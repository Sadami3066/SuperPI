package io.github.whywood.superpi.ui.relay

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.relay.RelayAccount
import io.github.whywood.superpi.data.relay.RelayDevice
import io.github.whywood.superpi.data.relay.RelayError
import io.github.whywood.superpi.data.relay.RelayLiveState
import io.github.whywood.superpi.data.relay.RelayPairingLink
import io.github.whywood.superpi.data.relay.RelayUiState
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdNoticeBar
import io.github.whywood.superpi.design.DdNoticeTone
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdPrimaryButton
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSheet
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdTextField
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.ui.common.chat.CameraBarcodeAnalyzer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
internal fun RelayScreen(
    state: RelayUiState,
    liveState: RelayLiveState,
    allowRemote: Boolean,
    devices: List<RelayDevice>,
    defaultDeviceName: String,
    onBack: () -> Unit,
    onConnect: (link: String, loginSecret: String?, deviceName: String) -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit,
    onToggleAllowRemote: (Boolean) -> Unit,
    onGeneratePairCode: () -> Unit = {},
    onDismissPairCode: () -> Unit = {},
    onRevokeDevice: (String) -> Unit = {},
) {
    val colors = superPiColors()
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(defaultDeviceName) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var scanningQr by rememberSaveable { mutableStateOf(false) }
    var confirmRevokeDevice by remember { mutableStateOf<RelayDevice?>(null) }
    var pairCopied by remember(state.pairLink) { mutableStateOf(false) }
    val fingerprint = remember(link) { RelayPairingLink.parse(link)?.fingerprint }
    val connect = { onConnect(link.trim(), secret.trim().ifEmpty { null }, deviceName.trim()) }

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.relay_back),
                iconSize = 21.dp,
                tint = colors.ink,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.relay_title),
                modifier = Modifier.weight(1f),
                style = DdType.heading,
                color = colors.ink,
            )
        },
        modifier = Modifier.testTag("relay_screen"),
    ) {
        val account = state.account
        if (account == null) {
            ConnectForm(
                link = link,
                onLinkChange = { link = it },
                secret = secret,
                onSecretChange = { secret = it },
                deviceName = deviceName,
                onDeviceNameChange = { deviceName = it },
                fingerprint = fingerprint,
                signingIn = state.signingIn,
                error = state.error,
                onScanQr = { scanningQr = true },
                onConnect = connect,
                onDismissError = onDismissError,
            )
        } else {
            ConnectedAccount(
                account = account,
                liveState = liveState,
                allowRemote = allowRemote,
                devices = devices,
                generatingPairCode = state.generatingPairCode,
                revokingDeviceId = state.revokingDeviceId,
                error = state.error,
                onToggleAllowRemote = onToggleAllowRemote,
                onGeneratePairCode = onGeneratePairCode,
                onRequestRevokeDevice = { device -> confirmRevokeDevice = device },
                onDismissError = onDismissError,
                onSignOut = { confirmSignOut = true },
            )
        }
    }

    val activePairLink = state.pairLink
    if (activePairLink != null) {
        val qrBitmap = remember(activePairLink) {
            runCatching { QrCodeMatrix.encode(activePairLink)?.toBitmap(modulePx = 8)?.asImageBitmap() }.getOrNull()
        }
        DdDialog(
            onDismissRequest = onDismissPairCode,
            title = stringResource(R.string.relay_pair_dialog_title),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(DdShapes.Medium)
                                    .background(Color.White)
                                    .border(1.dp, colors.hairline, DdShapes.Medium)
                                    .padding(DdSpacing.Md),
                            ) {
                                Image(
                                    bitmap = qrBitmap,
                                    contentDescription = stringResource(R.string.relay_pair_dialog_title),
                                    modifier = Modifier
                                        .size(200.dp)
                                        .testTag("relay_pair_qr"),
                                    filterQuality = FilterQuality.None,
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.relay_pair_dialog_hint),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                    SelectionContainer {
                        Text(
                            text = activePairLink,
                            style = DdType.numeric,
                            color = colors.ink,
                        )
                    }
                }
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.relay_pair_close),
                    onClick = onDismissPairCode,
                    color = colors.inkSecondary,
                )
                DdTextAction(
                    text = stringResource(if (pairCopied) R.string.relay_pair_copied else R.string.relay_pair_copy),
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("pair-link", activePairLink))
                        pairCopied = true
                    },
                    color = colors.accent,
                )
            },
        )
    }

    val pendingRevoke = confirmRevokeDevice
    if (pendingRevoke != null) {
        DdDialog(
            onDismissRequest = { confirmRevokeDevice = null },
            title = stringResource(R.string.relay_device_remove_title),
            content = {
                Text(
                    text = stringResource(R.string.relay_device_remove_message, pendingRevoke.name),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.relay_cancel),
                    onClick = { confirmRevokeDevice = null },
                    color = colors.inkSecondary,
                )
                DdTextAction(
                    text = stringResource(R.string.relay_device_remove_confirm),
                    onClick = {
                        val id = pendingRevoke.deviceId
                        confirmRevokeDevice = null
                        onRevokeDevice(id)
                    },
                    color = colors.danger,
                )
            },
        )
    }

    if (confirmSignOut) {
        DdDialog(
            onDismissRequest = { confirmSignOut = false },
            title = stringResource(R.string.relay_sign_out_title),
            content = {
                Text(
                    text = stringResource(R.string.relay_sign_out_message),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.relay_cancel),
                    onClick = { confirmSignOut = false },
                    color = colors.inkSecondary,
                )
                DdTextAction(
                    text = stringResource(R.string.relay_sign_out_confirm),
                    onClick = {
                        confirmSignOut = false
                        onSignOut()
                    },
                    color = colors.danger,
                )
            },
        )
    }

    if (scanningQr) {
        RelayQrScanSheet(
            onDismiss = { scanningQr = false },
            onScanned = { scanned ->
                link = scanned
                scanningQr = false
            },
        )
    }
}

