package io.github.whywood.superpi.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdChip
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdPrimaryButton
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryTagCount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private enum class TimelineBucket(val labelRes: Int) {
    UPCOMING(R.string.memory_timeline_group_upcoming),
    TODAY(R.string.memory_timeline_group_today),
    WEEK(R.string.memory_timeline_group_week),
    MONTH(R.string.memory_timeline_group_month),
    EARLIER(R.string.memory_timeline_group_earlier),
}

@Composable
internal fun MemoryTimelineTab(
    query: String,
    events: List<MemoryEvent>,
    tags: List<MemoryTagCount>,
    selectedTag: String?,
    includeHistory: Boolean,
    hasMore: Boolean,
    onSelectTag: (String) -> Unit,
    onToggleIncludeHistory: () -> Unit,
    onLoadMore: () -> Unit,
    onClickEvent: (MemoryEvent) -> Unit,
    onAddEvent: () -> Unit,
    onClearFilters: () -> Unit,
) {
    val hasFilters = query.isNotBlank() || selectedTag != null || includeHistory
    val grouped = remember(events) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val buckets = LinkedHashMap<TimelineBucket, MutableList<MemoryEvent>>()
        events.forEach { ev ->
            val bucket = bucketFor(ev.eventTime, today, zone)
            buckets.getOrPut(bucket) { ArrayList() }.add(ev)
        }
        TimelineBucket.entries.mapNotNull { bucket ->
            val list = buckets[bucket] ?: return@mapNotNull null
            val sorted = if (bucket == TimelineBucket.UPCOMING) {
                list.sortedBy { it.eventTime }
            } else {
                list.sortedByDescending { it.eventTime }
            }
            bucket to sorted
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = DdSpacing.Xxl),
            horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            item(key = "toggle_history") {
                DdChip(
                    text = stringResource(R.string.memory_show_history),
                    selected = includeHistory,
                    onClick = onToggleIncludeHistory,
                    modifier = Modifier.testTag("memory_timeline_history_toggle"),
                )
            }
            items(tags.take(16), key = { "tag:${it.tag}" }) { tag ->
                DdChip(
                    text = "#${tag.tag}",
                    selected = selectedTag == tag.tag,
                    onClick = { onSelectTag(tag.tag) },
                )
            }
        }
        Spacer(Modifier.height(DdSpacing.Xs))

        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Xxl),
                contentAlignment = Alignment.Center,
            ) {
                if (hasFilters) {
                    DdEmptyState(
                        text = stringResource(R.string.memory_empty_filtered),
                        icon = DdIcons.Search,
                        action = {
                            DdTextAction(
                                text = stringResource(R.string.memory_clear_filters),
                                onClick = onClearFilters,
                            )
                        },
                    )
                } else {
                    DdEmptyState(
                        text = stringResource(R.string.memory_empty_timeline),
                        icon = DdIcons.Clock,
                        action = {
                            DdPrimaryButton(
                                text = stringResource(R.string.memory_add_event),
                                onClick = onAddEvent,
                            )
                        },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("memory_timeline_list"),
                contentPadding = PaddingValues(top = DdSpacing.Xs, bottom = DdSpacing.Xxxl),
            ) {
                grouped.forEach { (bucket, bucketEvents) ->
                    item(key = "header:${bucket.name}") {
                        DdSectionLabel(
                            text = stringResource(bucket.labelRes),
                            modifier = Modifier.padding(
                                start = DdSpacing.Xxl,
                                end = DdSpacing.Xxl,
                                top = DdSpacing.Md,
                                bottom = DdSpacing.Xs,
                            ),
                        )
                    }
                    items(bucketEvents, key = { it.id }) { event ->
                        Column(Modifier.animateItem()) {
                            TimelineEventRow(
                                event = event,
                                onClick = { onClickEvent(event) },
                            )
                            DdHairline(Modifier.padding(horizontal = DdSpacing.Xxl))
                        }
                    }
                }
                if (hasMore) {
                    item(key = "memory_load_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = DdSpacing.Lg),
                            contentAlignment = Alignment.Center,
                        ) {
                            DdTextAction(
                                text = stringResource(R.string.memory_load_more),
                                onClick = onLoadMore,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEventRow(
    event: MemoryEvent,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    val todayLabel = stringResource(R.string.memory_date_today)
    val yesterdayLabel = stringResource(R.string.memory_date_yesterday)
    val daysAgoFormat = stringResource(R.string.memory_date_days_ago)
    val tomorrowLabel = stringResource(R.string.memory_date_tomorrow)
    val inDaysFormat = stringResource(R.string.memory_date_in_days)
    val timestamp = if (event.eventTime > 0L) event.eventTime else event.validFrom
    val dateText = remember(timestamp, todayLabel, yesterdayLabel, daysAgoFormat, tomorrowLabel, inDaysFormat) {
        formatRelativeDate(timestamp, todayLabel, yesterdayLabel, daysAgoFormat, tomorrowLabel, inDaysFormat)
    }

    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = DdShapes.Row,
        contentDescription = event.content,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Xxl, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                DdText(
                    text = event.content,
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.pinned) {
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdIcon(
                        icon = DdIcons.Pin,
                        tint = colors.accent,
                        size = 15.dp,
                        contentDescription = stringResource(R.string.memory_pinned_cd),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
                ) {
                    DdText(
                        text = stringResource(eventKindLabelRes(event.eventKind)),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    eventStatusLabelRes(event.status)?.let { statusRes ->
                        TimelineMetaPill(
                            text = stringResource(statusRes),
                            accent = false,
                        )
                    }
                    if (event.entityNames.isNotEmpty()) {
                        event.entityNames.take(2).forEach { entityName ->
                            TimelineMetaPill(text = entityName, accent = true)
                        }
                    } else if (event.tags.isNotEmpty()) {
                        val tagsSummary = remember(event.tags) {
                            event.tags.take(2).joinToString(" ") { "#$it" }
                        }
                        DdText(
                            text = tagsSummary,
                            modifier = Modifier.weight(1f, fill = false),
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (event.epistemicTier == MemoryEpistemicTier.INFERRED) {
                    TimelineMetaPill(
                        text = stringResource(R.string.memory_inferred_badge),
                        accent = true,
                    )
                }
                DdText(
                    text = dateText,
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun TimelineMetaPill(
    text: String,
    accent: Boolean,
) {
    val colors = superPiColors()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.wash)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        DdText(
            text = text,
            style = DdType.micro,
            color = if (accent) colors.accent else colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun bucketFor(epochMs: Long, today: LocalDate, zone: ZoneId): TimelineBucket {
    if (epochMs <= 0L) return TimelineBucket.TODAY
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days < 0L -> TimelineBucket.UPCOMING
        days == 0L -> TimelineBucket.TODAY
        days <= 6L -> TimelineBucket.WEEK
        days <= 30L -> TimelineBucket.MONTH
        else -> TimelineBucket.EARLIER
    }
}
