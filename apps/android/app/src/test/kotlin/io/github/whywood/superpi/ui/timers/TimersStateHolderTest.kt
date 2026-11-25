package io.github.whywood.superpi.ui.timers

import io.github.whywood.superpi.R
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.NewTimer
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.RemoteBotRef
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperThreadState
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.Timer
import io.github.whywood.superpi.runtime.TimerPatch
import io.github.whywood.superpi.runtime.TimerService
import io.github.whywood.superpi.runtime.TimerStore
import io.github.whywood.superpi.runtime.TimerTrigger
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerInspectResult
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerProgressEvent
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerSessionSnapshot
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.ui.chat.testing.FakeAgentRuntime
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimersStateHolderTest {
    @Test
    fun loadPublishesItemsAndResolvesConversationTitles() = runTest {
        val source = FakeTimersSource()
        source.items.value = listOf(
            timer("morning", "Morning brief", sessionId = "session-a"),
            timer("clear", "Clear inbox", sessionId = "session-b"),
        )
        source.titles = mapOf("session-a" to "Weekly planning", "session-b" to null)
        val holder = TimersStateHolder(source, backgroundScope)

        holder.load()
        runCurrent()

        val state = holder.state.value
        assertFalse(state.loading)
        assertNull(state.errorRes)
        assertEquals(listOf("morning", "clear"), state.items.map { it.id })
        assertEquals(mapOf("session-a" to "Weekly planning", "session-b" to null), state.titles)
    }

    @Test
    fun loadFailureSetsErrorAndStopsLoading() = runTest {
        val source = FakeTimersSource()
        source.listFailure = IOException("boom")
        val holder = TimersStateHolder(source, backgroundScope)

        holder.load()

        val state = holder.state.value
        assertFalse(state.loading)
        assertEquals(R.string.chat_reload_failed, state.errorRes)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun saveFailureKeepsEditorOpenAndSetsError() = runTest {
        val source = FakeTimersSource()
        source.createFailure = IOException("disk full")
        val holder = TimersStateHolder(source, backgroundScope)
        holder.load()
        holder.openNew()

        holder.save(draft(sessionId = "session-a"))

        val state = holder.state.value
        assertTrue(state.editorOpen)
        assertFalse(state.busy)
        assertEquals(R.string.timer_error_save_failed, state.errorRes)
        assertEquals(1, source.createCalls)
    }

    @Test
    fun saveSuccessClosesEditor() = runTest {
        val source = FakeTimersSource()
        val holder = TimersStateHolder(source, backgroundScope)
        holder.load()
        holder.openNew()

        holder.save(draft(sessionId = "session-a"))
        runCurrent()

        val state = holder.state.value
        assertFalse(state.editorOpen)
        assertNull(state.errorRes)
        assertEquals(listOf("created"), state.items.map { it.id })
    }

    @Test
    fun setEnabledWritesTimerPatch() = runTest {
        val source = FakeTimersSource()
        source.items.value = listOf(timer("morning", "Morning brief"))
        val holder = TimersStateHolder(source, backgroundScope)
        holder.load()

        holder.setEnabled("morning", false)
        runCurrent()

        assertEquals(listOf("morning" to TimerPatch(enabled = false)), source.updates)
        assertFalse(holder.state.value.items.single().enabled)
        assertNull(holder.state.value.errorRes)
    }

    @Test
    fun setEnabledFailureSetsError() = runTest {
        val source = FakeTimersSource()
        source.items.value = listOf(timer("morning", "Morning brief"))
        source.updateFailure = IOException("read-only")
        val holder = TimersStateHolder(source, backgroundScope)
        holder.load()

        holder.setEnabled("morning", false)

        assertEquals(R.string.timer_error_save_failed, holder.state.value.errorRes)
    }

    @Test
    fun queryFiltersByNamePromptAndConversationTitle() = runTest {
        val source = FakeTimersSource()
        source.items.value = listOf(
            timer("morning", "Morning brief", prompt = "Summarize today's news", sessionId = "session-a"),
            timer("clear", "Clear inbox", prompt = "Triage unread mail", sessionId = "session-b"),
        )
        source.titles = mapOf("session-a" to "Weekly planning")
        val holder = TimersStateHolder(source, backgroundScope)
        holder.load()

        holder.setQuery("planning")
        assertEquals(listOf("morning"), holder.state.value.filtered.map { it.id })

        holder.setQuery("triage")
        assertEquals(listOf("clear"), holder.state.value.filtered.map { it.id })

        holder.setQuery("  ")
        assertEquals(2, holder.state.value.filtered.size)
    }

    @Test
    fun loadOmitsRemoteWorkspaceConversations() = runTest {
        val source = FakeTimersSource()
        source.conversations = listOf(
            conversation("local-session", "/work"),
            conversation("remote-session", "/remote/phone-b/home"),
        )
        source.workspaces = listOf(
            RuntimeWorkspace(id = "ws-1", name = "Work", cwd = "/work"),
            RuntimeWorkspace(
                id = "remote:phone-b:home",
                name = "PI",
                cwd = "/remote/phone-b/home",
                remote = RemoteBotRef(
                    deviceId = "phone-b",
                    deviceName = "Backup Phone",
                    botId = "home",
                    botName = "PI",
                    online = true,
                ),
            ),
        )
        val holder = TimersStateHolder(source, backgroundScope)

        holder.load()

        assertEquals(listOf("local-session"), holder.state.value.conversations.map { it.id })
        assertEquals(setOf("/work"), holder.state.value.workspaceNames.keys)
    }

    @Test
    fun concurrentRefreshCoalescesIntoOneLoad() = runTest {
        val source = FakeTimersSource()
        val holder = TimersStateHolder(source, backgroundScope)
        holder.load()
        assertEquals(1, source.listCalls)

        val gate = CompletableDeferred<Unit>()
        source.listGate = gate
        launch { holder.refresh() }
        runCurrent()
        launch { holder.refresh() }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, source.listCalls)
        assertNull(holder.state.value.errorRes)
    }

    private fun draft(sessionId: String = "session-a") = TimerDraft(
        name = "Morning brief",
        prompt = "Summarize today's news",
        sessionId = sessionId,
        sessionFile = "/work/session.jsonl",
        cwd = "/work",
    )

    private fun conversation(id: String, cwd: String) = ConversationSummary(
        id = id,
        title = id,
        updatedAt = 0L,
        filePath = "$cwd/$id.jsonl",
        cwd = cwd,
    )

    @Test
    fun separatesNormalAndSuperModeTimersStrictly() = runTest {
        val source = FakeTimersSource()
        source.items.value = listOf(
            timer("normal-1", "Normal timer", sessionId = "session-a"),
            timer("super-1", "Super timer", sessionId = "super:router"),
            timer("worker-1", "Worker timer", sessionId = "worker:wrk-abc"),
        )
        source.titles = mapOf(
            "session-a" to "General Chat",
            "super:router" to "Super PI",
            "worker:wrk-abc" to "PI · Monitor PR",
        )

        val normalHolder = TimersStateHolder(source, backgroundScope, superMode = false)
        normalHolder.load()
        runCurrent()
        assertEquals(listOf("normal-1"), normalHolder.state.value.items.map { it.id })

        val superHolder = TimersStateHolder(source, backgroundScope, superMode = true)
        superHolder.load()
        runCurrent()
        assertEquals(listOf("super-1", "worker-1"), superHolder.state.value.items.map { it.id })
        assertEquals("Super PI", superHolder.state.value.titles["super:router"])
        assertEquals("PI · Monitor PR", superHolder.state.value.titles["worker:wrk-abc"])
    }

    @Test
    fun timerServiceDataSourceReturnsNullForMissingWorkerInsteadOfRawWorkerId() = runTest {
        val dir = Files.createTempDirectory("timers-missing-worker").toFile()
        try {
            val agentRuntime = FakeAgentRuntime()
            val store = TimerStore(dir)
            val timerService = TimerService(agentRuntime, store, backgroundScope)
            val workersFlow = MutableStateFlow(
                listOf(
                    WorkerRunRecord(
                        workerId = "wrk-present",
                        botRef = SuperRuntime.HOME_BOT_REF,
                        botId = "home",
                        botName = "PI",
                        title = "Watch logs",
                        status = WorkerStatus.IDLE,
                        lastOutcome = WorkerOutcome.SUCCEEDED,
                        turnCount = 1,
                        createdAt = 1_000L,
                        updatedAt = 1_000L,
                    ),
                ),
            )
            val superRuntime = object : SuperRuntime {
                override val threadState: StateFlow<SuperThreadState> = MutableStateFlow(SuperThreadState())
                override val workers: StateFlow<List<WorkerRunRecord>> = workersFlow
                override val blueprints: StateFlow<List<BotBlueprint>> = MutableStateFlow(emptyList())
                override val pendingWorkerQuestions: StateFlow<List<PendingQuestion>> = MutableStateFlow(emptyList())
                override val workerProgress: SharedFlow<WorkerProgressEvent> = MutableSharedFlow()
                override val superThreadEvents: SharedFlow<RuntimeEvent> = MutableSharedFlow()
                override fun bindTimerHandler() = Unit
                override suspend fun ensureStarted() = Unit
                override suspend fun send(text: String, images: List<ImagePart>) = Unit
                override suspend fun steer(text: String, images: List<ImagePart>) = Unit
                override fun abortSuper() = Unit
                override suspend fun compactSuper() = Unit
                override suspend fun archiveAndResetSuper() = Unit
                override suspend fun answerSuperQuestion(requestId: String, answers: List<QuestionAnswer>) = Unit
                override suspend fun cancelSuperQuestion(requestId: String) = Unit
                override suspend fun answerWorkerQuestion(workerId: String, requestId: String, answers: List<QuestionAnswer>) = Unit
                override suspend fun dismissWorkerQuestion(workerId: String, requestId: String) = Unit
                override suspend fun stopWorker(workerId: String) = Unit
                override suspend fun deleteWorker(workerId: String) = Unit
                override suspend fun compactWorker(workerId: String) = Unit
                override suspend fun inspectWorker(workerId: String, tail: Int): WorkerInspectResult? = null
                override fun observeWorkerSession(workerId: String): StateFlow<WorkerSessionSnapshot> =
                    MutableStateFlow(WorkerSessionSnapshot(workerId))
                override suspend fun loadWorkerSession(workerId: String) = Unit
                override suspend fun sendToWorker(workerId: String, text: String, images: List<ImagePart>) = Unit
                override suspend fun changeWorkerModel(workerId: String, providerId: String, modelId: String, thinkingLevel: ThinkingLevel) = Unit
                override suspend fun changeWorkerThinking(workerId: String, thinkingLevel: ThinkingLevel) = Unit
                override fun workerWorkspaceDir(workerId: String): File? = null
                override suspend fun setBotEnabledInSuper(botRef: String, enabled: Boolean) = Unit
                override suspend fun refreshBlueprints() = Unit
                override suspend fun changeSuperModel(providerId: String, modelId: String, thinkingLevel: ThinkingLevel) = Unit
                override suspend fun changeSuperThinking(thinkingLevel: ThinkingLevel) = Unit
                override suspend fun resolveArtifactFile(refOrPath: String): File? = null
                override fun cardById(cardId: String): WorkerCardRef? = null
                override fun dismissError() = Unit
                override fun dismissWorkerError(workerId: String) = Unit
            }
            val dataSource = TimerServiceDataSource(
                service = timerService,
                runtime = agentRuntime,
                superRuntime = superRuntime,
                superMode = true,
            )

            assertEquals("PI · Watch logs", dataSource.sessionTitle("worker:wrk-present"))
            assertNull(dataSource.sessionTitle("worker:wrk-missing"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun timer(id: String, name: String, prompt: String = "prompt", sessionId: String = "") = Timer(
        id = id,
        name = name,
        prompt = prompt,
        trigger = TimerTrigger.Daily(LocalTime.of(9, 0)),
        sessionId = sessionId,
        sessionFile = "/work/$id.jsonl",
        cwd = "/work",
    )

    private class FakeTimersSource : TimersDataSource {
        val items = MutableStateFlow<List<Timer>>(emptyList())
        override val timers: StateFlow<List<Timer>> = items
        var listCalls = 0
        var createCalls = 0
        val updates = mutableListOf<Pair<String, TimerPatch>>()
        var listFailure: Exception? = null
        var createFailure: Exception? = null
        var updateFailure: Exception? = null
        var listGate: CompletableDeferred<Unit>? = null
        var titles: Map<String, String?> = emptyMap()
        var conversations: List<ConversationSummary> = emptyList()
        var workspaces: List<RuntimeWorkspace> = emptyList()

        override suspend fun list(): List<Timer> {
            listCalls++
            listGate?.await()
            listFailure?.let { throw it }
            return items.value
        }

        override suspend fun create(newTimer: NewTimer): Timer {
            createCalls++
            createFailure?.let { throw it }
            val created = Timer(
                id = "created",
                name = newTimer.name,
                prompt = newTimer.prompt,
                trigger = newTimer.trigger,
                enabled = newTimer.enabled,
                sessionId = newTimer.sessionId,
                sessionFile = newTimer.sessionFile,
                cwd = newTimer.cwd,
            )
            items.value = items.value + created
            return created
        }

        override suspend fun update(id: String, patch: TimerPatch): Timer? {
            updates += id to patch
            updateFailure?.let { throw it }
            items.value = items.value.map { timer ->
                if (timer.id != id) {
                    timer
                } else {
                    timer.copy(
                        name = patch.name ?: timer.name,
                        prompt = patch.prompt ?: timer.prompt,
                        enabled = patch.enabled ?: timer.enabled,
                    )
                }
            }
            return items.value.firstOrNull { it.id == id }
        }

        override suspend fun delete(id: String): Boolean {
            items.value = items.value.filterNot { it.id == id }
            return true
        }

        override suspend fun runNow(id: String): Result<Unit> = Result.success(Unit)

        override suspend fun sessionTitle(sessionId: String): String? = titles[sessionId]

        override suspend fun conversations(): List<ConversationSummary> = conversations

        override suspend fun workspaces(): List<RuntimeWorkspace> = workspaces
    }
}