@Composable
private fun ColumnScope.ConnectForm(
    link: String,
    onLinkChange: (String) -> Unit,
    secret: String,
    onSecretChange: (String) -> Unit,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    fingerprint: String?,
    signingIn: Boolean,
    error: RelayError?,
    onScanQr: () -> Unit,
    onConnect: () -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = superPiColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DdSpacing.ScreenHorizontal),
    ) {
        Text(
            text = stringResource(R.string.relay_intro),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(DdSpacing.Xxl))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.relay_link_label),
                modifier = Modifier.weight(1f),
                style = DdType.label,
                color = colors.inkSecondary,
            )
            DdPressable(
                onClick = onScanQr,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = RoundedCornerShape(8.dp),
                contentDescription = stringResource(R.string.relay_scan_qr),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = DdSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
                ) {
                    DdIcon(
                        icon = DdIcons.Scan,
                        tint = colors.accent,
                        size = 16.dp,
                    )
                    Text(
                        text = stringResource(R.string.relay_scan_qr),
                        style = DdType.label,
                        color = colors.accent,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(DdSpacing.Xs))
        DdTextField(
            value = link,
            onValueChange = onLinkChange,
            placeholder = stringResource(R.string.relay_link_placeholder),
            singleLine = false,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            testTag = "relay_link",
        )
        if (fingerprint != null) {
            Spacer(Modifier.height(DdSpacing.Sm))
            Text(
                text = stringResource(R.string.relay_fingerprint_label) + " · " + formatFingerprint(fingerprint),
                style = DdType.numeric,
                color = colors.inkTertiary,
            )
        }
        Spacer(Modifier.height(DdSpacing.Lg))
        FieldLabel(stringResource(R.string.relay_secret_label))
        DdTextField(
            value = secret,
            onValueChange = onSecretChange,
            placeholder = stringResource(R.string.relay_secret_placeholder),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            visualTransformation = PasswordVisualTransformation(),
            testTag = "relay_secret",
        )
        Spacer(Modifier.height(DdSpacing.Lg))
        FieldLabel(stringResource(R.string.relay_device_name_label))
        DdTextField(
            value = deviceName,
            onValueChange = onDeviceNameChange,
            placeholder = stringResource(R.string.relay_device_name_placeholder),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onDone = { if (link.isNotBlank() && !signingIn) onConnect() },
            testTag = "relay_device_name",
        )
        if (error != null) {
            Spacer(Modifier.height(DdSpacing.Xl))
            DdNoticeBar(
                text = stringResource(error.textRes()),
                tone = DdNoticeTone.DANGER,
                actionLabel = if (error.retryable()) stringResource(R.string.relay_retry) else null,
                onAction = if (error.retryable()) {
                    {
                        onDismissError()
                        onConnect()
                    }
                } else {
                    null
                },
            )
        }
        Spacer(Modifier.height(DdSpacing.Xl))
        DdPrimaryButton(
            text = stringResource(if (signingIn) R.string.relay_connecting else R.string.relay_connect),
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = !signingIn && link.isNotBlank(),
        )
        Spacer(Modifier.height(DdSpacing.Xxxl))
    }
}

