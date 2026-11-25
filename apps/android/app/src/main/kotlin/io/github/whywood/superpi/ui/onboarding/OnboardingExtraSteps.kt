package io.github.whywood.superpi.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.AppMode
import io.github.whywood.superpi.data.CHAT_FONT_SCALE_MAX
import io.github.whywood.superpi.data.CHAT_FONT_SCALE_MIN
import io.github.whywood.superpi.data.OverlayEntryMode
import io.github.whywood.superpi.data.ProcessDetail
import io.github.whywood.superpi.data.SendWhileRunning
import io.github.whywood.superpi.data.WebSearchSettingsStore
import io.github.whywood.superpi.data.normalizeChatFontScale
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdExpandToggle
import io.github.whywood.superpi.design.DdFontStyle
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdSegmentedControl
import io.github.whywood.superpi.design.DdSlider
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdTextField
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.theme.ThemeAppearanceMode
import io.github.whywood.superpi.theme.ThemeChoice
import io.github.whywood.superpi.theme.ThemeOption
import io.github.whywood.superpi.ui.common.BotAvatar
import io.github.whywood.superpi.ui.common.PiBrandMark
import io.github.whywood.superpi.ui.common.ThemeMiniPreview
import io.github.whywood.superpi.ui.common.overlayEntryModeLabel
import io.github.whywood.superpi.ui.common.webSearchProviderName
import kotlin.math.roundToInt

private const val MAX_BOT_NAME_LENGTH = 40
private const val MAX_BOT_INTRO_LENGTH = 200

@Composable
internal fun BotStep(
    botName: String,
    onBotNameChange: (String) -> Unit,
    botIntro: String,
    onBotIntroChange: (String) -> Unit,
    botAvatarPath: String?,
    onPickAvatar: () -> Unit,
    onClearAvatar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val previewName = botName.trim().ifEmpty { AgentRuntimeHost.HOME_NAME }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DdSpacing.Xxl)
            .testTag("onboarding_step_bot"),
    ) {
        Spacer(Modifier.height(DdSpacing.Sm))
        StepHeading(
            title = stringResource(R.string.onboarding_bot_title),
            subtitle = stringResource(R.string.onboarding_bot_subtitle),
        )
        Spacer(Modifier.height(DdSpacing.Xxl))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DdPressable(
                onClick = onPickAvatar,
                modifier = Modifier.size(84.dp).testTag("onboarding_bot_avatar"),
                shape = CircleShape,
                contentDescription = stringResource(R.string.onboarding_bot_avatar_pick),
            ) {
                if (!botAvatarPath.isNullOrBlank()) {
                    BotAvatar(
                        name = previewName,
                        avatar = botAvatarPath,
                        size = 84.dp,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PiBrandMark(
                            size = 76.dp,
                            contentDescription = previewName,
                        )
                    }
                }
            }
            Spacer(Modifier.height(DdSpacing.Sm))
            Row(horizontalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
                DdTextAction(
                    text = stringResource(R.string.onboarding_bot_avatar_pick),
                    onClick = onPickAvatar,
                    color = colors.accent,
                )
                if (!botAvatarPath.isNullOrBlank()) {
                    DdTextAction(
                        text = stringResource(R.string.onboarding_bot_avatar_remove),
                        onClick = onClearAvatar,
                        color = colors.inkTertiary,
                    )
                }
            }
        }
        Spacer(Modifier.height(DdSpacing.Xl))
        FieldLabel(text = stringResource(R.string.onboarding_bot_name_label))
        Spacer(Modifier.height(DdSpacing.Xxs))
        UnderlineField(
            value = botName,
            onValueChange = { onBotNameChange(it.take(MAX_BOT_NAME_LENGTH)) },
            placeholder = stringResource(R.string.onboarding_bot_name_hint),
            testTag = "onboarding_bot_name",
        )
        Spacer(Modifier.height(DdSpacing.Xl))
        FieldLabel(text = stringResource(R.string.onboarding_bot_intro_label))
        Spacer(Modifier.height(DdSpacing.Xxs))
        UnderlineField(
            value = botIntro,
            onValueChange = { onBotIntroChange(it.take(MAX_BOT_INTRO_LENGTH)) },
            placeholder = stringResource(R.string.onboarding_bot_intro_hint),
            testTag = "onboarding_bot_intro",
        )
        Spacer(Modifier.height(DdSpacing.Xxl))
    }
}

