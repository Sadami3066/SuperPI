package io.github.whywood.superpi.ui.settings

import androidx.annotation.StringRes
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.CHAT_FONT_SCALE_MAX
import io.github.whywood.superpi.data.CHAT_FONT_SCALE_MIN
import io.github.whywood.superpi.data.ProcessDetail
import io.github.whywood.superpi.data.SendWhileRunning
import io.github.whywood.superpi.data.UiPreferences
import io.github.whywood.superpi.data.UiToggle
import io.github.whywood.superpi.data.normalizeChatFontScale
import kotlin.math.roundToInt
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSlider
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdRowHorizontalPadding
import io.github.whywood.superpi.design.superPiColors

@Composable
fun ChatSettingsScreen(
    preferences: UiPreferences,
    sendWhileRunning: SendWhileRunning = SendWhileRunning.STEER,
    processDetail: ProcessDetail = ProcessDetail.DETAILED,
    onBack: () -> Unit,
    onToggle: (UiToggle, Boolean) -> Unit,
    voiceAutoSpeak: Boolean = false,
    onVoiceAutoSpeakChange: (Boolean) -> Unit = {},
    onChatFontScaleChange: (Float) -> Unit = {},
    onSendWhileRunningChange: (SendWhileRunning) -> Unit = {},
    onProcessDetailChange: (ProcessDetail) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
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
        modifier = modifier,
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.ScreenHorizontal),
        ) {
            Text(
                text = stringResource(R.string.settings_chat),
                style = DdType.pageTitle,
                color = colors.ink,
            )
            Spacer(Modifier.height(DdSpacing.Xl))
            DdGroupCard {
                ChatFontSizeRow(
                    scale = preferences.chatFontScale,
                    onChange = onChatFontScaleChange,
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                ProcessDetailRow(
                    value = processDetail,
                    onChange = onProcessDetailChange,
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SendWhileRunningRow(
                    value = sendWhileRunning,
                    onChange = onSendWhileRunningChange,
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SwitchRow(
                    title = R.string.settings_auto_retry,
                    checked = preferences.autoRetry,
                    onCheckedChange = { onToggle(UiToggle.AUTO_RETRY, it) },
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SwitchRow(
                    title = R.string.settings_background_job_wake,
                    checked = preferences.backgroundJobWake,
                    onCheckedChange = { onToggle(UiToggle.BACKGROUND_JOB_WAKE, it) },
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SwitchRow(
                    title = R.string.voice_setting_auto_speak,
                    checked = voiceAutoSpeak,
                    onCheckedChange = onVoiceAutoSpeakChange,
                )
            }
            Spacer(Modifier.height(DdSpacing.Huge))
        }
    }
}

@Composable
private fun SendWhileRunningRow(
    value: SendWhileRunning,
    onChange: (SendWhileRunning) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsDropdownRow(
        title = stringResource(R.string.settings_send_while_running),
        value = sendWhileRunningLabel(value),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        options = listOf(SendWhileRunning.STEER, SendWhileRunning.QUEUE),
        selected = value,
        optionLabel = { option -> sendWhileRunningLabel(option) },
        onSelect = onChange,
        rowTag = "settings_send_while_running",
        optionTag = { option ->
            when (option) {
                SendWhileRunning.STEER -> "settings_send_while_running_option_steer"
                SendWhileRunning.QUEUE -> "settings_send_while_running_option_queue"
            }
        },
    )
}

@Composable
private fun sendWhileRunningLabel(value: SendWhileRunning): String = stringResource(
    when (value) {
        SendWhileRunning.STEER -> R.string.settings_send_while_running_steer
        SendWhileRunning.QUEUE -> R.string.settings_send_while_running_queue
    },
)

@Composable
private fun ProcessDetailRow(
    value: ProcessDetail,
    onChange: (ProcessDetail) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsDropdownRow(
        title = stringResource(R.string.chat_process_detail_title),
        value = processDetailLabel(value),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        options = ProcessDetail.entries,
        selected = value,
        optionLabel = { option -> processDetailLabel(option) },
        onSelect = onChange,
        rowTag = "settings_process_detail",
        optionTag = { option -> "settings_process_detail_option_${option.name.lowercase()}" },
    )
}

@Composable
private fun processDetailLabel(value: ProcessDetail): String = stringResource(
    when (value) {
        ProcessDetail.DETAILED -> R.string.chat_process_detail_detailed
        ProcessDetail.COMPACT -> R.string.chat_process_detail_compact
    },
)

@Composable
private fun SwitchRow(
    @StringRes title: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val stateDescription = stringResource(
        if (checked) R.string.settings_switch_on else R.string.settings_switch_off,
    )
    DdListRow(
        title = stringResource(title),
        onClick = { onCheckedChange(!checked) },
        trailing = {
            DdSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                stateDescription = stateDescription,
            )
        },
    )
}


@Composable
private fun ChatFontSizeRow(scale: Float, onChange: (Float) -> Unit) {
    val colors = superPiColors()
    var sliderValue by remember(scale) { mutableFloatStateOf(normalizeChatFontScale(scale)) }
    val percent = (sliderValue * 100f).roundToInt()
    Column(
        Modifier.fillMaxWidth().padding(
            horizontal = LocalDdRowHorizontalPadding.current,
            vertical = DdSpacing.Md,
        ),
    ) {
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
            onValueChangeFinished = { onChange(sliderValue) },
            valueRange = CHAT_FONT_SCALE_MIN..CHAT_FONT_SCALE_MAX,
            steps = 10,
            modifier = Modifier.fillMaxWidth().testTag("chat_font_size_slider"),
        )
        Text(
            text = stringResource(R.string.chat_font_size_preview),
            modifier = Modifier.padding(vertical = DdSpacing.Xs),
            style = DdType.body.copy(
                fontSize = (16f * sliderValue).sp,
                lineHeight = (26f * sliderValue).sp,
            ),
            color = colors.inkSecondary,
        )
    }
}
