package io.github.whywood.superpi.ui.navigation.routes

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.data.relay.RelayMemorySyncErrorKind
import io.github.whywood.superpi.design.DdNoticeTone
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryExportLabels
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MemoryRecord
import io.github.whywood.superpi.runtime.MemorySettings
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.MemoryTagCount
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.memory.DreamOutcome
import io.github.whywood.superpi.runtime.memory.MemoryAccountSwitchMode
import io.github.whywood.superpi.ui.chat.ChatViewModel
import io.github.whywood.superpi.ui.common.shareChooser
import io.github.whywood.superpi.ui.memory.MemoryAccountSwitchDialog
import io.github.whywood.superpi.ui.memory.MemoryEditorPayload
import io.github.whywood.superpi.ui.memory.MemoryScreen
import io.github.whywood.superpi.ui.memory.MemoryScreenState
import io.github.whywood.superpi.ui.memory.MemoryStatusNotice
import io.github.whywood.superpi.ui.navigation.AppDestination
import io.github.whywood.superpi.ui.navigation.AppNavigator
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SEARCH_DEBOUNCE_MS = 180L
private const val UNDO_WINDOW_MS = 5_000L
private const val PAGE_SIZE = 100

@Composable
internal fun MemoryRoute(
    dependencies: AppDependencies,
    chatViewModel: ChatViewModel,
    navigator: AppNavigator,
    configuredProviderIds: Set<String>,
    defaultModelConfig: ModelConfig,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    val storeRevision by dependencies.memory.changes.collectAsStateWithLifecycle()
    val memorySettings by dependencies.memoryPreferences.settings.collectAsStateWithLifecycle(
        initialValue = MemorySettings(),
    )
    val syncState by dependencies.memorySync.state.collectAsStateWithLifecycle()
    val manualDream by dependencies.memoryDreamer.manualState.collectAsStateWithLifecycle()
    val organizing = manualDream.running

    var query by rememberSaveable { mutableStateOf("") }
    var selectedKindWire by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedKind = selectedKindWire?.let { MemoryKind.fromWire(it) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleLimit by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var records by remember { mutableStateOf<List<MemoryRecord>>(emptyList()) }
    var tags by remember { mutableStateOf<List<MemoryTagCount>>(emptyList()) }
    var statusNotice by remember { mutableStateOf<MemoryStatusNotice?>(null) }
    var undoDeleteId by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableLongStateOf(0L) }
    var lastHandledOutcomeSeq by rememberSaveable { mutableLongStateOf(manualDream.outcomeSeq) }
    var dismissedSwitchKey by rememberSaveable { mutableStateOf<String?>(null) }
    var resolvingAccountSwitch by remember { mutableStateOf(false) }

    val configuredProviders = remember(configuredProviderIds, dependencies.providerCatalog.summaries) {
        dependencies.providerCatalog.summaries.filter { it.id in configuredProviderIds }
    }
    val modelsByProvider: Map<String, List<ModelOption>> = remember(configuredProviders) {
        configuredProviders.associate { summary: ProviderSummary ->
            summary.id to dependencies.providerCatalog.models(summary.id)
        }
    }

    LaunchedEffect(query, selectedKind, selectedTag, visibleLimit, storeRevision, reloadNonce) {
        val currentQuery = query.trim()
        if (currentQuery.isNotEmpty()) {
            delay(SEARCH_DEBOUNCE_MS)
        }
        val filter = MemoryFilter(
            kinds = selectedKind?.let { setOf(it) } ?: emptySet(),
            tag = selectedTag,
            includeHistory = false,
            limit = visibleLimit + 1,
        )
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val items = if (currentQuery.isNotEmpty()) {
                    dependencies.memory.search(listOf(currentQuery), filter).map { it.record }
                } else {
                    dependencies.memory.list(filter)
                }
                val activeTags = dependencies.memory.listTags()
                items to activeTags
            }.getOrNull()
        }
        if (loaded != null) {
            val rawItems = loaded.first
            hasMore = rawItems.size > visibleLimit
            records = rawItems.take(visibleLimit)
            tags = loaded.second
            val activeTag = selectedTag
            if (activeTag != null && loaded.second.none { it.tag == activeTag } && rawItems.isEmpty()) {
                selectedTag = null
            }
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        dependencies.memorySync.requestSyncIfConnected()
    }

    LaunchedEffect(syncState.pendingAccountSwitch) {
        if (syncState.pendingAccountSwitch == null) {
            dismissedSwitchKey = null
        }
    }

    LaunchedEffect(undoDeleteId) {
        if (undoDeleteId == null) return@LaunchedEffect
        delay(UNDO_WINDOW_MS)
        undoDeleteId = null
    }

    LaunchedEffect(statusNotice, organizing) {
        if (statusNotice == null || organizing) return@LaunchedEffect
        delay(4_000L)
        statusNotice = null
    }

    LaunchedEffect(manualDream.running, manualDream.outcomeSeq) {
        if (manualDream.running) {
            undoDeleteId = null
            statusNotice = MemoryStatusNotice(resources.getString(R.string.memory_organizing), DdNoticeTone.INFO)
            return@LaunchedEffect
        }
        if (manualDream.outcomeSeq != lastHandledOutcomeSeq) {
            lastHandledOutcomeSeq = manualDream.outcomeSeq
            val outcome = manualDream.lastOutcome ?: return@LaunchedEffect
            statusNotice = when (outcome) {
                is DreamOutcome.Done -> {
                    val s = outcome.summary
                    val total = s.promotedCount + s.supersededCount + s.expiredCount + s.mergedEntities
                    if (total == 0) {
                        MemoryStatusNotice(resources.getString(R.string.memory_organize_none), DdNoticeTone.INFO)
                    } else {
                        MemoryStatusNotice(
                            resources.getString(
                                R.string.memory_organize_done,
                                s.promotedCount,
                                s.supersededCount + s.mergedEntities,
                                s.expiredCount,
                            ),
                            DdNoticeTone.SUCCESS,
                        )
                    }
                }
                is DreamOutcome.Skipped -> {
                    val text = when (outcome.reason) {
                        "paused" -> resources.getString(R.string.memory_organize_paused)
                        "no_model" -> resources.getString(R.string.memory_organize_no_model)
                        else -> resources.getString(R.string.memory_organize_none)
                    }
                    MemoryStatusNotice(text, DdNoticeTone.INFO)
                }
                is DreamOutcome.Failed -> {
                    when (outcome.message) {
                        "sync_offline" -> MemoryStatusNotice(
                            resources.getString(R.string.memory_organize_offline),
                            DdNoticeTone.DANGER,
                        )
                        "sync_unauthorized" -> MemoryStatusNotice(
                            resources.getString(R.string.memory_organize_unauthorized),
                            DdNoticeTone.DANGER,
                        )
                        "sync_rejected" -> MemoryStatusNotice(
                            resources.getString(R.string.memory_organize_rejected),
                            DdNoticeTone.DANGER,
                        )
                        "account_switch_pending" -> {
                            dismissedSwitchKey = null
                            null
                        }
                        else -> MemoryStatusNotice(
                            resources.getString(R.string.memory_organize_failed),
                            DdNoticeTone.DANGER,
                        )
                    }
                }
                DreamOutcome.Busy ->
                    MemoryStatusNotice(resources.getString(R.string.memory_organizing), DdNoticeTone.INFO)
            }
            reloadNonce += 1L
        }
    }

    fun triggerOrganize() {
        if (organizing) return
        undoDeleteId = null
        dependencies.memoryDreamer.launchManual()
    }

    fun exportMarkdown() {
        scope.launch {
            val ok = runCatching {
                val labels = MemoryExportLabels(
                    title = resources.getString(R.string.memory_export_header),
                    exportedOn = resources.getString(R.string.memory_export_date),
                    sectionIdentity = resources.getString(R.string.memory_cat_identity),
                    sectionActive = resources.getString(R.string.memory_cat_active),
                    sectionHabit = resources.getString(R.string.memory_cat_habit),
                    sectionEpisodic = resources.getString(R.string.memory_cat_episodic),
                    sectionEntities = resources.getString(R.string.memory_export_entities),
                    sectionHistory = resources.getString(R.string.memory_export_history),
                    pinnedPrefix = "${resources.getString(R.string.memory_export_pinned)} ",
                    inferredSuffix = " (${resources.getString(R.string.memory_inferred_badge)})",
                    statusSuperseded = resources.getString(R.string.memory_export_superseded),
                    statusExpired = resources.getString(R.string.memory_export_expired),
                    statusArchived = resources.getString(R.string.memory_export_archived),
                    aliasesPrefix = resources.getString(R.string.memory_export_aliases),
                )
                val markdown = withContext(Dispatchers.IO) { dependencies.memory.exportMarkdown(labels) }
                val shareDir = File(context.cacheDir, "share-incoming").apply { mkdirs() }
                val outFile = File(shareDir, "memory-export-${LocalDate.now()}.md")
                withContext(Dispatchers.IO) { outFile.writeText(markdown, Charsets.UTF_8) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", outFile)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val title = resources.getString(R.string.memory_export_title)
                context.startActivity(shareChooser(context, sendIntent, title))
            }.isSuccess
            if (!ok) {
                statusNotice = MemoryStatusNotice(resources.getString(R.string.memory_export_failed), DdNoticeTone.DANGER)
            }
        }
    }

    fun saveEditor(payload: MemoryEditorPayload) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val existingId = payload.id
                if (existingId == null) {
                    dependencies.memory.save(
                        MemoryDraft(
                            kind = payload.kind,
                            content = payload.content,
                            tags = payload.tags,
                            confidence = MemoryConfidence.CONFIRMED,
                            importance = if (payload.pinned) 5 else 4,
                            pinned = payload.pinned,
                            sourceKind = MemorySourceKind.USER_MANUAL,
                        ),
                    )
                } else {
                    dependencies.memory.update(
                        id = existingId,
                        content = payload.content,
                        kind = payload.kind,
                        tags = payload.tags,
                        confidence = if (payload.confirmInferred) {
                            MemoryConfidence.CONFIRMED
                        } else {
                            payload.originalConfidence
                        },
                        pinned = payload.pinned,
                        validUntil = payload.validUntil,
                    )
                }
            }
            reloadNonce += 1L
        }
    }

    fun confirmRecord(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { dependencies.memory.confirm(id) }
            statusNotice = MemoryStatusNotice(resources.getString(R.string.memory_confirmed_toast), DdNoticeTone.SUCCESS)
            reloadNonce += 1L
        }
    }

    fun deleteRecord(id: String) {
        scope.launch {
            val removed = withContext(Dispatchers.IO) { dependencies.memory.softDelete(id) }
            if (removed) {
                undoDeleteId = id
                statusNotice = null
                reloadNonce += 1L
            }
        }
    }

    fun undoDelete() {
        val targetId = undoDeleteId ?: return
        undoDeleteId = null
        scope.launch {
            val restored = withContext(Dispatchers.IO) { dependencies.memory.restore(targetId) }
            if (restored != null) {
                statusNotice = MemoryStatusNotice(resources.getString(R.string.memory_restored), DdNoticeTone.SUCCESS)
                reloadNonce += 1L
            }
        }
    }

    fun forgetTag(tag: String) {
        scope.launch {
            val removed = withContext(Dispatchers.IO) { dependencies.memory.deleteByTag(tag) }
            if (selectedTag == tag) selectedTag = null
            statusNotice = MemoryStatusNotice(
                resources.getString(R.string.memory_forget_tag_done, tag, removed),
                DdNoticeTone.INFO,
            )
            reloadNonce += 1L
        }
    }

    fun purgeSession(sessionId: String) {
        scope.launch {
            val removed = withContext(Dispatchers.IO) { dependencies.memory.deleteBySession(sessionId) }
            statusNotice = MemoryStatusNotice(
                resources.getString(R.string.memory_purge_session_done, removed),
                DdNoticeTone.INFO,
            )
            reloadNonce += 1L
        }
    }

    val syncNotice = remember(syncState, resources) {
        when {
            syncState.pendingAccountSwitch != null -> MemoryStatusNotice(
                text = resources.getString(R.string.memory_account_switch_notice),
                tone = DdNoticeTone.INFO,
                actionLabel = resources.getString(R.string.memory_account_switch_choose),
            )
            syncState.errorKind != null && syncState.syncing -> MemoryStatusNotice(
                text = resources.getString(R.string.memory_sync_retrying),
                tone = DdNoticeTone.INFO,
            )
            syncState.errorKind == RelayMemorySyncErrorKind.Unauthorized -> MemoryStatusNotice(
                text = resources.getString(R.string.memory_sync_error_unauthorized),
                tone = DdNoticeTone.DANGER,
                actionLabel = resources.getString(R.string.memory_sync_reconnect),
            )
            syncState.errorKind == RelayMemorySyncErrorKind.Rejected -> MemoryStatusNotice(
                text = resources.getString(R.string.memory_sync_error_rejected),
                tone = DdNoticeTone.DANGER,
                actionLabel = resources.getString(R.string.memory_sync_retry),
            )
            syncState.errorKind == RelayMemorySyncErrorKind.Offline -> MemoryStatusNotice(
                text = resources.getString(R.string.memory_sync_error_offline),
                tone = DdNoticeTone.DANGER,
                actionLabel = resources.getString(R.string.memory_sync_retry),
            )
            else -> null
        }
    }

    MemoryScreen(
        state = MemoryScreenState(
            query = query,
            selectedKind = selectedKind,
            selectedTag = selectedTag,
            loading = loading,
            organizing = organizing,
            records = records,
            tags = tags,
            hasMore = hasMore,
            undoDeleteVisible = undoDeleteId != null,
            statusNotice = statusNotice,
            syncNotice = syncNotice,
            memorySettings = memorySettings,
            configuredProviders = configuredProviders,
            modelsByProvider = modelsByProvider,
            defaultProviderId = defaultModelConfig.providerId,
        ),
        onBack = onBack,
        onQueryChange = {
            query = it
            visibleLimit = PAGE_SIZE
        },
        onSelectKind = { kind ->
            selectedKindWire = if (selectedKind == kind) null else kind?.wire
            visibleLimit = PAGE_SIZE
        },
        onSelectTag = { tag ->
            selectedTag = if (selectedTag == tag) null else tag
            visibleLimit = PAGE_SIZE
        },
        onLoadMore = { visibleLimit += PAGE_SIZE },
        onClearFilters = {
            query = ""
            selectedKindWire = null
            selectedTag = null
            visibleLimit = PAGE_SIZE
        },
        onSaveEditor = ::saveEditor,
        onConfirmRecord = ::confirmRecord,
        onDeleteRecord = ::deleteRecord,
        onUndoDelete = ::undoDelete,
        onForgetTag = ::forgetTag,
        onOpenSourceSession = { sessionId ->
            scope.launch {
                val localExists = withContext(Dispatchers.IO) {
                    runCatching {
                        dependencies.host.allSessions().any { it.id == sessionId } ||
                            dependencies.host.archivedSessions().any { it.id == sessionId }
                    }.getOrDefault(false)
                }
                if (localExists) {
                    chatViewModel.openConversationById(sessionId)
                    navigator.reset(AppDestination.Chat)
                } else {
                    statusNotice = MemoryStatusNotice(
                        resources.getString(R.string.memory_source_other_device),
                        DdNoticeTone.INFO,
                    )
                }
            }
        },
        onPurgeSession = ::purgeSession,
        onOrganizeNow = ::triggerOrganize,
        onExportMarkdown = ::exportMarkdown,
        onSaveMemorySettings = { next ->
            scope.launch { dependencies.memoryPreferences.save(next) }
        },
        onSyncNoticeAction = {
            when {
                syncState.pendingAccountSwitch != null -> dismissedSwitchKey = null
                syncState.errorKind == RelayMemorySyncErrorKind.Unauthorized ->
                    navigator.push(AppDestination.Relay)
                syncState.errorKind != null && !syncState.syncing ->
                    scope.launch { dependencies.memorySync.syncNow() }
            }
        },
    )

    val switchPrompt = syncState.pendingAccountSwitch
    if (switchPrompt != null && dismissedSwitchKey != switchPrompt.accountKey) {
        fun handleSwitch(mode: MemoryAccountSwitchMode) {
            if (resolvingAccountSwitch) return
            resolvingAccountSwitch = true
            scope.launch {
                try {
                    dependencies.memorySync.resolveAccountSwitch(mode)
                    reloadNonce += 1L
                } finally {
                    resolvingAccountSwitch = false
                }
            }
        }
        MemoryAccountSwitchDialog(
            localMemoryCount = switchPrompt.localMemoryCount,
            busy = resolvingAccountSwitch,
            onMerge = { handleSwitch(MemoryAccountSwitchMode.MERGE_ALL) },
            onClear = { handleSwitch(MemoryAccountSwitchMode.REPLACE_WITH_REMOTE) },
            onDismiss = { dismissedSwitchKey = switchPrompt.accountKey },
        )
    }
}
