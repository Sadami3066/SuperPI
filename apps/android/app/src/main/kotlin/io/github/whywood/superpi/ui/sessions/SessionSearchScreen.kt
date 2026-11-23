package io.github.whywood.superpi.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdSearchField
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.ui.chat.SessionSearchHit
import io.github.whywood.superpi.ui.common.rememberRelativeTime
import kotlinx.coroutines.delay

private const val SearchDebounceMillis = 130L
private const val SearchResultLimit = 100

@Composable
fun SessionSearchScreen(
    onBack: () -> Unit,
    loadSessions: suspend () -> List<SessionSearchHit>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var loading by remember { mutableStateOf(true) }
    var sessions by remember { mutableStateOf<List<SessionSearchHit>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        sessions = runCatching { loadSessions() }.getOrElse { emptyList() }
        loading = false
    }

    LaunchedEffect(loading) {
        if (!loading) revealed = true
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(query) {
        if (query != debouncedQuery) {
            delay(SearchDebounceMillis)
            debouncedQuery = query
        }
    }

    val ordered = remember(sessions, debouncedQuery) {
        val sorted = sessions.sortedByDescending { hit -> hit.conversation.updatedAt }
        val matched = filterSessionSearch(sorted, debouncedQuery)
        if (debouncedQuery.isBlank()) matched.take(SearchResultLimit) else matched
    }
    val groups = remember(ordered) {
        ordered.groupBy { hit -> hit.workspaceName }.toList()
    }

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.search_back),
                modifier = Modifier.testTag("search_back"),
                tint = colors.ink,
                iconSize = 21.dp,
            )
            Spacer(Modifier.width(DdSpacing.Xs))
            DdSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.search_placeholder),
                modifier = Modifier.weight(1f).padding(end = DdSpacing.Sm),
                focusRequester = focusRequester,
                clearContentDescription = stringResource(R.string.search_clear),
                onEscape = onBack,
                fieldTestTag = "search_field",
                clearTestTag = "search_clear",
            )
        },
        modifier = modifier.testTag("session_search"),
    ) {
        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                DdSpinner(
                    modifier = Modifier.testTag("search_loading"),
                    color = colors.inkTertiary,
                    size = 18.dp,
                    strokeWidth = 2.dp,
                )
            }
        } else {
            AnimatedVisibility(
                visible = revealed,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                enter = if (motionEnabled) {
                    fadeIn(tween(DdMotion.FAST)) +
                        slideInVertically(
                            animationSpec = tween(DdMotion.BASE, easing = DdMotion.EaseOut),
                            initialOffsetY = { it / 24 },
                        )
                } else {
                    EnterTransition.None
                },
            ) {
                if (ordered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DdEmptyState(
                            text = stringResource(R.string.search_empty),
                            icon = DdIcons.Search,
                            modifier = Modifier.testTag("search_empty"),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = DdSpacing.Md, end = DdSpacing.Md, top = DdSpacing.Xs, bottom = 18.dp),
                    ) {
                        groups.forEach { (workspaceName, hits) ->
                            item(key = "workspace_$workspaceName") {
                                DdSectionLabel(
                                    text = workspaceName,
                                    modifier = Modifier.padding(start = DdSpacing.Md, top = 14.dp, bottom = 6.dp),
                                )
                            }
                            itemsIndexed(hits, key = { _, hit -> hit.conversation.id }) { index, hit ->
                                Column(Modifier.animateItem()) {
                                    if (index > 0) {
                                        DdHairline(Modifier.padding(horizontal = DdSpacing.Md))
                                    }
                                    SearchSessionRow(
                                        hit = hit,
                                        onClick = {
                                            keyboard?.hide()
                                            onOpen(hit.conversation.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun sessionSearchSubtitle(hit: SessionSearchHit, relativeTime: String): String =
    listOfNotNull(hit.workspaceDevice, hit.workspaceName.takeIf { it.isNotBlank() }, relativeTime.takeIf { it.isNotBlank() })
        .joinToString(" · ")

private fun searchRowSubtitle(hit: SessionSearchHit, relativeTime: String): String =
    listOfNotNull(hit.workspaceDevice, relativeTime.takeIf { it.isNotBlank() })
        .joinToString(" · ")

@Composable
private fun SearchSessionRow(
    hit: SessionSearchHit,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    val relativeTime = rememberRelativeTime(hit.conversation.updatedAt)
    val subtitle = searchRowSubtitle(hit, relativeTime)
    DdPressable(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_result_${hit.conversation.id}"),
        shape = DdShapes.Medium,
        contentDescription = hit.conversation.title,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = DdSpacing.Md, vertical = 9.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = hit.conversation.title,
                style = DdType.body.copy(fontSize = 16.sp, lineHeight = 23.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(DdSpacing.Xxs))
                Text(
                    text = subtitle,
                    style = DdType.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
