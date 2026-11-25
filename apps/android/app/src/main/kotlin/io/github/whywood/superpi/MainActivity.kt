package io.github.whywood.superpi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.whywood.superpi.assistant.AssistantOverlayActivity
import io.github.whywood.superpi.data.LanguagePreferences
import io.github.whywood.superpi.data.OnboardingPreferences
import io.github.whywood.superpi.data.PluginPackageIntake
import io.github.whywood.superpi.data.MAX_SHARE_STREAMS
import io.github.whywood.superpi.data.SharePayload
import io.github.whywood.superpi.data.parseSharePayload
import io.github.whywood.superpi.deeplink.DeepLink
import io.github.whywood.superpi.plugin.api.PluginCapability
import io.github.whywood.superpi.plugin.host.interaction.PluginInteractionHost
import io.github.whywood.superpi.plugin.host.interaction.PluginInteractionOverlay
import io.github.whywood.superpi.plugin.manager.PluginSharedStream
import io.github.whywood.superpi.service.AgentService
import io.github.whywood.superpi.service.AppVisibility
import io.github.whywood.superpi.service.NotificationCenter
import io.github.whywood.superpi.theme.HostTheme
import io.github.whywood.superpi.ui.common.ProvideHostFiles
import io.github.whywood.superpi.ui.miniapp.MiniappLaunchEvents
import io.github.whywood.superpi.ui.common.chat.ProvidePluginManager
import io.github.whywood.superpi.ui.navigation.AppDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as SuperPIApplication).graph }
    private val pendingSessionId = mutableStateOf<String?>(null)
    private val pendingDeepDestination = mutableStateOf<AppDestination?>(null)
    private val pendingShare = mutableStateOf<SharePayload?>(null)
    private val pendingPluginImportUri = mutableStateOf<String?>(null)
    private var lastHandledIntentKey: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguagePreferences.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            lastHandledIntentKey = intentFingerprint(intent)
            handleIntent(intent)
        } else {
            restoreIntake(savedInstanceState)
            val incomingKey = intentFingerprint(intent)
            if (incomingKey != null && incomingKey != lastHandledIntentKey) {
                lastHandledIntentKey = incomingKey
                handleIntent(intent)
            }
        }
        startAgentService()
        graph.processRuntime.startAsync()
        requestNotificationPermission()
        setContent {
            HostTheme(controller = graph.themeController) {
                ProvideHostFiles(graph.hostFiles) {
                    ProvidePluginManager(graph.pluginManager) {
                        Box(Modifier.fillMaxSize()) {
                            SuperPIApp(
                                graph = graph,
                                openSessionId = pendingSessionId.value,
                                onOpenSessionConsumed = { pendingSessionId.value = null },
                                pendingDeepDestination = pendingDeepDestination.value,
                                onDeepDestinationConsumed = { pendingDeepDestination.value = null },
                                pendingShare = pendingShare.value,
                                onShareConsumed = { pendingShare.value = null },
                                pendingPluginImportUri = pendingPluginImportUri.value,
                                onPluginImportConsumed = { pendingPluginImportUri.value = null },
                                onActiveSessionChanged = { graph.activeSessionId.value = it },
                                onFontStyleChange = graph.fontPreferences::save,
                            )
                            PluginInteractionOverlay()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lastHandledIntentKey = intentFingerprint(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSessionId.value?.let { outState.putString(EXTRA_PENDING_SESSION_ID, it) }
        pendingDeepDestination.value?.routeKey?.let { outState.putString(EXTRA_PENDING_DEEP_ROUTE, it) }
        lastHandledIntentKey?.let { outState.putString(EXTRA_LAST_HANDLED_INTENT_KEY, it) }
        pendingShare.value?.let { payload ->
            outState.putStringArrayList(EXTRA_SHARE_STREAMS, ArrayList(payload.streams))
            outState.putString(EXTRA_SHARE_TEXT, payload.text)
            outState.putString(EXTRA_SHARE_TITLE, payload.title)
        }
        pendingPluginImportUri.value?.let { outState.putString(EXTRA_PLUGIN_IMPORT_URI, it) }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.activityStarted()
        AppVisibility.taskId = taskId
        PluginInteractionHost.attachActivity(this)
        graph.notificationCenter.clearQuestionNotifications()
        graph.processRuntime.onForeground()
        graph.updateManager.maybeAutoCheck()
    }

    override fun onStop() {
        AppVisibility.activityStopped()
        PluginInteractionHost.detachActivity(this)
        graph.notificationCenter.refreshQuestionNotifications()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW) {
            when (val link = DeepLink.parse(intent.dataString.orEmpty())) {
                is DeepLink.Session -> pendingSessionId.value = link.sessionId
                is DeepLink.Page -> {
                    pendingDeepDestination.value = link.destination
                    val destination = link.destination
                    if (destination is AppDestination.MiniappDetail) {
                        MiniappLaunchEvents.record(destination.id, miniappOpenParameters(intent.dataString.orEmpty()))
                    }
                }
                is DeepLink.PluginPath -> dispatchPluginDeepLink(link)

                null -> {
                    val data = intent.data
                    if (data != null && isExternalViewIntakeScheme(data.scheme)) {
                        if (isPluginPackage(data, intent.type)) {
                            pendingPluginImportUri.value = data.toString()
                        } else {
                            val payload = parseSharePayload(
                                action = Intent.ACTION_VIEW,
                                type = intent.type,
                                streamUris = listOf(data.toString()),
                            )
                            pendingShare.value = payload
                            dispatchPluginShare(payload)
                        }
                    }
                }
            }
            return
        }
        intent.getStringExtra(NotificationCenter.EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }?.let {
            pendingSessionId.value = it
        }
        if (intent.action == Intent.ACTION_ASSIST) {
            startActivity(
                Intent(this, AssistantOverlayActivity::class.java).setAction(Intent.ACTION_ASSIST),
            )
            return
        }
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val streams = if (action == Intent.ACTION_SEND) {
            listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
            )
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                .orEmpty()
                .map(Uri::toString)
        }
        val clipStreams = intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.toString() }
        }.orEmpty()
        if (streams.size + clipStreams.size > MAX_SHARE_STREAMS) {
            Toast.makeText(this, R.string.share_error_too_many, Toast.LENGTH_SHORT).show()
        }
        val intakeUris = streams + clipStreams
        if (intakeUris.size == 1) {
            val single = Uri.parse(intakeUris.first())
            if (isPluginPackage(single, intent.type)) {
                pendingPluginImportUri.value = single.toString()
                return
            }
        }
        val payload = parseSharePayload(
            action = action,
            type = intent.type,
            streamUris = streams,
            clipUris = clipStreams,
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            title = intent.getStringExtra(Intent.EXTRA_TITLE),
        )
        if (payload.isEmpty) {
            Toast.makeText(this, R.string.share_error_no_content, Toast.LENGTH_SHORT).show()
        } else {
            pendingShare.value = payload
            dispatchPluginShare(payload)
        }
    }

    private fun miniappOpenParameters(raw: String): kotlinx.serialization.json.JsonObject {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val note = uri?.getQueryParameter("note")
        return kotlinx.serialization.json.buildJsonObject {
            if (!note.isNullOrBlank()) {
                put("note", kotlinx.serialization.json.JsonPrimitive(note))
            }
        }
    }

    private fun dispatchPluginDeepLink(link: DeepLink.PluginPath) {
        graph.appScope.launch {
            awaitPluginManagerLoaded()
            graph.pluginManager.onDeepLinkReceived(link.pluginId, link.path, link.arguments)
        }
    }

    private fun dispatchPluginShare(payload: SharePayload) {
        graph.appScope.launch {
            awaitPluginManagerLoaded()
            val manager = graph.pluginManager
            val shareCapable = manager.state.value.plugins.any { plugin ->
                plugin.enabled && PluginCapability.SHARE.wire in plugin.capabilities
            }
            if (!shareCapable) return@launch
            if (payload.streams.isEmpty()) {
                if (payload.text.isNotBlank() || payload.title.isNotBlank()) {
                    manager.onShareReceived(payload.text, payload.title, emptyList())
                }
                return@launch
            }
            val staged = graph.shareStaging.stage(payload.streams)
            val streams = staged.files.map { file ->
                PluginSharedStream(
                    path = file.file.absolutePath,
                    mimeType = file.mime,
                    name = file.name,
                )
            }
            if (streams.isNotEmpty() || payload.text.isNotBlank() || payload.title.isNotBlank()) {
                manager.onShareReceived(payload.text, payload.title, streams)
            }
        }
    }

    private suspend fun awaitPluginManagerLoaded() {
        val state = graph.pluginManager.state
        withTimeoutOrNull(5_000) {
            val current = state.first { it.plugins.isNotEmpty() || it.loading }
            if (current.loading) state.first { !it.loading }
        }
    }

    private fun restoreIntake(savedInstanceState: Bundle) {
        lastHandledIntentKey = savedInstanceState.getString(EXTRA_LAST_HANDLED_INTENT_KEY)
        savedInstanceState.getString(EXTRA_PENDING_SESSION_ID)?.let { pendingSessionId.value = it }
        savedInstanceState.getString(EXTRA_PENDING_DEEP_ROUTE)
            ?.let(AppDestination::fromRouteKey)
            ?.let { pendingDeepDestination.value = it }
        savedInstanceState.getString(EXTRA_PLUGIN_IMPORT_URI)?.let { pendingPluginImportUri.value = it }
        val streams = savedInstanceState.getStringArrayList(EXTRA_SHARE_STREAMS).orEmpty()
        val text = savedInstanceState.getString(EXTRA_SHARE_TEXT).orEmpty()
        val title = savedInstanceState.getString(EXTRA_SHARE_TITLE).orEmpty()
        val payload = parseSharePayload(
            action = Intent.ACTION_SEND_MULTIPLE,
            type = null,
            streamUris = streams,
            text = text,
            title = title,
        )
        if (!payload.isEmpty) pendingShare.value = payload
    }

    private fun intentFingerprint(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_VIEW && !intent.dataString.isNullOrBlank()) {
            return "VIEW:${intent.dataString}|${intent.type.orEmpty()}"
        }
        intent.getStringExtra(NotificationCenter.EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }?.let {
            return "SESSION:$it"
        }
        if (intent.action == Intent.ACTION_ASSIST) return "ASSIST"
        val action = intent.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            val streams = if (action == Intent.ACTION_SEND) {
                listOfNotNull(
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
                )
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()
                    .map(Uri::toString)
            }
            val clipStreams = intent.clipData?.let { clip ->
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.toString() }
            }.orEmpty()
            return buildString {
                append(action)
                append('|').append(intent.type.orEmpty())
                append('|').append(intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty())
                append('|').append(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                append('|').append((streams + clipStreams).joinToString(","))
            }
        }
        return null
    }

    private fun isPluginPackage(uri: Uri, mimeType: String?): Boolean =
        PluginPackageIntake.isPluginPackage(resolveDisplayName(uri), mimeType) ||
            PluginPackageIntake.isPluginPackage(uri.encodedPath ?: uri.toString(), null)

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun startAgentService() {
        AgentService.start(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val onboardingPrefs = OnboardingPreferences(this)
        if (!onboardingPrefs.isCompleted()) return
        if (onboardingPrefs.hasRequestedNotificationPermission()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        onboardingPrefs.markNotificationPermissionRequested()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    private companion object {
        const val EXTRA_PENDING_SESSION_ID = "io.github.whywood.superpi.extra.PENDING_SESSION_ID"
        const val EXTRA_PENDING_DEEP_ROUTE = "io.github.whywood.superpi.extra.PENDING_DEEP_ROUTE"
        const val EXTRA_LAST_HANDLED_INTENT_KEY = "io.github.whywood.superpi.extra.LAST_HANDLED_INTENT_KEY"
        const val EXTRA_SHARE_STREAMS = "io.github.whywood.superpi.extra.SHARE_STREAMS"
        const val EXTRA_SHARE_TEXT = "io.github.whywood.superpi.extra.SHARE_TEXT"
        const val EXTRA_SHARE_TITLE = "io.github.whywood.superpi.extra.SHARE_TITLE"
        const val EXTRA_PLUGIN_IMPORT_URI = "io.github.whywood.superpi.extra.PLUGIN_IMPORT_URI"
    }
}

internal fun isExternalViewIntakeScheme(scheme: String?): Boolean =
    !scheme.equals("superpi", ignoreCase = true)
