package io.github.whywood.superpi.design

import android.os.Trace
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Immutable
data class DdMarkdownImage(
    val alt: String,
    val source: String,
    val title: String? = null,
)

private val mdBody @Composable get() = DdType.body.copy(letterSpacing = 0.sp)
private val mdHeading1 @Composable get() = mdBody.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
private val mdHeading2 @Composable get() = mdBody.copy(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
private val mdHeading3 @Composable get() = mdBody.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)

private val tableCellStyle @Composable get() = DdType.bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp)
private val tableHeaderStyle @Composable get() = tableCellStyle.copy(fontWeight = FontWeight.Medium)

@Composable
fun DdMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    onFileClick: ((String) -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
    codeLabel: String = "",
    copyCodeLabel: String = "",
    onCopyCode: ((String) -> Unit)? = null,
    onImage: (@Composable (DdMarkdownImage) -> Unit)? = null,
) {
    val colors = superPiColors()
    val planner = remember { MarkdownStreamPlanner() }
    val plan = remember(text) {
        Trace.beginSection("superpi.markdown.parse")
        try {
            planner.plan(text)
        } finally {
            Trace.endSection()
        }
    }
    val document = remember(plan.links, plan.footnoteNumbers, plan.footnotes) {
        MarkdownDocument(emptyList(), plan.links, plan.footnoteNumbers, plan.footnotes)
    }
    val chunkBlocks = ArrayList<List<MarkdownBlock>>(plan.chunks.size)
    plan.chunks.forEach { chunk -> chunkBlocks += rememberChunkBlocks(chunk) }
    val fileClick = rememberFileClickHandler(onFileClick)
    val linkClick = rememberLinkClickHandler(onLinkClick)
    val copyCode = rememberCopyCodeHandler(onCopyCode)
    val imageHandler = rememberImageHandler(onImage)
    Column(modifier.fillMaxWidth()) {
        plan.chunks.indices.forEach { chunkIndex ->
            val blocks = chunkBlocks[chunkIndex]
            val previousTail = if (chunkIndex > 0) chunkBlocks[chunkIndex - 1].lastOrNull() else null
            key(chunkIndex) {
                if (chunkIndex > 0 && blocks.isNotEmpty()) {
                    val gap = if (previousTail != null) blockGap(previousTail, blocks.first()) else DdSpacing.Md
                    Spacer(Modifier.height(gap))
                }
                MarkdownBlocks(
                    blocks = blocks,
                    document = document,
                    colors = colors,
                    onFileClick = fileClick,
                    onLinkClick = linkClick,
                    live = live,
                    codeLabel = codeLabel,
                    copyCodeLabel = copyCodeLabel,
                    onCopyCode = copyCode,
                    onImage = imageHandler,
                )
            }
        }
        if (document.footnotes.isNotEmpty()) {
            if (chunkBlocks.any { it.isNotEmpty() }) Spacer(Modifier.height(DdSpacing.Md))
            FootnoteList(document.footnotes, document, colors, fileClick, linkClick)
        }
    }
}

@Composable
private fun rememberChunkBlocks(chunk: MarkdownChunk): List<MarkdownBlock> = remember(chunk) {
    parseMarkdownChunk(chunk.text)
}

@Composable
private fun rememberFileClickHandler(onFileClick: ((String) -> Unit)?): ((String) -> Unit)? {
    val current = rememberUpdatedState(onFileClick)
    return remember(onFileClick == null) {
        if (onFileClick == null) {
            null
        } else {
            { path: String -> current.value?.invoke(path) }
        }
    }
}

@Composable
private fun rememberLinkClickHandler(onLinkClick: ((String) -> Unit)?): ((String) -> Unit)? {
    val current = rememberUpdatedState(onLinkClick)
    return remember(onLinkClick == null) {
        if (onLinkClick == null) {
            null
        } else {
            { url: String -> current.value?.invoke(url) }
        }
    }
}

@Composable
private fun rememberCopyCodeHandler(onCopyCode: ((String) -> Unit)?): ((String) -> Unit)? {
    val current = rememberUpdatedState(onCopyCode)
    return remember(onCopyCode == null) {
        if (onCopyCode == null) {
            null
        } else {
            { code: String -> current.value?.invoke(code) }
        }
    }
}