@Composable
private fun ColumnScope.ConnectedAccount(
    account: RelayAccount,
    liveState: RelayLiveState,
    allowRemote: Boolean,
    devices: List<RelayDevice>,
    generatingPairCode: Boolean,
    revokingDeviceId: String?,
    error: RelayError?,
    onToggleAllowRemote: (Boolean) -> Unit,
    onGeneratePairCode: () -> Unit,
    onRequestRevokeDevice: (RelayDevice) -> Unit,
    onDismissError: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = superPiColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DdSpacing.ScreenHorizontal),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.relay_connected),
                style = DdType.micro,
                color = colors.accent,
            )
        }
        Spacer(Modifier.height(DdSpacing.Md))
        DdGroupCard {
            DdListRow(
                title = stringResource(R.string.relay_account_device),
                value = account.deviceName,
            )
            DdListRow(
                title = stringResource(R.string.relay_account_server),
                value = account.origin,
            )
            DdListRow(
                title = stringResource(R.string.relay_account_since),
                value = formatDate(account.enrolledAt),
            )
        }
        Spacer(Modifier.height(DdSpacing.Xl))
        ShareCard(
            liveState = liveState,
            allowRemote = allowRemote,
            devices = devices,
            revokingDeviceId = revokingDeviceId,
            onToggleAllowRemote = onToggleAllowRemote,
            onRequestRevokeDevice = onRequestRevokeDevice,
        )
        Spacer(Modifier.height(DdSpacing.Lg))
        DdPrimaryButton(
            text = stringResource(if (generatingPairCode) R.string.relay_pair_generating else R.string.relay_pair_new),
            onClick = onGeneratePairCode,
            modifier = Modifier.fillMaxWidth(),
            enabled = !generatingPairCode,
        )
        if (error != null) {
            Spacer(Modifier.height(DdSpacing.Lg))
            DdNoticeBar(
                text = stringResource(error.textRes()),
                tone = DdNoticeTone.DANGER,
                actionLabel = stringResource(R.string.relay_pair_close),
                onAction = onDismissError,
            )
        }
        Spacer(Modifier.height(DdSpacing.Xxxl))
        DdTextAction(
            text = stringResource(R.string.relay_sign_out),
            onClick = onSignOut,
            color = colors.danger,
        )
    }
}

