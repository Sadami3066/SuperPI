package io.github.whywood.superpi.ui.superagent

import android.net.Uri
import io.github.whywood.superpi.conversation.ConversationStrings
import io.github.whywood.superpi.data.AttachmentKind
import io.github.whywood.superpi.data.AttachmentRef
import io.github.whywood.superpi.data.AttachmentStore
import io.github.whywood.superpi.data.ImageNormalizer
import io.github.whywood.superpi.data.NormalizedImage
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerSessionSnapshot
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.isBusyForCommands
import io.github.whywood.superpi.runtime.resolveThinkingLevel
import io.github.whywood.superpi.ui.common.chat.PresentedCardRef
import io.github.whywood.superpi.ui.common.chat.QuotedMessage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class WorkerChatUiState(
    val worker: WorkerRunRecord? = null,
    val session: WorkerSessionSnapshot = WorkerSessionSnapshot(workerId = ""),
    val messages: List<AssistantMessage> = emptyList(),
    val presentedCards: Map<String, PresentedCardRef> = emptyMap(),
    val draft: SuperComposerDraftState = SuperComposerDraftState(),
    val attachmentRoot: File = File("/"),
    val isRemoteOnline: Boolean = true,
    val models: List<ModelOption> = emptyList(),
    val thinkingLevels: List<ThinkingLevel> = listOf(ThinkingLevel.OFF),
    val error: String? = null,
    val stopping: Boolean = false,
    val deleting: Boolean = false,
)

private data class WorkerActionFlags(
    val stopping: Boolean = false,
    val deleting: Boolean = false,
)

