package io.github.whywood.superpi.runtime

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

interface MiniappData {
    suspend fun get(key: String): JsonElement?

    suspend fun set(key: String, value: JsonElement)

    suspend fun remove(key: String): Boolean

    suspend fun keys(): List<String>

    fun close()
}

class SqliteMiniappData(private val databaseFile: File) : MiniappData {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private var connection: SQLiteConnection? = null

    override suspend fun get(key: String): JsonElement? = withContext(Dispatchers.IO) {
        synchronized(lock) { database().read(key) }
    }

    override suspend fun set(key: String, value: JsonElement) {
        withContext(Dispatchers.IO) {
            synchronized(lock) { database().write(key, value.toString()) }
        }
    }

    override suspend fun remove(key: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val conn = database()
            conn.prepare("DELETE FROM $TABLE WHERE $COLUMN_KEY = ?").use { stmt ->
                stmt.bindText(1, key)
                stmt.step()
            }
            conn.prepare("SELECT changes()").use { stmt ->
                if (stmt.step()) stmt.getInt(0) > 0 else false
            }
        }
    }

    override suspend fun keys(): List<String> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            database().prepare("SELECT $COLUMN_KEY FROM $TABLE ORDER BY $COLUMN_KEY ASC").use { stmt ->
                buildList {
                    while (stmt.step()) {
                        add(stmt.getText(0))
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed.compareAndSet(false, true)) {
                connection?.close()
                connection = null
            }
        }
    }

    private fun database(): SQLiteConnection {
        check(!closed.get()) { "miniapp data store is closed" }
        connection?.let { return it }
        val parent = databaseFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("cannot create miniapp data directory: ${parent.absolutePath}")
        }
        val opened = BundledSQLiteDriver().open(databaseFile.absolutePath)
        try {
            opened.execSQL("PRAGMA busy_timeout=5000")
            opened.execSQL("PRAGMA journal_mode=WAL")
            opened.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE ($COLUMN_KEY TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL, updated_at INTEGER NOT NULL)",
            )
            connection = opened
            return opened
        } catch (error: Throwable) {
            runCatching { opened.close() }
            throw error
        }
    }

    private fun SQLiteConnection.read(key: String): JsonElement? =
        prepare("SELECT value FROM $TABLE WHERE $COLUMN_KEY = ?").use { stmt ->
            stmt.bindText(1, key)
            if (!stmt.step()) return null
            val stored = stmt.getText(0)
            runCatching { Json.parseToJsonElement(stored) }.getOrNull()
        }

    private fun SQLiteConnection.write(key: String, value: String) {
        prepare("INSERT OR REPLACE INTO $TABLE ($COLUMN_KEY, value, updated_at) VALUES (?, ?, ?)").use { stmt ->
            stmt.bindText(1, key)
            stmt.bindText(2, value)
            stmt.bindLong(3, System.currentTimeMillis())
            stmt.step()
        }
    }

    private companion object {
        const val TABLE = "kv"
        const val COLUMN_KEY = "key"
    }
}
