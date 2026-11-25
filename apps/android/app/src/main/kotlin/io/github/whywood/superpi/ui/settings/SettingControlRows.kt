package io.github.whywood.superpi.ui.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdMenu
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdSlider
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdRowHorizontalPadding
import io.github.whywood.superpi.design.superPiColors
import kotlin.math.roundToInt

@Composable
internal fun SwitchSettingRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    DdListRow(
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = 2,
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.testTag(tag),
        trailing = {
            DdSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                stateDescription = title,
            )
        },
    )
}

@Composable
internal fun <T> SettingsDropdownRow(
    title: String,
    subtitle: String? = null,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    rowTag: String,
    optionTag: (T) -> String,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        DdListRow(
            title = title,
            subtitle = subtitle,
            subtitleMaxLines = 2,
            value = value,
            onClick = { onExpandedChange(true) },
            showChevron = true,
            modifier = Modifier.testTag(rowTag),
        )
        DdMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            menuWidth = maxWidth,
        ) {
            options.forEach { option ->
                DdMenuItem(
                    text = optionLabel(option),
                    onClick = {
                        onExpandedChange(false)
                        onSelect(option)
                    },
                    selected = option == selected,
                    testTag = optionTag(option),
                )
            }
        }
    }
}

@Composable
internal fun SteppedIntSliderRow(
    title: String,
    subtitle: String?,
    valueText: @Composable (Int) -> String,
    value: Int,
    min: Int,
    max: Int,
    stepSize: Int,
    normalize: (Int) -> Int,
    onChange: (Int) -> Unit,
    sliderTag: String,
) {
    val colors = superPiColors()
    var sliderValue by remember(value) { mutableIntStateOf(normalize(value)) }
    Column(
        Modifier.fillMaxWidth().padding(
            horizontal = LocalDdRowHorizontalPadding.current,
            vertical = DdSpacing.Md,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = DdType.body,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            Text(
                text = valueText(sliderValue),
                style = DdType.numeric,
                color = colors.inkSecondary,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )
        }
        DdSlider(
            value = sliderValue.toFloat(),
            onValueChange = { raw -> sliderValue = normalize(raw.roundToInt()) },
            onValueChangeFinished = { onChange(sliderValue) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / stepSize - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().testTag(sliderTag),
        )
    }
}
