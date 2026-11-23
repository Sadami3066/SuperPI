package io.github.whywood.superpi.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.core.AssistantEntry
import io.github.whywood.superpi.core.AssistantRole
import io.github.whywood.superpi.data.FloatingWindowPreferences
import io.github.whywood.superpi.data.FloatingWindowSettings
import io.github.whywood.superpi.data.OverlayEntryMode
import io.github.whywood.superpi.design.DdDropdown
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import kotlinx.coroutines.launch

@Composable
fun AndroidSettingsScreen(
    overlayEntryMode: OverlayEntryMode = OverlayEntryMode.VOICE,
    onOverlayEntryModeChange: (OverlayEntryMode) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { FloatingWindowPreferences(context) }
    val floatingSettings by preferences.settings.collectAsStateWithLifecycle(
        initialValue = FloatingWindowSettings(),
    )
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var roleHeld by remember { mutableStateOf(AssistantRole.isHeld(context)) }
    val powerUnavailableMessage = stringResource(R.string.assistant_settings_power_unavailable)
    LifecycleResumeEffect(Unit) {
        canDrawOverlays = Settings.canDrawOverlays(context)
        roleHeld = AssistantRole.isHeld(context)
        onPauseOrDispose {}
    }

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.settings_back),
                tint = colors.ink,
                iconSize = 21.dp,
            )
        },
        modifier = modifier.testTag("android_settings"),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.ScreenHorizontal),
        ) {
            Text(
                text = stringResource(R.string.settings_android),
                style = DdType.pageTitle,
                color = colors.ink,
            )
            Spacer(Modifier.height(DdSpacing.Xl))
            DdSectionLabel(
                stringResource(R.string.floating_settings_title),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdGroupCard {
                DdListRow(
                    title = stringResource(R.string.floating_settings_enable),
                    onClick = {
                        val enabled = !floatingSettings.enabled
                        scope.launch { preferences.setEnabled(enabled) }
                        if (enabled && !canDrawOverlays) openOverlayPermission(context)
                    },
                    trailing = {
                        DdSwitch(
                            checked = floatingSettings.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch { preferences.setEnabled(enabled) }
                                if (enabled && !canDrawOverlays) openOverlayPermission(context)
                            },
                            stateDescription = stringResource(
                                if (floatingSettings.enabled) R.string.settings_switch_on
                                else R.string.settings_switch_off,
                            ),
                        )
                    },
                    modifier = Modifier.testTag("floating_enable"),
                )
                if (floatingSettings.enabled) {
                    DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                    DdListRow(
                        title = stringResource(R.string.floating_settings_permission),
                        value = stringResource(
                            if (canDrawOverlays) {
                                R.string.floating_settings_permission_granted
                            } else {
                                R.string.floating_settings_permission_missing
                            },
                        ),
                        onClick = { openOverlayPermission(context) },
                        showChevron = true,
                        modifier = Modifier.testTag("floating_permission"),
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Xxl))
            DdSectionLabel(
                stringResource(R.string.assistant_settings_title),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdGroupCard {
                DdListRow(
                    title = stringResource(R.string.assistant_settings_role),
                    value = stringResource(
                        if (roleHeld) R.string.assistant_settings_role_held
                        else R.string.assistant_settings_role_missing,
                    ),
                    onClick = {
                        AssistantRole.defaultAppsIntent(context)?.let { intent ->
                            runCatching {
                                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    },
                    showChevron = true,
                    modifier = Modifier.testTag("assistant_role"),
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                DdListRow(
                    title = stringResource(R.string.assistant_settings_power),
                    onClick = {
                        val intent = AssistantEntry.powerSettingsIntent(context)
                        if (intent == null) {
                            Toast.makeText(context, powerUnavailableMessage, Toast.LENGTH_SHORT).show()
                        } else {
                            runCatching { context.startActivity(intent) }
                        }
                    },
                    showChevron = true,
                    modifier = Modifier.testTag("assistant_power_settings"),
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                OverlayEntryModeRow(
                    mode = overlayEntryMode,
                    onModeChange = onOverlayEntryModeChange,
                )
            }
            Spacer(Modifier.height(DdSpacing.Huge))
        }
    }
}

@Composable
private fun OverlayEntryModeRow(
    mode: OverlayEntryMode,
    onModeChange: (OverlayEntryMode) -> Unit,
) {
    val colors = superPiColors()
    var expanded by remember { mutableStateOf(false) }
    DdDropdown(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        anchor = {
            DdListRow(
                title = stringResource(R.string.usage_habits_entry_mode),
                onClick = { expanded = true },
                modifier = Modifier.testTag("usage_habits_entry_mode"),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = overlayEntryModeLabel(mode),
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        DdIcon(icon = DdIcons.ChevronDown, tint = colors.inkTertiary, size = 16.dp)
                    }
                },
            )
        },
    ) {
        OverlayEntryMode.entries.forEach { option ->
            DdMenuItem(
                text = overlayEntryModeLabel(option),
                onClick = {
                    expanded = false
                    if (option != mode) onModeChange(option)
                },
                selected = option == mode,
                trailing = if (option == mode) {
                    {
                        DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 18.dp)
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
internal fun overlayEntryModeLabel(mode: OverlayEntryMode): String = stringResource(
    when (mode) {
        OverlayEntryMode.VOICE -> R.string.usage_habits_mode_voice
        OverlayEntryMode.CAMERA -> R.string.usage_habits_mode_camera
        OverlayEntryMode.NONE -> R.string.usage_habits_mode_none
    },
)

private fun openOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:${context.packageName}".toUri(),
    )
    runCatching { context.startActivity(intent) }
}