internal class WorkerChatViewModel(
    private val workerId: String,
    private val superRuntime: SuperRuntime,
    private val providers: ProviderDirectory,
    private val configuredProviderIds: () -> Set<String>,
    private val attachmentStore: AttachmentStore,
    private val superDir: File,
    private val routerWorkspaceDir: File,
    private val scope: CoroutineScope,
    @Volatile private var strings: ConversationStrings = ConversationStrings.fallback(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val normalizeImage: suspend (File, String) -> NormalizedImage? = { file, mime ->
        ImageNormalizer.normalize(file, mime)
    },
) {
    private val draftState = MutableStateFlow(SuperComposerDraftState())
    private val expandedStepIds = MutableStateFlow<Set<Long>>(emptySet())
    private val actionFlags = MutableStateFlow(WorkerActionFlags())

    val state: StateFlow<WorkerChatUiState> = combine(
        combine(superRuntime.workers, superRuntime.blueprints) { workers, blueprints -> workers to blueprints },
        superRuntime.observeWorkerSession(workerId),
        draftState,
        expandedStepIds,
        actionFlags,
    ) { (workers, blueprints), session, draft, expanded, flags ->
        val worker = workers.firstOrNull { it.workerId == workerId }
        val isRemoteOnline = if (worker?.isRemote == true && blueprints.isNotEmpty()) {
            blueprints.firstOrNull { it.ref == worker.botRef }?.online == true
        } else {
            true
        }
        val root = currentAttachmentRoot(worker)
        val displayMessages = applyExpandedStepOverrides(session.messages, expanded)
        val mergedCards = mergePresentedCards(
            messages = displayMessages,
            workers = listOfNotNull(worker),
            lookupById = superRuntime::cardById,
        )
        val models = if (worker?.isRemote != true && session.providerId.isNotBlank()) {
            providers.models(session.providerId)
        } else {
            emptyList()
        }
        val thinkingLevels = models.firstOrNull { it.id == session.modelId }?.thinkingLevels
            ?.takeIf { it.isNotEmpty() }
            ?: session.thinkingLevels
        val activeStopping = flags.stopping &&
            (worker?.status?.isBusyForCommands == true || worker?.status == WorkerStatus.STALLED)
        WorkerChatUiState(
            worker = worker,
            session = session,
            messages = displayMessages,
            presentedCards = mergedCards,
            draft = draft,
            attachmentRoot = root,
            isRemoteOnline = isRemoteOnline,
            models = models,
            thinkingLevels = thinkingLevels,
            error = draft.localError ?: session.error,
            stopping = activeStopping,
            deleting = flags.deleting,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = WorkerChatUiState(
            session = WorkerSessionSnapshot(workerId = workerId),
            attachmentRoot = currentAttachmentRoot(null),
        ),
    )

    init {
        scope.launch {
            try {
                superRuntime.loadWorkerSession(workerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                draftState.update {
                    it.copy(localError = error.message?.takeIf { msg -> msg.isNotBlank() })
                }
            }
        }
    }

    fun updateStrings(next: ConversationStrings) {
        strings = next
    }

    fun configuredProviders(): List<ProviderSummary> {
        val configured = configuredProviderIds()
        return providers.summaries.filter { it.id in configured }
    }

    fun onInputChange(value: String) {
        draftState.update { it.copy(input = value, localError = null) }
    }

    fun setModelsOpen(open: Boolean) {
        draftState.update { it.copy(modelsOpen = open) }
    }

    fun toggleStep(stepId: Long) {
        expandedStepIds.update { current ->
            if (stepId in current) current - stepId else current + stepId
        }
    }

    fun quote(messageId: Long, text: String) {
        val excerpt = superQuoteExcerpt(text)
        if (excerpt.isEmpty()) return
        draftState.update { it.copy(quote = QuotedMessage(messageId, excerpt)) }
    }

    fun clearQuote() {
        draftState.update { if (it.quote == null) it else it.copy(quote = null) }
    }

    fun attach(uri: Uri) {
        val targetRoot = currentAttachmentRoot(state.value.worker)
        launchSuperAttachmentImport(
            uri = uri,
            targetDir = targetRoot,
            attachmentStore = attachmentStore,
            strings = strings,
            draftState = draftState,
            scope = scope,
            ioDispatcher = ioDispatcher,
        )
    }

    fun removeAttachment(id: String) {
        draftState.update { latest ->
            latest.copy(attachments = latest.attachments.filterNot { it.id == id })
        }
    }

    fun send() {
        sendDirect(state.value.draft.input)
    }

    fun sendDirect(rawText: String): Boolean {
        val snapshot = state.value
        val worker = snapshot.worker ?: return false
        val draft = snapshot.draft
        if (draft.importingAttachment || (worker.isRemote && !snapshot.isRemoteOnline)) return false
        val clean = rawText.trim()
        if (clean.isEmpty() && draft.attachments.isEmpty()) return false
        val root = snapshot.attachmentRoot
        val prompt = buildSuperPrompt(
            quote = draft.quote,
            attachments = draft.attachments,
            root = root,
            body = clean,
            remote = worker.isRemote,
        )
        val imageRefs = draft.attachments.filter { it.kind == AttachmentKind.IMAGE }
        draftState.update {
            it.copy(
                input = "",
                quote = null,
                attachments = emptyList(),
                localError = null,
            )
        }
        launchAction(strings.requestFailed) {
            val images = normalizeSuperImages(
                refs = imageRefs,
                root = root,
                attachmentStore = attachmentStore,
                normalizeImage = normalizeImage,
                ioDispatcher = ioDispatcher,
            )
            superRuntime.sendToWorker(workerId, prompt, images)
        }
        return true
    }

    fun stop() {
        actionFlags.update { it.copy(stopping = true) }
        scope.launch {
            try {
                superRuntime.stopWorker(workerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                actionFlags.update { it.copy(stopping = false) }
                draftState.update {
                    it.copy(localError = error.message?.takeIf { msg -> msg.isNotBlank() } ?: strings.requestFailed)
                }
            }
        }
    }

    fun retryMessage(messageId: Long) {
        val current = state.value
        val worker = current.worker ?: return
        if (worker.status.isBusyForCommands || current.session.streaming) return
        if (worker.isRemote && !current.isRemoteOnline) return
        val targetIndex = current.messages.indexOfFirst { it.id == messageId }
        val sourceMessages = if (targetIndex >= 0) current.messages.take(targetIndex + 1) else current.messages
        val lastUser = sourceMessages.lastOrNull { it.role == AssistantRole.USER } ?: return
        val text = lastUser.text.trim()
        if (text.isEmpty()) return
        dismissError()
        launchAction(strings.requestFailed) {
            superRuntime.sendToWorker(workerId, text)
        }
    }

    fun retryLastError() {
        val current = state.value
        val worker = current.worker
        if (worker == null || worker.status.isBusyForCommands || current.session.streaming) {
            dismissError()
            return
        }
        val lastUser = current.messages.lastOrNull { it.role == AssistantRole.USER }
        if (lastUser == null || lastUser.text.isBlank()) {
            dismissError()
            launchAction(strings.requestFailed) {
                superRuntime.loadWorkerSession(workerId)
            }
            return
        }
        dismissError()
        launchAction(strings.requestFailed) {
            superRuntime.sendToWorker(workerId, lastUser.text)
        }
    }

    fun dismissError() {
        draftState.update { it.copy(localError = null) }
        superRuntime.dismissWorkerError(workerId)
    }

    fun compact() {
        val current = state.value
        val worker = current.worker ?: return
        if (worker.status.isBusyForCommands || current.session.streaming) return
        if (worker.isRemote && !current.isRemoteOnline) return
        dismissError()
        launchAction(strings.compactionFailed) {
            superRuntime.compactWorker(workerId)
        }
    }

    fun setProvider(providerId: String) {
        val current = state.value
        if (current.worker?.isRemote == true) return
        val session = current.session
        if (providerId == session.providerId) return
        val options = providers.models(providerId)
        val target = options.firstOrNull { it.id == session.modelId } ?: options.firstOrNull() ?: return
        val thinking = target.thinkingLevels.resolveThinkingLevel(session.thinkingLevel)
        launchAction(strings.switchModelFailed) {
            superRuntime.changeWorkerModel(workerId, providerId, target.id, thinking)
        }
    }

    fun setModel(modelId: String) {
        val current = state.value
        if (current.worker?.isRemote == true) return
        val session = current.session
        val options = providers.models(session.providerId)
        val target = options.firstOrNull { it.id == modelId } ?: return
        val providerId = session.providerId.ifBlank { target.providerId }
        val thinking = target.thinkingLevels.resolveThinkingLevel(session.thinkingLevel)
        launchAction(strings.switchModelFailed) {
            superRuntime.changeWorkerModel(workerId, providerId, target.id, thinking)
        }
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        val current = state.value
        if (current.worker?.isRemote == true) return
        if (current.session.thinkingLevel == level) return
        launchAction(strings.switchThinkingFailed) {
            superRuntime.changeWorkerThinking(workerId, level)
        }
    }

    fun answerQuestion(requestId: String, answers: List<QuestionAnswer>) {
        launchAction(strings.requestFailed) {
            superRuntime.answerWorkerQuestion(workerId, requestId, answers)
        }
    }

    fun deleteWorker(onDeleted: () -> Unit) {
        if (actionFlags.value.deleting) return
        actionFlags.update { it.copy(deleting = true) }
        scope.launch {
            try {
                superRuntime.deleteWorker(workerId)
                onDeleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                actionFlags.update { it.copy(deleting = false) }
                draftState.update {
                    it.copy(localError = error.message?.takeIf { msg -> msg.isNotBlank() } ?: strings.requestFailed)
                }
            }
        }
    }

    private fun launchAction(fallbackError: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                draftState.update {
                    it.copy(localError = error.message?.takeIf { msg -> msg.isNotBlank() } ?: fallbackError)
                }
            }
        }
    }

    suspend fun resolveFile(raw: String): Pair<AttachmentRef, File>? =
        resolveSuperAttachment(
            raw = raw,
            superRuntime = superRuntime,
            attachmentStore = attachmentStore,
            superDir = superDir,
            routerWorkspaceDir = currentAttachmentRoot(state.value.worker),
            ioDispatcher = ioDispatcher,
        )

    private fun currentAttachmentRoot(worker: WorkerRunRecord?): File {
        if (worker?.isRemote == true) return routerWorkspaceDir
        return superRuntime.workerWorkspaceDir(workerId) ?: routerWorkspaceDir
    }
}