@Composable
private fun rememberImageHandler(
    onImage: (@Composable (DdMarkdownImage) -> Unit)?,
): (@Composable (DdMarkdownImage) -> Unit)? {
    val current = rememberUpdatedState(onImage)
    return remember(onImage == null) {
        if (onImage == null) {
            null
        } else {
            @Composable { image: DdMarkdownImage -> current.value?.invoke(image) }
        }
    }
}

@Composable
private fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    document: MarkdownDocument,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
    live: Boolean,
    codeLabel: String,
    copyCodeLabel: String,
    onCopyCode: ((String) -> Unit)?,
    onImage: (@Composable (DdMarkdownImage) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(Modifier.height(blockGap(blocks[index - 1], block)))
            }
            MarkdownBlockItem(
                block = block,
                document = document,
                colors = colors,
                onFileClick = onFileClick,
                onLinkClick = onLinkClick,
                live = live,
                codeLabel = codeLabel,
                copyCodeLabel = copyCodeLabel,
                onCopyCode = onCopyCode,
                onImage = onImage,
            )
        }
    }
}

@Composable
private fun blockGap(previous: MarkdownBlock, current: MarkdownBlock): Dp = when {
    current is MarkdownBlock.Heading -> when (current.level) {
        1 -> 26.dp
        2 -> 22.dp
        else -> 18.dp
    }
    previous is MarkdownBlock.Heading -> 10.dp
    previous is MarkdownBlock.Code || current is MarkdownBlock.Code -> 16.dp
    previous is MarkdownBlock.Table || current is MarkdownBlock.Table -> 16.dp
    previous is MarkdownBlock.Quote || current is MarkdownBlock.Quote -> 14.dp
    current is MarkdownBlock.Rule || previous is MarkdownBlock.Rule -> 16.dp
    else -> 12.dp
}

@Composable
private fun MarkdownBlockItem(
    block: MarkdownBlock,
    document: MarkdownDocument,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
    live: Boolean,
    codeLabel: String,
    copyCodeLabel: String,
    onCopyCode: ((String) -> Unit)?,
    onImage: (@Composable (DdMarkdownImage) -> Unit)?,
) {
    when (block) {
        is MarkdownBlock.Heading -> InlineCodeText(
            rendered = rememberedInline(block.text, document, colors, onFileClick, onLinkClick),
            style = when (block.level) {
                1 -> mdHeading1
                2 -> mdHeading2
                else -> mdHeading3
            },
            color = colors.ink,
        )

        is MarkdownBlock.Paragraph -> InlineCodeText(
            rendered = rememberedInline(block.text, document, colors, onFileClick, onLinkClick),
            style = mdBody,
            color = colors.ink,
        )

        is MarkdownBlock.Code -> CodeBlock(block.language, block.code, colors, live, codeLabel, copyCodeLabel, onCopyCode)

        is MarkdownBlock.Quote -> QuoteBlock(
            block = block,
            document = document,
            colors = colors,
            onFileClick = onFileClick,
            onLinkClick = onLinkClick,
            live = live,
            codeLabel = codeLabel,
            copyCodeLabel = copyCodeLabel,
            onCopyCode = onCopyCode,
            onImage = onImage,
        )

        is MarkdownBlock.Image -> MarkdownImageBlock(block, onImage)

        is MarkdownBlock.ListBlock -> ListBlockBody(block.items, document, colors, onFileClick, onLinkClick, depth = 0)

        is MarkdownBlock.Table -> TableBlock(block, document, colors, onFileClick, onLinkClick, live)

        is MarkdownBlock.Math -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DdText(
                text = block.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.wash)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = DdType.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                ),
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
            )
        }

        MarkdownBlock.Rule -> DdHairline(Modifier.padding(vertical = DdSpacing.Xxs))
    }
}

@Composable
private fun MarkdownImageBlock(
    block: MarkdownBlock.Image,
    onImage: (@Composable (DdMarkdownImage) -> Unit)?,
) {
    val image = DdMarkdownImage(alt = block.alt, source = block.source, title = block.title)
    if (onImage != null) {
        onImage(image)
    } else {
        DdImage(
            path = block.source,
            contentDescription = image.alt.ifBlank { image.title.orEmpty() }.takeIf { it.isNotBlank() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )
    }
}

@Composable
private fun QuoteBlock(
    block: MarkdownBlock.Quote,
    document: MarkdownDocument,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
    live: Boolean,
    codeLabel: String,
    copyCodeLabel: String,
    onCopyCode: ((String) -> Unit)?,
    onImage: (@Composable (DdMarkdownImage) -> Unit)?,
) {
    val barColor = colors.accent.copy(alpha = 0.55f)
    val quoteShape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(quoteShape)
            .background(colors.wash.copy(alpha = 0.48f))
            .drawBehind {
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset.Zero,
                    size = Size(3.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                )
            }
            .padding(top = 8.dp, bottom = 8.dp, end = 12.dp),
    ) {
        MarkdownBlocks(
            blocks = block.blocks,
            document = document,
            colors = colors,
            onFileClick = onFileClick,
            onLinkClick = onLinkClick,
            live = live,
            codeLabel = codeLabel,
            copyCodeLabel = copyCodeLabel,
            onCopyCode = onCopyCode,
            onImage = onImage,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
        )
    }
}

