package io.github.whywood.superpi.ui.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSegmented
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdRowHorizontalPadding
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.MemoryAccessScope

private val BOT_MEMORY_SCOPES = listOf(
    MemoryAccessScope.PERSONAL,
    MemoryAccessScope.WORK_ONLY,
    MemoryAccessScope.OFF,
)

@Composable
internal fun BotMemoryPolicySection(
    policy: WorkspaceToolPolicy,
    isHome: Boolean,
    enabled: Boolean,
    useCardContainer: Boolean,
    tagPrefix: String,
    onSelectScope: (MemoryAccessScope) -> Unit,
    onToggleDispatch: (Boolean) -> Unit,
) {
    val colors = superPiColors()
    val activeScope = policy.effectiveMemoryScope()
    val dispatchEnabled = enabled && activeScope != MemoryAccessScope.OFF
    val effectiveDispatch = policy.effectiveMemoryDispatch(isHome)
    val selectedIndex = BOT_MEMORY_SCOPES.indexOf(activeScope).coerceAtLeast(0)
    val scopeTags = BOT_MEMORY_SCOPES.map { scope -> "${tagPrefix}_scope_${scope.wire.lowercase()}" }

    DdSectionLabel(stringResource(R.string.bot_memory_section))
    Spacer(Modifier.height(8.dp))

    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (useCardContainer) LocalDdRowHorizontalPadding.current else 0.dp,
                    vertical = DdSpacing.Md,
                ),
        ) {
            Text(
                text = stringResource(R.string.bot_memory_scope),
                style = DdType.body,
                color = if (enabled) colors.ink else colors.inkTertiary,
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdSegmented(
                options = listOf(
                    stringResource(R.string.bot_memory_scope_personal),
                    stringResource(R.string.bot_memory_scope_work),
                    stringResource(R.string.bot_memory_scope_off),
                ),
                selectedIndex = selectedIndex,
                onSelect = { idx ->
                    if (enabled) {
                        onSelectScope(BOT_MEMORY_SCOPES.getOrElse(idx) { MemoryAccessScope.PERSONAL })
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${tagPrefix}_scope"),
                testTags = scopeTags,
            )
        }
        DdRowDivider(
            leadingInset = if (useCardContainer) LocalDdRowHorizontalPadding.current else 0.dp,
        )
        DdListRow(
            title = stringResource(R.string.bot_memory_dispatch),
            enabled = dispatchEnabled,
            onClick = { onToggleDispatch(!effectiveDispatch) },
            modifier = Modifier.testTag("${tagPrefix}_dispatch"),
            trailing = {
                DdSwitch(
                    checked = effectiveDispatch,
                    onCheckedChange = onToggleDispatch,
                    enabled = dispatchEnabled,
                )
            },
        )
    }

    if (useCardContainer) {
        DdGroupCard {
            body()
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            body()
        }
    }
}
