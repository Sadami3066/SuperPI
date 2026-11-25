package io.github.whywood.superpi.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.OverlayEntryMode
import io.github.whywood.superpi.data.WebSearchSettingsStore
import io.github.whywood.superpi.design.SuperPIColors

@Composable
internal fun ThemeMiniPreview(
    preview: SuperPIColors,
    selected: Boolean,
    selectedBorder: Color,
) {
    val shape = RoundedCornerShape(9.dp)
    val borderWidth = if (selected) 1.5.dp else 0.75.dp
    val borderColor = if (selected) selectedBorder else preview.surfaceRim
    Column(
        modifier = Modifier
            .size(width = 52.dp, height = 38.dp)
            .clip(shape)
            .background(preview.paper)
            .border(borderWidth, borderColor, shape)
            .padding(4.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(preview.signal),
            )
            Box(
                Modifier
                    .width(12.dp)
                    .height(2.5.dp)
                    .clip(CircleShape)
                    .background(preview.ink.copy(alpha = 0.8f)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                Modifier
                    .size(width = 18.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(preview.accentSoft),
            )
        }
        Box(
            Modifier
                .fillMaxWidth(0.65f)
                .padding(start = 2.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(preview.inkTertiary.copy(alpha = 0.55f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(preview.composer)
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .width(14.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(preview.inkTertiary.copy(alpha = 0.45f)),
            )
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(preview.accent),
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

internal fun webSearchProviderName(id: String): Int = when (id) {
    WebSearchSettingsStore.DEEPSEEK -> R.string.web_search_deepseek
    WebSearchSettingsStore.EXA -> R.string.web_search_exa
    WebSearchSettingsStore.TAVILY -> R.string.web_search_tavily
    WebSearchSettingsStore.BRAVE -> R.string.web_search_brave
    else -> R.string.web_search_serper
}
