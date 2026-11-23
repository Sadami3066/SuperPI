package io.github.whywood.superpi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.WebSearchSettingsStore
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdTextField
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors

@Composable
fun WebSearchSettingsScreen(
    configured: Map<String, Boolean>,
    onSave: (String, String) -> Unit,
    onClear: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
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
        modifier = modifier.testTag("web_search_settings"),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.ScreenHorizontal),
        ) {
            Text(
                text = stringResource(R.string.web_search_title),
                style = DdType.pageTitle,
                color = colors.ink,
            )
            Spacer(Modifier.height(DdSpacing.Xl))
            val providers = listOf(
                WebSearchSettingsStore.DEEPSEEK,
                WebSearchSettingsStore.EXA,
                WebSearchSettingsStore.TAVILY,
                WebSearchSettingsStore.BRAVE,
                WebSearchSettingsStore.SERPER,
            )
            DdGroupCard {
                providers.forEachIndexed { index, id ->
                    if (index > 0) DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                    WebSearchProviderEntry(
                        id = id,
                        configured = configured[id] == true,
                        onEdit = {
                            draft = ""
                            editing = id
                        },
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Huge))
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
                        testTag = "web_search_key_$provider",
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
private fun WebSearchProviderEntry(
    id: String,
    configured: Boolean,
    onEdit: () -> Unit,
) {
    DdListRow(
        title = stringResource(webSearchProviderName(id)),
        value = stringResource(if (configured) R.string.web_search_configured else R.string.web_search_not_configured),
        onClick = onEdit,
        modifier = Modifier.testTag("web_search_$id"),
    )
}

private fun webSearchProviderName(id: String): Int = when (id) {
    WebSearchSettingsStore.DEEPSEEK -> R.string.web_search_deepseek
    WebSearchSettingsStore.EXA -> R.string.web_search_exa
    WebSearchSettingsStore.TAVILY -> R.string.web_search_tavily
    WebSearchSettingsStore.BRAVE -> R.string.web_search_brave
    else -> R.string.web_search_serper
}