@Composable
internal fun AppearanceStep(
    builtInThemes: List<ThemeOption>,
    selectedTheme: ThemeChoice,
    appearance: ThemeAppearanceMode,
    fontStyle: DdFontStyle,
    onSelectAppearance: (ThemeAppearanceMode) -> Unit,
    onSelectTheme: (ThemeChoice) -> Unit,
    onSelectFontStyle: (DdFontStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val systemLabel = stringResource(R.string.settings_theme_appearance_system)
    val lightLabel = stringResource(R.string.settings_theme_appearance_light)
    val darkLabel = stringResource(R.string.settings_theme_appearance_dark)
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DdSpacing.Xxl)
            .testTag("onboarding_step_appearance"),
    ) {
        Spacer(Modifier.height(DdSpacing.Sm))
        StepHeading(
            title = stringResource(R.string.onboarding_appearance_title),
            subtitle = stringResource(R.string.onboarding_appearance_subtitle),
        )
        Spacer(Modifier.height(DdSpacing.Xxl))
        FieldLabel(text = stringResource(R.string.settings_theme_appearance))
        Spacer(Modifier.height(DdSpacing.Sm))
        DdSegmentedControl(
            options = ThemeAppearanceMode.entries,
            selected = appearance,
            onSelect = onSelectAppearance,
        ) { mode ->
            when (mode) {
                ThemeAppearanceMode.SYSTEM -> systemLabel
                ThemeAppearanceMode.LIGHT -> lightLabel
                ThemeAppearanceMode.DARK -> darkLabel
            }
        }
        Spacer(Modifier.height(DdSpacing.Xl))
        FieldLabel(text = stringResource(R.string.settings_theme_builtin))
        Spacer(Modifier.height(DdSpacing.Sm))
        OnboardingCard {
            builtInThemes.forEachIndexed { index, option ->
                if (index > 0) DdHairline()
                val selected = option.id == selectedTheme
                DdPressable(
                    onClick = { onSelectTheme(option.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_theme_${option.id.key}")
                        .semantics { this.selected = selected },
                    shape = RectangleShape,
                    contentDescription = option.label,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThemeMiniPreview(
                            preview = option.colors,
                            selected = selected,
                            selectedBorder = colors.accent,
                        )
                        Spacer(Modifier.width(DdSpacing.Md))
                        Text(
                            text = option.label,
                            modifier = Modifier.weight(1f),
                            style = DdType.body,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected) {
                            DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 18.dp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(DdSpacing.Xl))
        FieldLabel(text = stringResource(R.string.settings_font))
        Spacer(Modifier.height(DdSpacing.Sm))
        OnboardingCard {
            DdFontStyle.entries.forEachIndexed { index, option ->
                if (index > 0) DdHairline()
                val label = if (option == DdFontStyle.SYSTEM) {
                    stringResource(R.string.settings_font_system)
                } else {
                    option.displayName
                }
                val selected = option == fontStyle
                DdListRow(
                    title = label,
                    onClick = { onSelectFontStyle(option) },
                    modifier = Modifier
                        .testTag("onboarding_font_${option.name.lowercase()}")
                        .semantics { this.selected = selected },
                    trailing = {
                        if (selected) {
                            DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 18.dp)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(DdSpacing.Huge))
    }
}

@Composable
internal fun ModeStep(
    appMode: AppMode,
    processDetail: ProcessDetail,
    sendWhileRunning: SendWhileRunning,
    chatFontScale: Float,
    onSelectAppMode: (AppMode) -> Unit,
    onSelectProcessDetail: (ProcessDetail) -> Unit,
    onSelectSendWhileRunning: (SendWhileRunning) -> Unit,
    onChatFontScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val detailedLabel = stringResource(R.string.chat_process_detail_detailed)
    val compactLabel = stringResource(R.string.chat_process_detail_compact)
    val steerLabel = stringResource(R.string.settings_send_while_running_steer)
    val queueLabel = stringResource(R.string.settings_send_while_running_queue)
    var sliderValue by remember(chatFontScale) { mutableFloatStateOf(normalizeChatFontScale(chatFontScale)) }
    val percent = (sliderValue * 100f).roundToInt()
    val previewStyle = DdType.body.copy(
        fontSize = (16f * sliderValue).sp,
        lineHeight = (26f * sliderValue).sp,
    )
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DdSpacing.Xxl)
            .testTag("onboarding_step_mode"),
    ) {
        Spacer(Modifier.height(DdSpacing.Sm))
        StepHeading(
            title = stringResource(R.string.onboarding_mode_title),
            subtitle = stringResource(R.string.onboarding_mode_subtitle),
        )
        Spacer(Modifier.height(DdSpacing.Xxl))
        FieldLabel(text = stringResource(R.string.onboarding_mode_section))
        Spacer(Modifier.height(DdSpacing.Sm))
        OnboardingCard {
            val normalSelected = appMode == AppMode.NORMAL
            DdListRow(
                title = stringResource(R.string.onboarding_mode_normal_title),
                subtitle = stringResource(R.string.onboarding_mode_normal_body),
                subtitleMaxLines = 2,
                onClick = { onSelectAppMode(AppMode.NORMAL) },
                modifier = Modifier
                    .testTag("onboarding_mode_normal")
                    .semantics { selected = normalSelected },
                trailing = { RadioMark(checked = normalSelected) },
            )
            DdHairline()
            val superSelected = appMode == AppMode.SUPER
            DdListRow(
                title = stringResource(R.string.onboarding_mode_super_title),
                subtitle = stringResource(R.string.onboarding_mode_super_body),
                subtitleMaxLines = 2,
                onClick = { onSelectAppMode(AppMode.SUPER) },
                modifier = Modifier
                    .testTag("onboarding_mode_super")
                    .semantics { selected = superSelected },
                trailing = { RadioMark(checked = superSelected) },
            )
        }
        Spacer(Modifier.height(DdSpacing.Xl))
        FieldLabel(text = stringResource(R.string.onboarding_chat_prefs_section))
        Spacer(Modifier.height(DdSpacing.Sm))
        OnboardingCard {
            Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
                Text(
                    text = stringResource(R.string.chat_process_detail_title),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.height(DdSpacing.Sm))
                DdSegmentedControl(
                    options = ProcessDetail.entries,
                    selected = processDetail,
                    onSelect = onSelectProcessDetail,
                ) { detail ->
                    when (detail) {
                        ProcessDetail.DETAILED -> detailedLabel
                        ProcessDetail.COMPACT -> compactLabel
                    }
                }
                Spacer(Modifier.height(DdSpacing.Lg))
                Text(
                    text = stringResource(R.string.settings_send_while_running),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.height(DdSpacing.Sm))
                DdSegmentedControl(
                    options = listOf(SendWhileRunning.STEER, SendWhileRunning.QUEUE),
                    selected = sendWhileRunning,
                    onSelect = onSelectSendWhileRunning,
                ) { mode ->
                    when (mode) {
                        SendWhileRunning.STEER -> steerLabel
                        SendWhileRunning.QUEUE -> queueLabel
                    }
                }
            }
            DdHairline()
            Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.chat_font_size_title),
                        modifier = Modifier.weight(1f),
                        style = DdType.body,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(DdSpacing.Sm))
                    Text(
                        text = stringResource(R.string.chat_font_size_value, percent),
                        style = DdType.numeric,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                DdSlider(
                    value = sliderValue,
                    onValueChange = { sliderValue = normalizeChatFontScale(it) },
                    onValueChangeFinished = { onChatFontScaleChange(sliderValue) },
                    valueRange = CHAT_FONT_SCALE_MIN..CHAT_FONT_SCALE_MAX,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_font_size_slider"),
                )
                Text(
                    text = stringResource(R.string.chat_font_size_preview),
                    style = previewStyle,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(DdSpacing.Huge))
    }
}

@Composable
internal fun WebSearchOptionalSection(
    configured: Map<String, Boolean>,
    onSave: (String, String) -> Unit,
    onClear: (String) -> Unit,
) {
    val colors = superPiColors()
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val searchProviders = listOf(
        WebSearchSettingsStore.DEEPSEEK,
        WebSearchSettingsStore.EXA,
        WebSearchSettingsStore.TAVILY,
        WebSearchSettingsStore.BRAVE,
        WebSearchSettingsStore.SERPER,
    )
    Spacer(Modifier.height(DdSpacing.Xxl))
    OnboardingCard {
        DdExpandToggle(
            expanded = expanded,
            collapsedLabel = stringResource(R.string.onboarding_web_search_title),
            expandedLabel = stringResource(R.string.onboarding_web_search_title),
            onToggle = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().testTag("onboarding_web_search_toggle"),
        )
        if (expanded) {
            DdHairline()
            Text(
                text = stringResource(R.string.onboarding_web_search_subtitle),
                modifier = Modifier.padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Sm),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )
            searchProviders.forEach { id ->
                DdHairline()
                DdListRow(
                    title = stringResource(webSearchProviderName(id)),
                    value = stringResource(
                        if (configured[id] == true) R.string.web_search_configured
                        else R.string.web_search_not_configured,
                    ),
                    onClick = {
                        draft = ""
                        editing = id
                    },
                    showChevron = true,
                    modifier = Modifier.testTag("onboarding_web_search_$id"),
                )
            }
        }
    }
    val provider = editing
    if (provider != null) {
        DdDialog(
            onDismissRequest = {
                draft = ""
                editing = null
            },
            title = stringResource(webSearchProviderName(provider)),
            content = {
                Column {
                    DdTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = stringResource(R.string.web_search_key_hint),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        testTag = "onboarding_web_search_key_$provider",
                    )
                    if (configured[provider] == true) {
                        Spacer(Modifier.height(DdSpacing.Md))
                        DdTextAction(
                            text = stringResource(R.string.web_search_clear),
                            onClick = {
                                onClear(provider)
                                draft = ""
                                editing = null
                            },
                            color = colors.warning,
                        )
                    }
                }
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.web_search_cancel),
                    onClick = {
                        draft = ""
                        editing = null
                    },
                    color = colors.inkSecondary,
                )
                DdTextAction(
                    text = stringResource(R.string.web_search_save),
                    onClick = {
                        val value = draft.trim()
                        if (value.isNotEmpty()) {
                            onSave(provider, value)
                            draft = ""
                            editing = null
                        }
                    },
                )
            },
        )
    }
}