@Composable
private fun FootnoteList(
    footnotes: List<MarkdownFootnote>,
    document: MarkdownDocument,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        DdHairline()
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            footnotes.forEach { footnote ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    DdText(
                        text = "${footnote.number}.",
                        modifier = Modifier.width(22.dp),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                    )
                    InlineCodeText(
                        rendered = rememberedInline(footnote.text, document, colors, onFileClick, onLinkClick),
                        modifier = Modifier.weight(1f),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(
    language: String?,
    code: String,
    colors: SuperPIColors,
    live: Boolean,
    codeLabel: String,
    copyCodeLabel: String,
    onCopyCode: ((String) -> Unit)?,
) {
    val plainText = remember(code) { AnnotatedString(code) }
    val highlighted by produceState(initialValue = plainText, code, language, colors, live) {
        if (live) {
            value = plainText
        } else {
            value = withContext(Dispatchers.Default) {
                highlightedCode(code, language, colors)
            }
        }
    }
    val displayText = if (!live && highlighted.text == code) highlighted else plainText
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DdShapes.Medium)
            .background(colors.codeBackground)
            .border(1.dp, colors.hairline.copy(alpha = 0.65f), DdShapes.Medium)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DdText(
                text = language?.uppercase().orEmpty().ifBlank { codeLabel },
                modifier = Modifier.weight(1f),
                style = DdType.micro,
                color = colors.inkTertiary,
            )
            if (onCopyCode != null) {
                DdIconButton(
                    icon = if (copied) DdIcons.Check else DdIcons.Copy,
                    onClick = {
                        onCopyCode(code)
                        copied = true
                    },
                    contentDescription = copyCodeLabel,
                    modifier = Modifier.testTag("markdown_code_copy"),
                    size = 44.dp,
                    iconSize = 15.dp,
                    tint = if (copied) colors.success else colors.inkTertiary,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(end = 8.dp).horizontalScroll(rememberScrollState())) {
            DdText(
                text = displayText,
                style = DdType.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                ),
                color = colors.ink,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ListBlockBody(
    items: List<MarkdownListItem>,
    document: MarkdownDocument,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
    depth: Int,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item -> ListItemBody(item, document, colors, onFileClick, onLinkClick, depth) }
    }
}

@Composable
private fun ListItemBody(
    item: MarkdownListItem,
    document: MarkdownDocument,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
    depth: Int,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            ListMarker(item.marker, colors, depth)
            InlineCodeText(
                rendered = rememberedInline(item.text, document, colors, onFileClick, onLinkClick),
                modifier = Modifier.weight(1f).padding(start = DdSpacing.Xs),
                style = mdBody,
                color = if (item.marker is MarkdownMarker.Task && item.marker.checked) {
                    colors.inkTertiary
                } else {
                    colors.ink
                },
            )
        }
        if (item.children.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(start = DdSpacing.Xl, top = DdSpacing.Xxs)) {
                ListBlockBody(item.children, document, colors, onFileClick, onLinkClick, depth + 1)
            }
        }
    }
}

@Composable
private fun ListMarker(marker: MarkdownMarker, colors: SuperPIColors, depth: Int) {
    when (marker) {
        MarkdownMarker.Bullet -> DdText(
            text = if (depth == 0) "•" else "◦",
            modifier = Modifier.width(18.dp),
            style = DdType.body,
            color = colors.inkSecondary.copy(alpha = if (depth == 0) 1f else 0.72f),
        )

        is MarkdownMarker.Ordered -> DdText(
            text = "${marker.number}.",
            modifier = Modifier.widthIn(min = 24.dp),
            style = DdType.body,
            color = colors.inkSecondary,
        )

        is MarkdownMarker.Task -> Box(Modifier.width(22.dp)) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (marker.checked) colors.accent else colors.wash)
                    .border(
                        width = 1.dp,
                        color = if (marker.checked) colors.accent else colors.hairline,
                        shape = RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (marker.checked) DdIcon(icon = DdIcons.Check, tint = colors.onAccent, size = 10.dp)
            }
        }
    }
}

