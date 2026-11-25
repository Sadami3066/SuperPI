package io.github.whywood.superpi.runtime.superagent

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.QuestionOption
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.runtime.WorkerArtifactRef
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.WorkerStepSnapshot
import io.github.whywood.superpi.runtime.isBusyForCommands
import io.github.whywood.superpi.runtime.logWarn
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class SuperTaskStore(
    private val superDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dbFile = File(superDir, DB_FILE_NAME)
    private val driver = BundledSQLiteDriver()
    private val mutex = Mutex()

    @Volatile
    private var connection: SQLiteConnection? = null

    private val _workers = MutableStateFlow<List<WorkerRunRecord>>(emptyList())
    val workers: StateFlow<List<WorkerRunRecord>> = _workers.asStateFlow()

    private val _whitelist = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val whitelist: StateFlow<Map<String, Boolean>> = _whitelist.asStateFlow()

    private val cardCache = ConcurrentHashMap<String, WorkerCardRef>()
    private val artifactCache = ConcurrentHashMap<String, WorkerArtifactRef>()

    suspend fun ensure() = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLocked()
        }
    }

    suspend fun recoverStaleLocalWorkers(): List<WorkerRunRecord> = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLocked()
            val conn = checkNotNull(connection)
            val nowMs = clock()
            val stale = _workers.value.filter { worker ->
                !worker.isRemote && worker.status.isBusyForCommands
            }
            if (stale.isEmpty()) return@withLock emptyList()
            val updated = stale.map { worker ->
                worker.copy(
                    status = WorkerStatus.FAILED,
                    lastOutcome = WorkerOutcome.FAILED,
                    lastError = "Interrupted by app restart",
                    lastTurnSilent = false,
                    activeStepText = null,
                    pendingQuestion = null,
                    updatedAt = nowMs,
                    lastProgressAt = nowMs,
                    steps = worker.steps.map { step ->
                        if (step.status == ToolActivityStatus.RUNNING) {
                            step.copy(status = ToolActivityStatus.FAILED, isError = true, updatedAt = nowMs)
                        } else {
                            step
                        }
                    },
                )
            }
            writeTransactionLocked(conn) {
                updated.forEach { upsertWorkerRow(conn, it) }
            }
            updated.forEach(::applyWorkerUpsertLocked)
            updated
        }
    }

    fun isBotEnabled(botRef: String): Boolean {
        val current = _whitelist.value
        return current[botRef] ?: (botRef == SuperRuntime.HOME_BOT_REF)
    }

    suspend fun setBotEnabled(botRef: String, enabled: Boolean) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLocked()
            val conn = checkNotNull(connection)
            val nowMs = clock()
            writeTransactionLocked(conn) {
                conn.prepare(
                    """
                    INSERT INTO super_bot_whitelist(bot_ref, enabled, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(bot_ref) DO UPDATE SET enabled = excluded.enabled, updated_at = excluded.updated_at
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.bindText(1, botRef)
                    stmt.bindLong(2, if (enabled) 1L else 0L)
                    stmt.bindLong(3, nowMs)
                    stmt.step()
                }
            }
            _whitelist.value = _whitelist.value + (botRef to enabled)
        }
    }

    suspend fun listWorkers(): List<WorkerRunRecord> = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLocked()
            _workers.value
        }
    }

    suspend fun getWorker(workerId: String): WorkerRunRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLocked()
            _workers.value.firstOrNull { it.workerId == workerId }
        }
    }

    fun currentWorker(workerId: String): WorkerRunRecord? =
        _workers.value.firstOrNull { it.workerId == workerId }

    suspend fun upsertWorker(record: WorkerRunRecord) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLocked()
            val conn = checkNotNull(connection)
            writeTransactionLocked(conn) {
                upsertWorkerRow(conn, record)
                record.cards.forEach { upsertCardRow(conn, it) }
            }
            applyWorkerUpsertLocked(record)
        }
    }

    suspend fun updateWorker(
        workerId: String,
        transform: (WorkerRunRecord) -> WorkerRunRecord,
    ): WorkerRunRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLocked()
            val conn = checkNotNull(connection)
            val existing = _workers.value.firstOrNull { it.workerId == workerId } ?: return@withLock null
            val updated = transform(existing)
            writeTransactionLocked(conn) {
                upsertWorkerRow(conn, updated)
                updated.cards.forEach { upsertCardRow(conn, it) }
            }
            applyWorkerUpsertLocked(updated)
            updated
        }
    }

    suspend fun upsertCard(card: WorkerCardRef) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLocked()
            val conn = checkNotNull(connection)
            writeTransactionLocked(conn) {
                upsertCardRow(conn, card)
            }
            cardCache[card.cardId] = card
        }
    }

    suspend fun deleteWorker(workerId: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLocked()
            val conn = checkNotNull(connection)
            val existed = _workers.value.any { it.workerId == workerId }
            writeTransactionLocked(conn) {
                conn.prepare("DELETE FROM super_worker_cards WHERE worker_id = ?").use { stmt ->
                    stmt.bindText(1, workerId)
                    stmt.step()
                }
                conn.prepare("DELETE FROM super_workers WHERE worker_id = ?").use { stmt ->
                    stmt.bindText(1, workerId)
                    stmt.step()
                }
            }
            applyWorkerDeleteLocked(workerId)
            existed
        }
    }

    private fun applyWorkerUpsertLocked(record: WorkerRunRecord) {
        val current = _workers.value.toMutableList()
        val index = current.indexOfFirst { it.workerId == record.workerId }
        if (index >= 0) {
            current[index] = record
        } else {
            current += record
        }
        _workers.value = current.sortedWith(WORKER_ORDER)
        record.cards.forEach { cardCache[it.cardId] = it }
        artifactCache.keys.removeIf { it.startsWith("${record.workerId}:") }
        record.artifacts.forEach { artifact ->
            artifactCache[artifactKey(record.workerId, artifact.artifactId)] = artifact
        }
    }

    private fun applyWorkerDeleteLocked(workerId: String) {
        _workers.value = _workers.value.filterNot { it.workerId == workerId }
        cardCache.values.removeIf { it.workerId == workerId }
        artifactCache.keys.removeIf { it.startsWith("$workerId:") }
    }

    fun cardById(cardId: String): WorkerCardRef? {
        cardCache[cardId]?.let { return it }
        for (worker in _workers.value) {
            worker.cards.firstOrNull { it.cardId == cardId }?.let { return it }
        }
        return null
    }

    fun findArtifact(workerId: String, artifactId: String): WorkerArtifactRef? =
        artifactCache[artifactKey(workerId, artifactId)]
            ?: _workers.value.firstOrNull { it.workerId == workerId }?.artifacts?.firstOrNull { it.artifactId == artifactId }

    fun findArtifactById(artifactId: String): WorkerArtifactRef? =
        artifactCache.values.firstOrNull { it.artifactId == artifactId }
            ?: _workers.value.asSequence().flatMap { it.artifacts.asSequence() }.firstOrNull { it.artifactId == artifactId }

    suspend fun close() = withContext(ioDispatcher) {
        mutex.withLock {
            runCatching { connection?.close() }
            connection = null
        }
    }

    private fun ensureLocked() {
        superDir.mkdirs()
        if (connection != null) return
        val conn = openVerifiedConnection()
        connection = conn
        refreshStateLocked(conn)
    }

    private fun openVerifiedConnection(): SQLiteConnection {
        val opened = runCatching { openAndInit() }.getOrNull()
        if (opened != null) return opened
        quarantineCorruptDb()
        return openAndInit()
    }

    private fun openAndInit(): SQLiteConnection {
        val conn = driver.open(dbFile.absolutePath)
        try {
            conn.execSQL("PRAGMA journal_mode=WAL")
            conn.execSQL("PRAGMA foreign_keys=ON")
            conn.execSQL("PRAGMA busy_timeout=5000")
            val ok = conn.prepare("PRAGMA quick_check").use { stmt ->
                stmt.step() && stmt.getText(0).equals("ok", ignoreCase = true)
            }
            if (!ok) error("SQLite quick_check failed")
            createSchema(conn)
            conn.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
            return conn
        } catch (error: Throwable) {
            runCatching { conn.close() }
            throw error
        }
    }

    private fun createSchema(conn: SQLiteConnection) {
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS super_bot_whitelist (
                bot_ref TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS super_workers (
                worker_id TEXT PRIMARY KEY,
                bot_ref TEXT NOT NULL,
                bot_id TEXT NOT NULL,
                bot_name TEXT NOT NULL,
                bot_avatar TEXT,
                device_id TEXT,
                device_name TEXT NOT NULL,
                title TEXT NOT NULL,
                status TEXT NOT NULL,
                last_outcome TEXT NOT NULL,
                turn_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_progress_at INTEGER NOT NULL,
                last_summary TEXT,
                last_error TEXT,
                active_step_text TEXT,
                steps_json TEXT NOT NULL,
                artifacts_json TEXT NOT NULL,
                cards_json TEXT NOT NULL,
                pending_question_json TEXT,
                remote_last_seq INTEGER NOT NULL DEFAULT 0,
                last_turn_silent INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        ensureColumn(conn, "super_workers", "pending_question_json", "TEXT")
        ensureColumn(conn, "super_workers", "last_turn_silent", "INTEGER NOT NULL DEFAULT 0")
        conn.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_super_workers_bot ON super_workers(bot_ref, updated_at DESC)",
        )
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS super_worker_cards (
                card_id TEXT PRIMARY KEY,
                worker_id TEXT NOT NULL,
                bot_ref TEXT NOT NULL,
                tool_name TEXT NOT NULL,
                title TEXT,
                args_json TEXT NOT NULL,
                output_text TEXT NOT NULL,
                details_json TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        conn.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_super_worker_cards_worker ON super_worker_cards(worker_id)",
        )
    }

    private fun ensureColumn(conn: SQLiteConnection, table: String, column: String, type: String) {
        val exists = conn.prepare("PRAGMA table_info($table)").use { stmt ->
            var found = false
            while (stmt.step()) {
                if (stmt.getText(1) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            conn.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    private fun writeTransactionLocked(conn: SQLiteConnection, block: () -> Unit) {
        conn.execSQL("BEGIN IMMEDIATE TRANSACTION")
        try {
            block()
            conn.execSQL("COMMIT")
        } catch (error: Throwable) {
            runCatching { conn.execSQL("ROLLBACK") }
            throw error
        }
    }

    private fun upsertWorkerRow(conn: SQLiteConnection, record: WorkerRunRecord) {
        conn.prepare(
            """
            INSERT INTO super_workers(
                worker_id, bot_ref, bot_id, bot_name, bot_avatar, device_id, device_name,
                title, status, last_outcome, turn_count, created_at, updated_at, last_progress_at,
                last_summary, last_error, active_step_text, steps_json, artifacts_json, cards_json,
                pending_question_json, remote_last_seq, last_turn_silent
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(worker_id) DO UPDATE SET
                bot_ref = excluded.bot_ref,
                bot_id = excluded.bot_id,
                bot_name = excluded.bot_name,
                bot_avatar = excluded.bot_avatar,
                device_id = excluded.device_id,
                device_name = excluded.device_name,
                title = excluded.title,
                status = excluded.status,
                last_outcome = excluded.last_outcome,
                turn_count = excluded.turn_count,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                last_progress_at = excluded.last_progress_at,
                last_summary = excluded.last_summary,
                last_error = excluded.last_error,
                active_step_text = excluded.active_step_text,
                steps_json = excluded.steps_json,
                artifacts_json = excluded.artifacts_json,
                cards_json = excluded.cards_json,
                pending_question_json = excluded.pending_question_json,
                remote_last_seq = excluded.remote_last_seq,
                last_turn_silent = excluded.last_turn_silent
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, record.workerId)
            stmt.bindText(2, record.botRef)
            stmt.bindText(3, record.botId)
            stmt.bindText(4, record.botName)
            stmt.bindTextOrNull(5, record.botAvatar)
            stmt.bindTextOrNull(6, record.deviceId)
            stmt.bindText(7, record.deviceName)
            stmt.bindText(8, record.title)
            stmt.bindText(9, record.status.name)
            stmt.bindText(10, record.lastOutcome.name)
            stmt.bindLong(11, record.turnCount.toLong())
            stmt.bindLong(12, record.createdAt)
            stmt.bindLong(13, record.updatedAt)
            stmt.bindLong(14, record.lastProgressAt)
            stmt.bindTextOrNull(15, record.lastSummary)
            stmt.bindTextOrNull(16, record.lastError)
            stmt.bindTextOrNull(17, record.activeStepText)
            stmt.bindText(18, encodeSteps(record.steps))
            stmt.bindText(19, encodeArtifacts(record.artifacts))
            stmt.bindText(20, encodeCards(record.cards))
            stmt.bindTextOrNull(21, record.pendingQuestion?.let(::encodePendingQuestion))
            stmt.bindLong(22, record.remoteLastSeq)
            stmt.bindLong(23, if (record.lastTurnSilent) 1L else 0L)
            stmt.step()
        }
    }

    private fun upsertCardRow(conn: SQLiteConnection, card: WorkerCardRef) {
        conn.prepare(
            """
            INSERT INTO super_worker_cards(
                card_id, worker_id, bot_ref, tool_name, title, args_json, output_text, details_json, payload_json, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(card_id) DO UPDATE SET
                worker_id = excluded.worker_id,
                bot_ref = excluded.bot_ref,
                tool_name = excluded.tool_name,
                title = excluded.title,
                args_json = excluded.args_json,
                output_text = excluded.output_text,
                details_json = excluded.details_json,
                payload_json = excluded.payload_json,
                created_at = excluded.created_at
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, card.cardId)
            stmt.bindText(2, card.workerId)
            stmt.bindText(3, card.botRef)
            stmt.bindText(4, card.toolName)
            stmt.bindTextOrNull(5, card.title)
            stmt.bindText(6, card.argsJson)
            stmt.bindText(7, card.outputText)
            stmt.bindText(8, card.detailsJson)
            stmt.bindText(9, card.payloadJson)
            stmt.bindLong(10, card.createdAt)
            stmt.step()
        }
    }

    private fun refreshStateLocked(conn: SQLiteConnection) {
        val loadedWhitelist = LinkedHashMap<String, Boolean>()
        conn.prepare("SELECT bot_ref, enabled FROM super_bot_whitelist").use { stmt ->
            while (stmt.step()) {
                val ref = stmt.getText(0)
                val enabled = stmt.getLong(1) != 0L
                loadedWhitelist[ref] = enabled
            }
        }
        _whitelist.value = loadedWhitelist

        val loadedWorkers = ArrayList<WorkerRunRecord>()
        conn.prepare(
            """
            SELECT worker_id, bot_ref, bot_id, bot_name, bot_avatar, device_id, device_name,
                   title, status, last_outcome, turn_count, created_at, updated_at, last_progress_at,
                   last_summary, last_error, active_step_text, steps_json, artifacts_json, cards_json,
                   pending_question_json, remote_last_seq, last_turn_silent
            FROM super_workers
            ORDER BY updated_at DESC, created_at DESC
            """.trimIndent(),
        ).use { stmt ->
            while (stmt.step()) {
                val workerId = stmt.getText(0)
                val status = runCatching { WorkerStatus.valueOf(stmt.getText(8)) }.getOrDefault(WorkerStatus.IDLE)
                val outcome = runCatching { WorkerOutcome.valueOf(stmt.getText(9)) }.getOrDefault(WorkerOutcome.NONE)
                val artifacts = decodeArtifacts(stmt.getText(18))
                val cards = decodeCards(stmt.getText(19))
                val pendingQuestion = stmt.getTextOrNull(20)?.let(::decodePendingQuestion)
                val record = WorkerRunRecord(
                    workerId = workerId,
                    botRef = stmt.getText(1),
                    botId = stmt.getText(2),
                    botName = stmt.getText(3),
                    botAvatar = stmt.getTextOrNull(4),
                    deviceId = stmt.getTextOrNull(5),
                    deviceName = stmt.getText(6),
                    title = stmt.getText(7),
                    status = status,
                    lastOutcome = outcome,
                    turnCount = stmt.getLong(10).toInt(),
                    createdAt = stmt.getLong(11),
                    updatedAt = stmt.getLong(12),
                    lastProgressAt = stmt.getLong(13),
                    lastSummary = stmt.getTextOrNull(14),
                    lastError = stmt.getTextOrNull(15),
                    activeStepText = stmt.getTextOrNull(16),
                    steps = decodeSteps(stmt.getText(17)),
                    artifacts = artifacts,
                    cards = cards,
                    pendingQuestion = pendingQuestion,
                    remoteLastSeq = stmt.getLong(21),
                    lastTurnSilent = stmt.getLong(22) != 0L,
                )
                loadedWorkers += record
            }
        }
        _workers.value = loadedWorkers

        val nextCards = HashMap<String, WorkerCardRef>()
        conn.prepare(
            """
            SELECT card_id, worker_id, bot_ref, tool_name, title, args_json, output_text, details_json, payload_json, created_at
            FROM super_worker_cards
            """.trimIndent(),
        ).use { stmt ->
            while (stmt.step()) {
                val card = WorkerCardRef(
                    cardId = stmt.getText(0),
                    workerId = stmt.getText(1),
                    botRef = stmt.getText(2),
                    toolName = stmt.getText(3),
                    title = stmt.getTextOrNull(4),
                    argsJson = stmt.getText(5),
                    outputText = stmt.getText(6),
                    detailsJson = stmt.getText(7),
                    payloadJson = stmt.getText(8),
                    createdAt = stmt.getLong(9),
                )
                nextCards[card.cardId] = card
            }
        }
        cardCache.clear()
        cardCache.putAll(nextCards)

        val nextArtifacts = HashMap<String, WorkerArtifactRef>()
        for (worker in loadedWorkers) {
            for (artifact in worker.artifacts) {
                nextArtifacts[artifactKey(worker.workerId, artifact.artifactId)] = artifact
            }
        }
        artifactCache.clear()
        artifactCache.putAll(nextArtifacts)
    }

    private fun quarantineCorruptDb() {
        val stamp = CORRUPT_STAMP.format(LocalDateTime.now())
        listOf("", "-wal", "-shm").forEach { suffix ->
            val candidate = File(superDir, "$DB_FILE_NAME$suffix")
            if (candidate.exists()) {
                val backup = File(superDir, "$DB_FILE_NAME.$stamp.corrupt$suffix")
                if (!candidate.renameTo(backup)) {
                    logWarn(TAG, "failed to backup corrupt super task db ${candidate.name}")
                    candidate.delete()
                }
            }
        }
    }

    private fun encodeSteps(steps: List<WorkerStepSnapshot>): String = buildJsonArray {
        steps.forEach { step ->
            add(
                buildJsonObject {
                    put("callId", step.callId)
                    put("toolName", step.toolName)
                    put("argsJson", step.argsJson)
                    put("status", step.status.name)
                    step.outputPreview?.let { put("outputPreview", it) }
                    put("isError", step.isError)
                    put("updatedAt", step.updatedAt)
                },
            )
        }
    }.toString()

    private fun decodeSteps(raw: String): List<WorkerStepSnapshot> {
        val array = runCatching { JSON.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val callId = obj.str("callId") ?: return@mapNotNull null
            val toolName = obj.str("toolName") ?: return@mapNotNull null
            val status = obj.str("status")?.let {
                runCatching { ToolActivityStatus.valueOf(it) }.getOrNull()
            } ?: ToolActivityStatus.DONE
            WorkerStepSnapshot(
                callId = callId,
                toolName = toolName,
                argsJson = obj.str("argsJson") ?: "{}",
                status = status,
                outputPreview = obj.str("outputPreview"),
                isError = obj.bool("isError") ?: false,
                updatedAt = obj.long("updatedAt") ?: 0L,
            )
        }
    }

    private fun encodeArtifacts(artifacts: List<WorkerArtifactRef>): String = buildJsonArray {
        artifacts.forEach { art ->
            add(
                buildJsonObject {
                    put("artifactId", art.artifactId)
                    put("workerId", art.workerId)
                    put("botRef", art.botRef)
                    put("kind", art.kind)
                    put("path", art.path)
                    put("name", art.name)
                    art.title?.let { put("title", it) }
                    art.mimeType?.let { put("mimeType", it) }
                    art.sizeBytes?.let { put("sizeBytes", it) }
                    art.remoteDeviceId?.let { put("remoteDeviceId", it) }
                    art.remoteBotId?.let { put("remoteBotId", it) }
                    art.remoteArtifactId?.let { put("remoteArtifactId", it) }
                    put("createdAt", art.createdAt)
                },
            )
        }
    }.toString()

    private fun decodeArtifacts(raw: String): List<WorkerArtifactRef> {
        val array = runCatching { JSON.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val artifactId = obj.str("artifactId") ?: return@mapNotNull null
            val workerId = obj.str("workerId") ?: return@mapNotNull null
            val botRef = obj.str("botRef") ?: return@mapNotNull null
            val kind = obj.str("kind") ?: return@mapNotNull null
            val path = obj.str("path") ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            WorkerArtifactRef(
                artifactId = artifactId,
                workerId = workerId,
                botRef = botRef,
                kind = kind,
                path = path,
                name = name,
                title = obj.str("title"),
                mimeType = obj.str("mimeType"),
                sizeBytes = obj.long("sizeBytes"),
                remoteDeviceId = obj.str("remoteDeviceId"),
                remoteBotId = obj.str("remoteBotId"),
                remoteArtifactId = obj.str("remoteArtifactId"),
                createdAt = obj.long("createdAt") ?: 0L,
            )
        }
    }

    private fun encodeCards(cards: List<WorkerCardRef>): String = buildJsonArray {
        cards.forEach { card ->
            add(
                buildJsonObject {
                    put("cardId", card.cardId)
                    put("workerId", card.workerId)
                    put("botRef", card.botRef)
                    put("toolName", card.toolName)
                    card.title?.let { put("title", it) }
                    put("argsJson", card.argsJson)
                    put("outputText", card.outputText)
                    put("detailsJson", card.detailsJson)
                    put("payloadJson", card.payloadJson)
                    put("createdAt", card.createdAt)
                },
            )
        }
    }.toString()

    private fun decodeCards(raw: String): List<WorkerCardRef> {
        val array = runCatching { JSON.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val cardId = obj.str("cardId") ?: return@mapNotNull null
            val workerId = obj.str("workerId") ?: return@mapNotNull null
            val botRef = obj.str("botRef") ?: return@mapNotNull null
            val toolName = obj.str("toolName") ?: return@mapNotNull null
            WorkerCardRef(
                cardId = cardId,
                workerId = workerId,
                botRef = botRef,
                toolName = toolName,
                title = obj.str("title"),
                argsJson = obj.str("argsJson") ?: "{}",
                outputText = obj.str("outputText").orEmpty(),
                detailsJson = obj.str("detailsJson").orEmpty(),
                payloadJson = obj.str("payloadJson") ?: "{}",
                createdAt = obj.long("createdAt") ?: 0L,
            )
        }
    }

    private fun encodePendingQuestion(pq: PendingQuestion): String = buildJsonObject {
        put("requestId", pq.requestId)
        put("sessionId", pq.sessionId)
        put("toolCallId", pq.toolCallId)
        put(
            "questions",
            buildJsonArray {
                pq.questions.forEach { q ->
                    add(
                        buildJsonObject {
                            put("id", q.id)
                            q.header?.let { put("header", it) }
                            put("question", q.question)
                            put("multiSelect", q.multiSelect)
                            put(
                                "options",
                                buildJsonArray {
                                    q.options.forEach { opt ->
                                        add(
                                            buildJsonObject {
                                                put("label", opt.label)
                                                opt.description?.let { put("description", it) }
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }.toString()

    private fun decodePendingQuestion(raw: String): PendingQuestion? {
        val obj = runCatching { JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val requestId = obj.str("requestId") ?: return null
        val sessionId = obj.str("sessionId") ?: return null
        val toolCallId = obj.str("toolCallId").orEmpty()
        val questions = (obj["questions"] as? JsonArray)?.mapNotNull { el ->
            val qObj = el as? JsonObject ?: return@mapNotNull null
            val qId = qObj.str("id") ?: return@mapNotNull null
            val text = qObj.str("question") ?: return@mapNotNull null
            val options = (qObj["options"] as? JsonArray)?.mapNotNull { optEl ->
                val optObj = optEl as? JsonObject ?: return@mapNotNull null
                val label = optObj.str("label") ?: return@mapNotNull null
                QuestionOption(label = label, description = optObj.str("description"))
            }.orEmpty()
            UserQuestion(
                id = qId,
                header = qObj.str("header"),
                question = text,
                options = options,
                multiSelect = qObj.bool("multiSelect") ?: false,
            )
        }.orEmpty()
        return PendingQuestion(
            requestId = requestId,
            sessionId = sessionId,
            toolCallId = toolCallId,
            questions = questions,
        )
    }

    private fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindText(index, value)
    }

    private fun SQLiteStatement.getTextOrNull(index: Int): String? =
        if (isNull(index)) null else getText(index)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun artifactKey(workerId: String, artifactId: String): String = "$workerId:$artifactId"

    private companion object {
        private const val TAG = "SuperTaskStore"
        private const val DB_FILE_NAME = "super_tasks.db"
        private const val SCHEMA_VERSION = 1
        private val CORRUPT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val JSON = Json { ignoreUnknownKeys = true }
        private val WORKER_ORDER = compareByDescending<WorkerRunRecord> { it.updatedAt }
            .thenByDescending { it.createdAt }
    }
}