@Composable
private fun ShareCard(
    liveState: RelayLiveState,
    allowRemote: Boolean,
    devices: List<RelayDevice>,
    revokingDeviceId: String?,
    onToggleAllowRemote: (Boolean) -> Unit,
    onRequestRevokeDevice: (RelayDevice) -> Unit,
) {
    val colors = superPiColors()
    DdGroupCard(
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.relay_share_title),
                        style = DdType.label,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(DdSpacing.Xxs))
                    Text(
                        text = stringResource(
                            if (allowRemote) liveState.textRes() else R.string.relay_share_off,
                        ),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
                Spacer(Modifier.width(DdSpacing.Md))
                DdSwitch(
                    checked = allowRemote,
                    onCheckedChange = onToggleAllowRemote,
                    stateDescription = stringResource(
                        if (allowRemote) R.string.relay_share_on else R.string.relay_share_off,
                    ),
                )
            }
        },
    ) {
        if (devices.isEmpty()) {
            DdListRow(
                title = stringResource(R.string.relay_devices_empty),
                subtitle = stringResource(R.string.relay_devices_empty_hint),
            )
        } else {
            devices.forEach { device ->
                DdListRow(
                    title = device.name,
                    subtitle = if (device.online) {
                        device.bots.joinToString("、") { it.name }.ifEmpty {
                            stringResource(R.string.relay_devices_no_bots)
                        }
                    } else {
                        stringResource(R.string.relay_devices_offline)
                    },
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (device.online) colors.accent else colors.hairline),
                            )
                            DdTextAction(
                                text = stringResource(R.string.relay_device_remove),
                                onClick = { onRequestRevokeDevice(device) },
                                enabled = revokingDeviceId == null,
                                color = colors.danger,
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun RelayLiveState.textRes(): Int = when (this) {
    RelayLiveState.Disabled -> R.string.relay_share_off
    RelayLiveState.Connecting -> R.string.relay_live_connecting
    RelayLiveState.Online -> R.string.relay_live_online
    RelayLiveState.Offline -> R.string.relay_live_offline
    RelayLiveState.Unauthorized -> R.string.relay_live_unauthorized
}

@Composable
private fun FieldLabel(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = DdType.label,
            color = superPiColors().inkSecondary,
        )
        Spacer(Modifier.height(DdSpacing.Sm))
    }
}

private fun RelayError.textRes(): Int = when (this) {
    RelayError.InvalidLink -> R.string.relay_error_invalid_link
    RelayError.MissingCode -> R.string.relay_error_missing_code
    RelayError.MissingFingerprint -> R.string.relay_error_missing_fingerprint
    RelayError.FingerprintMismatch -> R.string.relay_error_fingerprint
    RelayError.Unreachable -> R.string.relay_error_unreachable
    RelayError.Unauthorized -> R.string.relay_error_unauthorized
    RelayError.BadResponse -> R.string.relay_error_bad_response
}

private fun RelayError.retryable(): Boolean = this == RelayError.Unreachable

private fun formatFingerprint(value: String): String =
    value.uppercase().chunked(4).joinToString(" ")

private fun formatDate(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(formatter)
}

@Composable
private fun RelayQrScanSheet(
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { granted = context.hasCameraPermission() }
    LaunchedEffect(Unit) {
        if (!context.hasCameraPermission()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    DdSheet(
        onDismiss = onDismiss,
        fullBleed = true,
        containerColor = Color.Black,
    ) {
        if (granted) {
            RelayQrScannerBody(onDismiss = onDismiss, onScanned = onScanned)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = DdSpacing.Xxxl),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.relay_scan_permission_hint),
                        style = DdType.body,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(DdSpacing.Lg))
                    DdPrimaryButton(
                        text = stringResource(R.string.camera_open_settings),
                        onClick = {
                            settingsLauncher.launch(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DdIconButton(
                    icon = DdIcons.Close,
                    onClick = onDismiss,
                    contentDescription = stringResource(R.string.camera_close),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(DdSpacing.Xs),
                    tint = Color.White,
                    iconSize = 20.dp,
                )
            }
        }
    }
}

@Composable
private fun RelayQrScannerBody(
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    val handled = remember { AtomicBoolean(false) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzer = remember(mainExecutor, onScanned) {
        CameraBarcodeAnalyzer { codes ->
            if (handled.get()) return@CameraBarcodeAnalyzer
            val matched = codes.firstOrNull { RelayPairingLink.parse(it.raw) != null }?.raw ?: return@CameraBarcodeAnalyzer
            if (handled.compareAndSet(false, true)) {
                mainExecutor.execute { onScanned(matched) }
            }
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(lifecycleOwner) {
        onDispose {
            surfaceRequest = null
            provider?.unbindAll()
            analysisExecutor.shutdown()
            analyzer.close()
        }
    }
    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = try {
            awaitRelayCameraProvider(context)
        } catch (_: Exception) {
            unavailable = true
            return@LaunchedEffect
        }
        provider = cameraProvider
        val lens = if (cameraProvider.hasLens(CameraSelector.LENS_FACING_BACK)) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        try {
            cameraProvider.unbindAll()
            surfaceRequest = null
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider { request -> surfaceRequest = request }
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()
                .apply { setAnalyzer(analysisExecutor, analyzer) }
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            unavailable = false
        } catch (_: Exception) {
            unavailable = true
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f),
    ) {
        val request = surfaceRequest
        when {
            unavailable -> Text(
                text = stringResource(R.string.camera_unavailable),
                style = DdType.body,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = DdSpacing.Xxxl),
            )

            request != null -> CameraXViewfinder(
                surfaceRequest = request,
                modifier = Modifier.fillMaxSize(),
                implementationMode = ImplementationMode.EMBEDDED,
                contentScale = ContentScale.Crop,
            )

            else -> DdSpinner(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }
        if (!unavailable && request != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(220.dp)
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp),
                    ),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(104.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = DdAlpha.Border)),
                    ),
                ),
        )
        Text(
            text = stringResource(R.string.relay_scan_hint),
            style = DdType.bodySmall,
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = DdSpacing.Xl, end = DdSpacing.Xl, bottom = DdSpacing.Lg),
        )
        DdIconButton(
            icon = DdIcons.Close,
            onClick = onDismiss,
            contentDescription = stringResource(R.string.camera_close),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(DdSpacing.Xs),
            tint = Color.White,
            iconSize = 20.dp,
        )
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private suspend fun awaitRelayCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (continuation.isActive) {
                    try {
                        continuation.resume(future.get())
                    } catch (exception: Exception) {
                        continuation.resumeWithException(exception)
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

private fun ProcessCameraProvider.hasLens(lensFacing: Int): Boolean = runCatching {
    hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build())
}.getOrDefault(false)