@Composable
private fun TableBlock(
    block: MarkdownBlock.Table,
    document: MarkdownDocument,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
    live: Boolean,
) {
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headerStyle = tableHeaderStyle
    val cellStyle = tableCellStyle
    val headerFontPx = with(density) { headerStyle.fontSize.takeOrElse { 14.sp }.toPx() }
    val cellFontPx = with(density) { cellStyle.fontSize.takeOrElse { 14.sp }.toPx() }
    val renderedHeader = remember(
        block.header,
        document.links,
        document.footnoteNumbers,
        colors,
        onFileClick,
        onLinkClick,
    ) {
        block.header.map { renderInline(it, document, colors, onFileClick, onLinkClick) }
    }
    val renderedRows = remember(
        block.rows,
        document.links,
        document.footnoteNumbers,
        colors,
        onFileClick,
        onLinkClick,
    ) {
        block.rows.map { row -> row.map { renderInline(it, document, colors, onFileClick, onLinkClick) } }
    }
    val naturalWidths = remember(
        renderedHeader,
        renderedRows,
        headerStyle,
        cellStyle,
        headerFontPx,
        cellFontPx,
        textMeasurer,
        live,
    ) {
        if (live) {
            List(columns) { column ->
                var widest = estimateTextWidthPx(
                    renderedHeader.getOrNull(column)?.text?.text.orEmpty(),
                    headerFontPx,
                )
                renderedRows.forEach { row ->
                    val est = estimateTextWidthPx(
                        row.getOrNull(column)?.text?.text.orEmpty(),
                        cellFontPx,
                    )
                    if (est > widest) widest = est
                }
                widest
            }
        } else {
            List(columns) { column ->
                var widest = 0
                fun measure(rendered: RenderedInline?, style: TextStyle) {
                    if (rendered == null || rendered.text.isEmpty()) return
                    val measured = textMeasurer.measure(
                        text = rendered.text,
                        style = style,
                        overflow = TextOverflow.Clip,
                        softWrap = false,
                        maxLines = 1,
                    )
                    if (measured.size.width > widest) widest = measured.size.width
                }
                measure(renderedHeader.getOrNull(column), headerStyle)
                renderedRows.forEach { row -> measure(row.getOrNull(column), cellStyle) }
                widest
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontalPadding = 12.dp
        val verticalPadding = 8.dp
        val paddingPx = with(density) { (horizontalPadding * 2).roundToPx() }
        val minWidthPx = with(density) { 64.dp.roundToPx() }
        val maxWidthPx = with(density) { 240.dp.roundToPx() }
        val availablePx = constraints.maxWidth
        val clamped = naturalWidths.map { (it + paddingPx).coerceIn(minWidthPx, maxWidthPx) }
        val widths = distributeRemaining(clamped, naturalWidths, availablePx, maxWidthPx)
        val totalPx = widths.sum()
        val widthDps = widths.map { with(density) { it.toDp() } }
        val scrollState = rememberScrollState()
        Box(Modifier.fillMaxWidth().horizontalScroll(scrollState, enabled = totalPx > availablePx)) {
            Column(
                Modifier
                    .width(with(density) { totalPx.toDp() })
                    .clip(DdShapes.Medium)
                    .border(1.dp, colors.hairline, DdShapes.Medium),
            ) {
                TableRow(
                    renderedHeader,
                    widthDps,
                    colors,
                    header = true,
                    horizontalPadding,
                    verticalPadding,
                )
                DdHairline()
                renderedRows.forEachIndexed { index, row ->
                    TableRow(
                        row,
                        widthDps,
                        colors,
                        header = false,
                        horizontalPadding,
                        verticalPadding,
                    )
                    if (index != renderedRows.lastIndex) DdHairline()
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<RenderedInline>,
    widths: List<Dp>,
    colors: SuperPIColors,
    header: Boolean,
    horizontalPadding: Dp,
    verticalPadding: Dp,
) {
    val style = if (header) tableHeaderStyle else tableCellStyle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (header) Modifier.background(colors.wash) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        widths.forEachIndexed { index, width ->
            val cell = cells.getOrNull(index) ?: RenderedInline(AnnotatedString(""), emptyList())
            Box(Modifier.width(width).padding(horizontal = horizontalPadding, vertical = verticalPadding)) {
                InlineCodeText(
                    rendered = cell,
                    style = style,
                    color = colors.ink,
                )
            }
        }
    }
}

private fun distributeRemaining(
    base: List<Int>,
    natural: List<Int>,
    available: Int,
    maxWidth: Int,
): List<Int> {
    if (available <= 0 || available == Int.MAX_VALUE) return base
    val widths = base.toMutableList()
    var remaining = available - widths.sum()
    var passes = 0
    while (remaining > 0 && passes < 4) {
        val open = widths.indices.filter { widths[it] < maxWidth }
        if (open.isEmpty()) break
        val weight = open.sumOf { (natural[it] + 1).toDouble() }
        var used = 0
        open.forEach { index ->
            val share = ((natural[index] + 1) / weight * remaining).toInt()
            val add = minOf(share, maxWidth - widths[index])
            widths[index] += add
            used += add
        }
        if (used <= 0) break
        remaining -= used
        passes++
    }
    return widths
}

private fun estimateTextWidthPx(text: String, fontSizePx: Float): Int {
    if (text.isEmpty()) return 0
    var units = 0f
    for (ch in text) {
        units += if (ch.code > 0x2E7F) 1.0f else 0.56f
    }
    return (units * fontSizePx).toInt()
}

private data class RenderedInline(
    val text: AnnotatedString,
    val codeRanges: List<IntRange>,
)

@Composable
private fun InlineCodeText(
    rendered: RenderedInline,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (rendered.codeRanges.isEmpty()) {
        DdText(
            text = rendered.text,
            modifier = modifier,
            style = style,
            color = color,
        )
        return
    }
    val colors = superPiColors()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    DdText(
        text = rendered.text,
        modifier = modifier.drawBehind {
            val textLayout = layout ?: return@drawBehind
            if (rendered.codeRanges.isEmpty()) return@drawBehind
            if (textLayout.layoutInput.text != rendered.text) return@drawBehind
            val textLength = textLayout.layoutInput.text.length
            val horizontalPadding = 3.dp.toPx()
            val fontSize = style.fontSize.takeOrElse { 15.sp }.toPx()
            val ascent = fontSize * 0.84f
            val descent = fontSize * 0.32f
            val cornerRadius = CornerRadius(4.dp.toPx())
            rendered.codeRanges.forEach { range ->
                if (range.first < 0 || range.last >= textLength || range.first > range.last) return@forEach
                val firstLine = textLayout.getLineForOffset(range.first)
                val lastLine = textLayout.getLineForOffset(range.last)
                for (line in firstLine..lastLine) {
                    val start = maxOf(range.first, textLayout.getLineStart(line))
                    val end = minOf(range.last + 1, textLayout.getLineEnd(line, visibleEnd = true))
                    if (start >= end) continue
                    val startX = textLayout.getHorizontalPosition(start, usePrimaryDirection = true)
                    val endX = textLayout.getHorizontalPosition(end, usePrimaryDirection = true)
                    val left = minOf(startX, endX)
                    val right = maxOf(startX, endX)
                    val baseline = textLayout.getLineBaseline(line)
                    val top = baseline - ascent
                    val bottom = baseline + descent
                    drawRoundRect(
                        color = colors.inlineCodeBackground,
                        topLeft = Offset(left - horizontalPadding, top),
                        size = Size(right - left + horizontalPadding * 2, bottom - top),
                        cornerRadius = cornerRadius,
                    )
                }
            }
        },
        style = style,
        color = color,
        onTextLayout = { layout = it },
    )
}

@Composable
private fun rememberedInline(
    text: String,
    document: MarkdownDocument,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
): RenderedInline = remember(text, document.links, document.footnoteNumbers, colors, onFileClick, onLinkClick) {
    renderInline(text, document, colors, onFileClick, onLinkClick)
}

private fun renderInline(
    text: String,
    document: MarkdownDocument,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
): RenderedInline {
    val codeRanges = mutableListOf<IntRange>()
    val rendered = buildAnnotatedString {
        appendInlineNodes(
            nodes = inlineNodes(text, document.links, document.footnoteNumbers),
            colors = colors,
            onFileClick = onFileClick,
            onLinkClick = onLinkClick,
            codeRanges = codeRanges,
        )
    }
    return RenderedInline(rendered, codeRanges)
}

private fun AnnotatedString.Builder.appendFileReference(
    text: String,
    path: String,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit),
) {
    val annotation = LinkAnnotation.Clickable(
        tag = path,
        linkInteractionListener = object : LinkInteractionListener {
            override fun onClick(link: LinkAnnotation) {
                onFileClick(path)
            }
        },
    )
    withLink(annotation) {
        withStyle(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)) {
            append(text)
        }
    }
}

private fun AnnotatedString.Builder.appendInlineNodes(
    nodes: List<InlineNode>,
    colors: SuperPIColors,
    onFileClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
    codeRanges: MutableList<IntRange>,
) {
    nodes.forEach { node ->
        when (node) {
            is InlineNode.Text -> append(node.text)

            is InlineNode.Code -> {
                if (isPathLikeInlineCode(node.text)) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 0.88.em,
                            color = colors.inkSecondary,
                        ),
                    ) {
                        append(node.text)
                    }
                } else {
                    val start = length
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 0.88.em,
                            color = colors.inkSecondary,
                        ),
                    ) {
                        append(node.text)
                    }
                    if (start < length) codeRanges += start until length
                }
            }

            is InlineNode.Kbd -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    background = colors.wash,
                    color = colors.inkSecondary,
                ),
            ) {
                append(node.text)
            }

            is InlineNode.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                appendInlineNodes(node.children, colors, onFileClick, onLinkClick, codeRanges)
            }

            is InlineNode.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineNodes(node.children, colors, onFileClick, onLinkClick, codeRanges)
            }

            is InlineNode.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlineNodes(node.children, colors, onFileClick, onLinkClick, codeRanges)
            }

            is InlineNode.Link -> when {
                onFileClick != null && isWorkspaceFileReference(node.destination) -> {
                    val annotation = LinkAnnotation.Clickable(
                        tag = node.destination,
                        linkInteractionListener = object : LinkInteractionListener {
                            override fun onClick(link: LinkAnnotation) {
                                onFileClick(node.destination)
                            }
                        },
                    )
                    withLink(annotation) {
                        withStyle(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)) {
                            appendInlineNodes(node.children, colors, onFileClick, onLinkClick, codeRanges)
                        }
                    }
                }

                onLinkClick != null -> {
                    val annotation = LinkAnnotation.Clickable(
                        tag = node.destination,
                        linkInteractionListener = object : LinkInteractionListener {
                            override fun onClick(link: LinkAnnotation) {
                                onLinkClick(node.destination)
                            }
                        },
                    )
                    withLink(annotation) {
                        withStyle(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)) {
                            appendInlineNodes(node.children, colors, onFileClick, onLinkClick, codeRanges)
                        }
                    }
                }

                else -> withLink(LinkAnnotation.Url(node.destination)) {
                    withStyle(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)) {
                        appendInlineNodes(node.children, colors, onFileClick, onLinkClick, codeRanges)
                    }
                }
            }

            is InlineNode.FileRef -> {
                if (onFileClick == null) {
                    if (node.code || isPathLikeInlineCode(node.path)) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.inkSecondary)) {
                            append(node.text)
                        }
                    } else {
                        append(node.text)
                    }
                } else {
                    appendFileReference(node.text, node.path, colors, onFileClick)
                }
            }

            is InlineNode.Colored -> withStyle(SpanStyle(color = Color(node.color))) {
                appendInlineNodes(node.children, colors, onFileClick, onLinkClick, codeRanges)
            }

            is InlineNode.Superscript -> withStyle(SpanStyle(baselineShift = BaselineShift.Superscript)) {
                appendInlineNodes(node.children, colors, onFileClick, onLinkClick, codeRanges)
            }

            is InlineNode.Subscript -> withStyle(SpanStyle(baselineShift = BaselineShift.Subscript)) {
                appendInlineNodes(node.children, colors, onFileClick, onLinkClick, codeRanges)
            }

            is InlineNode.Math -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Italic,
                    color = colors.inkTertiary,
                ),
            ) {
                append(node.text)
            }

            is InlineNode.FootnoteRef -> withStyle(
                SpanStyle(color = colors.accent, baselineShift = BaselineShift.Superscript),
            ) {
                append("[${node.number}]")
            }
        }
    }
}