@Composable
internal fun OverlayPreferencesSection(
    floatingEnabled: Boolean,
    overlayEntryMode: OverlayEntryMode,
    onToggleFloating: (Boolean) -> Unit,
    onOverlayEntryModeChange: (OverlayEntryMode) -> Unit,
) {
    val colors = superPiColors()
    Spacer(Modifier.height(DdSpacing.Xl))
    FieldLabel(text = stringResource(R.string.onboarding_overlay_section))
    Spacer(Modifier.height(DdSpacing.Sm))
    OnboardingCard {
        DdListRow(
            title = stringResource(R.string.floating_settings_enable),
            subtitle = stringResource(R.string.onboarding_floating_hint),
            subtitleMaxLines = 2,
            onClick = { onToggleFloating(!floatingEnabled) },
            trailing = {
                DdSwitch(
                    checked = floatingEnabled,
                    onCheckedChange = onToggleFloating,
                    stateDescription = stringResource(
                        if (floatingEnabled) R.string.settings_switch_on
                        else R.string.settings_switch_off,
                    ),
                )
            },
            modifier = Modifier.testTag("onboarding_floating_enable"),
        )
        DdHairline()
        Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
            Text(
                text = stringResource(R.string.usage_habits_entry_mode),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdSegmentedControl(
                options = OverlayEntryMode.entries,
                selected = overlayEntryMode,
                onSelect = onOverlayEntryModeChange,
            ) { mode ->
                overlayEntryModeLabel(mode)
            }
        }
    }
}
