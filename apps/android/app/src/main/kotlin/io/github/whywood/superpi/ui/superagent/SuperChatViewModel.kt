package io.github.whywood.superpi.ui.superagent

import android.net.Uri
import io.github.whywood.superpi.conversation.ConversationStrings
import io.github.whywood.superpi.data.AttachmentImport
import io.github.whywood.superpi.data.AttachmentKind
import io.github.whywood.superpi.data.AttachmentRef
import io.github.whywood.superpi.data.AttachmentStore
import io.github.whywood.superpi.data.ImageNormalizer
import io.github.whywood.superpi.data.NormalizedImage
import io.github.whywood.superpi.data.attachmentFile
import io.github.whywood.superpi.data.attachmentKind
import io.github.whywood.superpi.data.encodeAttachmentBlock
import io.github.whywood.superpi.data.guessMime
import io.github.whywood.superpi.design.markdownToPlainText
import io.github.whywood.superpi.plugin.api.Cards
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperThreadState
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolResultPayload
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.isActive
import io.github.whywood.superpi.runtime.resolveThinkingLevel
import io.github.whywood.superpi.ui.common.chat.PresentedCardRef
import io.github.whywood.superpi.ui.common.chat.PresentedItemType
import io.github.whywood.superpi.ui.common.chat.QuotedMessage
import io.github.whywood.superpi.ui.common.chat.parsePresentedItems
import io.github.whywood.superpi.ui.common.chat.presentedCardLookup
import io.github.whywood.superpi.ui.common.chat.state.MAX_ATTACHMENTS
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class SuperComposerDraftState(
    val input: String = "",
    val quote: QuotedMessage? = null,
    val attachments: List<AttachmentRef> = emptyList(),
    val importingAttachment: Boolean = false,
    val modelsOpen: Boolean = false,
    val localError: String? = null,
)

internal data class SuperChatUiState(
    val thread: SuperThreadState = SuperThreadState(),
    val messages: List<AssistantMessage> = emptyList(),
    val workers: List<WorkerRunRecord> = emptyList(),
    val activeWorkers: List<WorkerRunRecord> = emptyList(),
    val pendingWorkerApprovals: List<Pair<WorkerRunRecord, PendingQuestion>> = emptyList(),
    val enabledTeamCount: Int = 0,
    val presentedCards: Map<String, PresentedCardRef> = emptyMap(),
    val draft: SuperComposerDraftState = SuperComposerDraftState(),
    val models: List<ModelOption> = emptyList(),
    val thinkingLevels: List<ThinkingLevel> = listOf(ThinkingLevel.OFF),
    val error: String? = null,
)

internal class SuperChatViewModel(
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
    private val modelRefreshTick = MutableStateFlow(0L)

    val state: StateFlow<SuperChatUiState> = combine(
        superRuntime.threadState,
        superRuntime.workers,
        superRuntime.blueprints,
        draftState,
        combine(expandedStepIds, modelRefreshTick) { expanded, tick -> expanded to tick },
    ) { thread, workers, blueprints, draft, (expanded, _) ->
        buildUiState(
            thread = thread,
            workers = workers,
            blueprints = blueprints,
            draft = draft,
            expanded = expanded,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SuperChatUiState())

    fun updateStrings(next: ConversationStrings) {
        strings = next
    }

    fun refreshModelCatalog() {
        modelRefreshTick.update { it + 1L }
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
        launchSuperAttachmentImport(
            uri = uri,
            targetDir = routerWorkspaceDir,
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
        val draft = snapshot.draft
        if (!snapshot.thread.ready || draft.importingAttachment) return false
        val clean = rawText.trim()
        if (clean.isEmpty() && draft.attachments.isEmpty()) return false
        val prompt = buildSuperPrompt(draft.quote, draft.attachments, routerWorkspaceDir, clean)
        val imageRefs = draft.attachments.filter { it.kind == AttachmentKind.IMAGE }
        val wasBusy = snapshot.thread.busy
        draftState.update {
            it.copy(
                input = "",
                quote = null,
                attachments = emptyList(),
                localError = null,
            )
        }
        superRuntime.dismissError()
        launchAction(strings.requestFailed) {
            val images = normalizeSuperImages(
                refs = imageRefs,
                root = routerWorkspaceDir,
                attachmentStore = attachmentStore,
                normalizeImage = normalizeImage,
                ioDispatcher = ioDispatcher,
            )
            if (wasBusy) {
                superRuntime.steer(prompt, images)
            } else {
                superRuntime.send(prompt, images)
            }
        }
        return true
    }

    fun stop() {
        superRuntime.abortSuper()
    }

    fun compact() {
        val current = state.value
        if (!current.thread.ready || current.thread.busy) return
        dismissError()
        launchAction(strings.compactionFailed) {
            superRuntime.compactSuper()
        }
    }

    fun retryMessage(messageId: Long) {
        val current = state.value
        if (current.thread.busy) return
        val targetIndex = current.messages.indexOfFirst { it.id == messageId }
        val sourceMessages = if (targetIndex >= 0) current.messages.take(targetIndex + 1) else current.messages
        val lastUser = sourceMessages.lastOrNull { it.role == AssistantRole.USER } ?: return
        val text = lastUser.text.trim()
        if (text.isEmpty()) return
        dismissError()
        launchAction(strings.requestFailed) {
            superRuntime.send(text)
        }
    }

    fun retryLastError() {
        val current = state.value
        if (current.thread.busy) {
            dismissError()
            return
        }
        val lastUser = current.messages.lastOrNull { it.role == AssistantRole.USER }
        if (lastUser == null || lastUser.text.isBlank()) {
            dismissError()
            launchAction(strings.requestFailed) {
                superRuntime.ensureStarted()
            }
            return
        }
        dismissError()
        launchAction(strings.requestFailed) {
            superRuntime.send(lastUser.text)
        }
    }

    fun dismissError() {
        draftState.update { it.copy(localError = null) }
        superRuntime.dismissError()
    }

    fun setProvider(providerId: String) {
        val thread = superRuntime.threadState.value
        if (providerId == thread.providerId) return
        val options = providers.models(providerId)
        val target = options.firstOrNull { it.id == thread.modelId } ?: options.firstOrNull() ?: return
        val thinking = target.thinkingLevels.resolveThinkingLevel(thread.thinkingLevel)
        launchAction(strings.switchModelFailed) {
            superRuntime.changeSuperModel(providerId, target.id, thinking)
        }
    }

    fun setModel(modelId: String) {
        val thread = superRuntime.threadState.value
        val options = providers.models(thread.providerId)
        val target = options.firstOrNull { it.id == modelId } ?: return
        val providerId = thread.providerId.ifBlank { target.providerId }
        val thinking = target.thinkingLevels.resolveThinkingLevel(thread.thinkingLevel)
        launchAction(strings.switchModelFailed) {
            superRuntime.changeSuperModel(providerId, target.id, thinking)
        }
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        val thread = superRuntime.threadState.value
        if (thread.thinkingLevel == level) return
        launchAction(strings.switchThinkingFailed) {
            superRuntime.changeSuperThinking(level)
        }
    }

    fun answerSuperQuestion(requestId: String, answers: List<QuestionAnswer>) {
        launchAction(strings.requestFailed) {
            superRuntime.answerSuperQuestion(requestId, answers)
        }
    }

    fun answerWorkerQuestion(workerId: String, requestId: String, answers: List<QuestionAnswer>) {
        launchAction(strings.requestFailed) {
            superRuntime.answerWorkerQuestion(workerId, requestId, answers)
        }
    }

    fun dismissWorkerQuestion(workerId: String, requestId: String) {
        launchAction(strings.requestFailed) {
            superRuntime.dismissWorkerQuestion(workerId, requestId)
        }
    }

    fun archiveAndReset() {
        draftState.update {
            it.copy(
                input = "",
                quote = null,
                attachments = emptyList(),
                localError = null,
            )
        }
        expandedStepIds.value = emptySet()
        launchAction(strings.requestFailed) {
            superRuntime.archiveAndResetSuper()
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
        resolveSuperAttachment(raw, superRuntime, attachmentStore, superDir, routerWorkspaceDir, ioDispatcher)

    private fun buildUiState(
        thread: SuperThreadState,
        workers: List<WorkerRunRecord>,
        blueprints: List<BotBlueprint>,
        draft: SuperComposerDraftState,
        expanded: Set<Long>,
    ): SuperChatUiState {
        val displayMessages = applyExpandedStepOverrides(thread.messages, expanded)
        val mergedCards = mergePresentedCards(
            messages = displayMessages,
            workers = workers,
            lookupById = superRuntime::cardById,
        )
        val activeWorkers = workers.filter { it.status.isActive }.sortedByDescending { it.updatedAt }
        val approvals = workers.mapNotNull { worker ->
            worker.pendingQuestion?.let { question -> worker to question }
        }
        val models = if (thread.providerId.isNotBlank()) {
            providers.models(thread.providerId)
        } else {
            emptyList()
        }
        val thinkingLevels = models.firstOrNull { it.id == thread.modelId }?.thinkingLevels
            ?.takeIf { it.isNotEmpty() }
            ?: thread.thinkingLevels
        return SuperChatUiState(
            thread = thread,
            messages = displayMessages,
            workers = workers,
            activeWorkers = activeWorkers,
            pendingWorkerApprovals = approvals,
            enabledTeamCount = blueprints.count { it.enabledInSuper },
            presentedCards = mergedCards,
            draft = draft,
            models = models,
            thinkingLevels = thinkingLevels,
            error = draft.localError ?: thread.error,
        )
    }
}

internal fun superQuoteExcerpt(text: String): String {
    val plain = markdownToPlainText(text.take(4000))
        .lineSequence()
        .map { it.replace(Regex("[ \\t]+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .take(3)
        .joinToString("\n")
    return if (plain.length <= 240) plain else plain.take(240).trimEnd() + "…"
}

internal fun launchSuperAttachmentImport(
    uri: Uri,
    targetDir: File,
    attachmentStore: AttachmentStore,
    strings: ConversationStrings,
    draftState: MutableStateFlow<SuperComposerDraftState>,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {
    val current = draftState.value
    if (current.attachments.size >= MAX_ATTACHMENTS) {
        draftState.update { it.copy(localError = strings.maxAttachments) }
        return
    }
    scope.launch {
        draftState.update { it.copy(importingAttachment = true, localError = null) }
        try {
            targetDir.mkdirs()
            val result = withContext(ioDispatcher) {
                attachmentStore.import(uri, targetDir)
            }
            when (result) {
                is AttachmentImport.Imported -> draftState.update { latest ->
                    if (latest.attachments.size >= MAX_ATTACHMENTS) {
                        latest.copy(localError = strings.maxAttachments)
                    } else {
                        latest.copy(attachments = latest.attachments + result.ref, localError = null)
                    }
                }

                AttachmentImport.TooLarge ->
                    draftState.update { it.copy(localError = strings.attachmentTooLarge) }

                AttachmentImport.InsufficientSpace ->
                    draftState.update { it.copy(localError = strings.insufficientSpace) }

                AttachmentImport.UnsupportedImage ->
                    draftState.update { it.copy(localError = strings.unsupportedImage) }

                AttachmentImport.Failed ->
                    draftState.update { it.copy(localError = strings.attachmentFailed) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            draftState.update { it.copy(localError = strings.attachmentFailed) }
        } finally {
            draftState.update { it.copy(importingAttachment = false) }
        }
    }
}

internal fun buildSuperPrompt(
    quote: QuotedMessage?,
    attachments: List<AttachmentRef>,
    root: File,
    body: String,
    remote: Boolean = false,
): String = buildString {
    if (quote != null) {
        append(quote.text.lineSequence().joinToString("\n") { "> $it" })
        if (body.isNotEmpty() || attachments.isNotEmpty()) append("\n\n")
    }
    if (attachments.isNotEmpty()) {
        if (!remote) {
            root.mkdirs()
            append(encodeAttachmentBlock(attachments, root))
            if (body.isNotEmpty()) append("\n\n")
        } else {
            val nonImages = attachments.filter { it.kind != AttachmentKind.IMAGE }
            if (nonImages.isNotEmpty()) {
                append("<attachments>\n")
                nonImages.forEach { ref ->
                    val file = attachmentFile(ref, root)
                    val inlineText = if (file.isFile && file.canRead() && file.length() in 1L..MAX_REMOTE_INLINE_BYTES) {
                        runCatching {
                            val bytes = file.readBytes()
                            if (bytes.any { b -> b == 0.toByte() }) null else bytes.toString(Charsets.UTF_8)
                        }.getOrNull()
                    } else {
                        null
                    }
                    if (inlineText != null) {
                        append("<attached_file name=\"${ref.name}\">\n")
                        append(inlineText)
                        append("\n</attached_file>\n")
                    } else {
                        append("""<attached_file name="${ref.name}" note="File on controller device (${ref.size} bytes) was not transferred" />""")
                        append('\n')
                    }
                }
                append("</attachments>")
                if (body.isNotEmpty()) append("\n\n")
            }
        }
    }
    append(body)
}

internal suspend fun normalizeSuperImages(
    refs: List<AttachmentRef>,
    root: File,
    attachmentStore: AttachmentStore,
    normalizeImage: suspend (File, String) -> NormalizedImage?,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): List<ImagePart> {
    if (refs.isEmpty()) return emptyList()
    return withContext(ioDispatcher) {
        refs.mapNotNull { ref ->
            val source = attachmentStore.materialize(ref, root) ?: return@mapNotNull null
            val normalized = runCatching { normalizeImage(source, ref.mime) }.getOrNull() ?: return@mapNotNull null
            ImagePart(normalized.data, normalized.mimeType, source.absolutePath)
        }
    }
}

private const val MAX_REMOTE_INLINE_BYTES = 32_768L

internal fun applyExpandedStepOverrides(
    messages: List<AssistantMessage>,
    expandedStepIds: Set<Long>,
): List<AssistantMessage> = messages.map { message ->
    if (message.steps.isEmpty()) {
        message
    } else {
        val updatedSteps = message.steps.map { step ->
            when (step) {
                is AssistantStep.Reasoning ->
                    if (step.id in expandedStepIds) step.copy(expanded = !step.expanded) else step

                is AssistantStep.Tool ->
                    if (step.id in expandedStepIds) {
                        AssistantStep.Tool(step.trace.copy(expanded = !step.trace.expanded))
                    } else {
                        step
                    }

                else -> step
            }
        }
        message.copy(steps = updatedSteps)
    }
}

internal fun mergePresentedCards(
    messages: List<AssistantMessage>,
    workers: List<WorkerRunRecord>,
    lookupById: (String) -> WorkerCardRef? = { null },
): Map<String, PresentedCardRef> {
    val map = LinkedHashMap(presentedCardLookup(messages))
    workers.forEach { worker ->
        worker.cards.forEach { card ->
            workerCardToPresentedCardRef(card)?.let { ref ->
                map.putIfAbsent(card.cardId, ref)
            }
        }
    }
    messages.forEach { msg ->
        msg.steps.forEach { step ->
            if (step is AssistantStep.Tool && step.trace.name == "present") {
                parsePresentedItems(step.trace).orEmpty()
                    .filter { it.type == PresentedItemType.CARD && !it.id.isNullOrBlank() }
                    .forEach { item ->
                        val cardId = item.id.orEmpty()
                        if (cardId !in map) {
                            lookupById(cardId)?.let(::workerCardToPresentedCardRef)?.let { ref ->
                                map[cardId] = ref
                            }
                        }
                    }
            }
        }
    }
    return map
}

internal fun workerCardToPresentedCardRef(card: WorkerCardRef): PresentedCardRef? {
    val detailsObj = card.detailsJson.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { CARD_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }
    val descriptor = detailsObj?.get(Cards.KEY) as? JsonObject
    if (descriptor != null) {
        val customType = descriptor.stringOrNull("customType")
        val payload = descriptor["payload"] as? JsonObject
        if (customType != null && payload != null) {
            return PresentedCardRef.Payload(
                customType = customType,
                payload = payload,
                title = descriptor.stringOrNull("title") ?: card.title,
            )
        }
    }
    val rawArgs = card.argsJson.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { CARD_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }
    val displayArgs = parseDisplayArguments(rawArgs)
    val trace = ToolTrace(
        id = card.cardId.hashCode().toLong(),
        name = card.toolName,
        arguments = displayArgs,
        status = ToolActivityStatus.DONE,
        output = card.outputText.takeIf { it.isNotBlank() },
        toolCallId = card.cardId,
        startedAt = card.createdAt,
        rawArguments = rawArgs,
        resultPayload = ToolResultPayload.Text(card.outputText),
        resultDetails = detailsObj,
    )
    return PresentedCardRef.Tool(trace)
}

internal fun parseDisplayArguments(args: JsonObject?): Map<String, String> =
    args?.entries?.associate { (key, value) ->
        val clean = if (value is JsonPrimitive && value.isString) value.content else value.toString()
        key to clean
    }.orEmpty()

internal suspend fun resolveSuperAttachment(
    raw: String,
    superRuntime: SuperRuntime,
    attachmentStore: AttachmentStore,
    superDir: File,
    routerWorkspaceDir: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Pair<AttachmentRef, File>? = withContext(ioDispatcher) {
    val artifactFile = runCatching { superRuntime.resolveArtifactFile(raw) }.getOrNull()
    if (artifactFile != null && artifactFile.isFile) {
        val resolved = attachmentStore.resolve(artifactFile.absolutePath, superDir)
            ?: superArtifactAttachmentRef(artifactFile)
        return@withContext resolved to artifactFile
    }
    val direct = attachmentStore.resolve(raw, routerWorkspaceDir)
        ?: attachmentStore.resolve(raw, superDir)
    if (direct != null) {
        val root = if (raw.startsWith(routerWorkspaceDir.absolutePath)) routerWorkspaceDir else superDir
        return@withContext direct to attachmentStore.file(direct, root)
    }
    null
}

internal fun superArtifactAttachmentRef(file: File): AttachmentRef {
    val mime = guessMime(file.name)
    return AttachmentRef(
        id = file.absolutePath,
        name = file.name,
        mime = mime,
        size = file.length(),
        path = file.absolutePath,
        kind = attachmentKind(mime, file.name),
    )
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private val CARD_JSON = Json { ignoreUnknownKeys = true }
