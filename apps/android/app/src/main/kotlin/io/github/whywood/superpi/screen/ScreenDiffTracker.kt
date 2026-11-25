package io.github.whywood.superpi.screen

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal data class NodeDiffResult(
    val added: List<GuiSnapshotNode>,
    val removed: List<GuiSnapshotNode>,
    val updated: List<Pair<GuiSnapshotNode, GuiSnapshotNode>>,
    val focusChanged: Boolean,
)

internal class ScreenDiffTracker {
    private data class Baseline(
        val snapshotId: String,
        val scopeKey: String,
        val windowKey: String,
        val packageName: String?,
        val scanLimits: Pair<Int, Int>,
        val snapshot: GuiSnapshot,
        val diffStreak: Int,
    )

    private val lock = Any()
    private val snapshotSeq = AtomicLong()
    private var baseline: Baseline? = null

    fun reset() = synchronized(lock) {
        baseline = null
    }

    fun lastBaseline(scopeKey: String): GuiSnapshot? = synchronized(lock) {
        val current = baseline ?: return null
        if (current.scopeKey != scopeKey) return null
        current.snapshot
    }

    fun formatObserve(
        observed: ScreenObserveResult,
        scopeKey: String,
        scanLimits: Pair<Int, Int>,
        filters: List<GuiSelector> = emptyList(),
        limit: Int? = null,
        forceFull: Boolean = false,
        displayBounds: GuiBounds? = null,
    ): JsonObject {
        val curr = observed.snapshot
        if (filters.isNotEmpty() || limit != null) {
            val base = observeJson(observed, filters, limit)
            val sparse = filters.isEmpty() && isTreeSparse(curr, displayBounds)
            return attachFullMetadata(base, nextSnapshotId(), sparse)
        }

        val windowKey = windowKeyOf(curr)
        return synchronized(lock) {
            val prev = baseline
            if (
                prev != null &&
                !forceFull &&
                prev.scopeKey == scopeKey &&
                prev.windowKey == windowKey &&
                prev.packageName == curr.packageName &&
                prev.scanLimits == scanLimits &&
                !prev.snapshot.truncated &&
                !curr.truncated &&
                prev.diffStreak < MAX_DIFF_STREAK
            ) {
                val diff = diffNodes(prev.snapshot, curr)
                val diffPayloadCount = diff.added.size + diff.updated.size
                val diffTooLarge = curr.nodes.size >= DIFF_GUARD_MIN_NODES &&
                    diffPayloadCount * 100 > curr.nodes.size * DIFF_GUARD_MAX_PERCENT
                if (!diffTooLarge) {
                    val snapshotId = nextSnapshotId()
                    val sparse = isTreeSparse(curr, displayBounds)
                    val changed = diff.added.isNotEmpty() || diff.removed.isNotEmpty() || diff.updated.isNotEmpty()
                    baseline = Baseline(
                        snapshotId = snapshotId,
                        scopeKey = scopeKey,
                        windowKey = windowKey,
                        packageName = curr.packageName,
                        scanLimits = scanLimits,
                        snapshot = curr,
                        diffStreak = prev.diffStreak + 1,
                    )
                    return@synchronized buildJsonObject {
                        put("mode", "diff")
                        put("snapshotId", snapshotId)
                        put("baseSnapshotId", prev.snapshotId)
                        curr.packageName?.let { put("package", it) }
                        curr.windowTitle?.let { put("title", it) }
                        put("changed", changed)
                        put("count", curr.nodes.size)
                        put("scanned", curr.scanned)
                        put("complete", true)
                        put("truncated", false)
                        put("filtered", observed.filteredNodes)
                        put("added", buildJsonArray { diff.added.forEach { add(GuiNodeTree.nodeJson(it)) } })
                        put("removed", buildJsonArray { diff.removed.forEach { add(JsonPrimitive(it.ref)) } })
                        put(
                            "updated",
                            buildJsonArray {
                                diff.updated.forEach { (beforeNode, afterNode) ->
                                    add(
                                        JsonObject(
                                            GuiNodeTree.nodeJson(afterNode) +
                                                ("prevRef" to JsonPrimitive(beforeNode.ref)),
                                        ),
                                    )
                                }
                            },
                        )
                        if (curr.windows.size > 1) {
                            putJsonArray("windows") { curr.windows.forEach { add(windowJson(it)) } }
                        }
                        if (sparse) {
                            put("treeSparse", true)
                            put("hint", SPARSE_TREE_HINT)
                        }
                    }
                }
            }

            val snapshotId = nextSnapshotId()
            val sparse = isTreeSparse(curr, displayBounds)
            baseline = if (curr.truncated) {
                null
            } else {
                Baseline(
                    snapshotId = snapshotId,
                    scopeKey = scopeKey,
                    windowKey = windowKey,
                    packageName = curr.packageName,
                    scanLimits = scanLimits,
                    snapshot = curr,
                    diffStreak = 0,
                )
            }
            attachFullMetadata(observeJson(observed), snapshotId, sparse)
        }
    }

    private fun attachFullMetadata(base: JsonObject, snapshotId: String, sparse: Boolean): JsonObject {
        val entries = LinkedHashMap<String, JsonElement>(base.size + 3)
        base.forEach { (k, v) -> entries[k] = v }
        entries["mode"] = JsonPrimitive("full")
        entries["snapshotId"] = JsonPrimitive(snapshotId)
        if (sparse) {
            entries["treeSparse"] = JsonPrimitive(true)
            val existingHint = (entries["hint"] as? JsonPrimitive)?.content
            entries["hint"] = JsonPrimitive(
                if (existingHint.isNullOrBlank()) SPARSE_TREE_HINT else "$existingHint; $SPARSE_TREE_HINT",
            )
        }
        return JsonObject(entries)
    }

    private fun nextSnapshotId(): String = "s${snapshotSeq.incrementAndGet()}"

    companion object {
        internal const val MAX_DIFF_STREAK = 5
        internal const val SPARSE_MAX_TOTAL_NODES = 6
        internal const val SPARSE_MIN_MEANINGFUL_NODES = 3
        private const val DIFF_GUARD_MIN_NODES = 6
        private const val DIFF_GUARD_MAX_PERCENT = 65
        internal const val SPARSE_TREE_HINT =
            "Node tree is sparse or custom-rendered; use screen_screenshot if visual confirmation is needed"

        internal fun windowKeyOf(snapshot: GuiSnapshot): String =
            snapshot.windows.firstOrNull { it.selected }?.let { win ->
                "${win.id}:${win.packageName.orEmpty()}:${win.title.orEmpty()}"
            } ?: "${snapshot.packageName.orEmpty()}:${snapshot.windowTitle.orEmpty()}"

        internal fun isTreeSparse(snapshot: GuiSnapshot, displayBounds: GuiBounds?): Boolean {
            if (displayBounds == null || displayBounds.width <= 0 || displayBounds.height <= 0) return false
            if (snapshot.truncated) return false
            if (snapshot.nodes.size > SPARSE_MAX_TOTAL_NODES) return false
            val meaningful = snapshot.nodes.count { it.isMeaningful() }
            return meaningful < SPARSE_MIN_MEANINGFUL_NODES
        }

        internal fun diffNodes(before: GuiSnapshot, after: GuiSnapshot): NodeDiffResult {
            val focusBefore = before.nodes.firstOrNull { it.focused }?.signatureSignatureWithoutBounds()
            val focusAfter = after.nodes.firstOrNull { it.focused }?.signatureSignatureWithoutBounds()
            val focusChanged = focusBefore != focusAfter

            val remainingAfter = after.nodes.toMutableList()
            val unmatchedBefore = mutableListOf<GuiSnapshotNode>()

            val afterByKey = HashMap<NodeSignature, ArrayDeque<GuiSnapshotNode>>()
            for (node in remainingAfter) {
                afterByKey.getOrPut(node.structuralSignature()) { ArrayDeque() }.addLast(node)
            }
            val matchedIdenticalAfter = HashSet<GuiSnapshotNode>(minOf(before.nodes.size, after.nodes.size))
            for (prev in before.nodes) {
                val queue = afterByKey[prev.structuralSignature()]
                val hit = if (queue != null && queue.isNotEmpty()) queue.removeFirst() else null
                if (hit != null) {
                    matchedIdenticalAfter += hit
                } else {
                    unmatchedBefore += prev
                }
            }
            val unmatchedAfter = remainingAfter.filterTo(mutableListOf()) { it !in matchedIdenticalAfter }

            val updated = mutableListOf<Pair<GuiSnapshotNode, GuiSnapshotNode>>()

            pairRemaining(unmatchedBefore, unmatchedAfter, updated) { prev, curr ->
                !prev.resourceId.isNullOrEmpty() &&
                    prev.resourceId == curr.resourceId &&
                    prev.className == curr.className
            }

            pairRemaining(unmatchedBefore, unmatchedAfter, updated) { prev, curr ->
                prev.className == curr.className &&
                    prev.depth == curr.depth &&
                    boundsOverlap(prev.bounds, curr.bounds) >= 0.5f
            }

            return NodeDiffResult(
                added = unmatchedAfter,
                removed = unmatchedBefore,
                updated = updated,
                focusChanged = focusChanged,
            )
        }

        private fun pairRemaining(
            beforePool: MutableList<GuiSnapshotNode>,
            afterPool: MutableList<GuiSnapshotNode>,
            out: MutableList<Pair<GuiSnapshotNode, GuiSnapshotNode>>,
            compatible: (GuiSnapshotNode, GuiSnapshotNode) -> Boolean,
        ) {
            val it = beforePool.iterator()
            while (it.hasNext()) {
                val prev = it.next()
                var bestIndex = -1
                var bestOverlap = -1f
                var bestDistance = Int.MAX_VALUE
                for (i in afterPool.indices) {
                    val curr = afterPool[i]
                    if (!compatible(prev, curr)) continue
                    val overlap = boundsOverlap(prev.bounds, curr.bounds)
                    val distance = abs(prev.bounds.centerX - curr.bounds.centerX) +
                        abs(prev.bounds.centerY - curr.bounds.centerY)
                    if (overlap > bestOverlap || (overlap == bestOverlap && distance < bestDistance)) {
                        bestIndex = i
                        bestOverlap = overlap
                        bestDistance = distance
                    }
                }
                if (bestIndex >= 0) {
                    out += prev to afterPool.removeAt(bestIndex)
                    it.remove()
                }
            }
        }

        private fun boundsOverlap(a: GuiBounds, b: GuiBounds): Float {
            val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (w <= 0 || h <= 0) return 0f
            val intersection = w.toLong() * h
            val smaller = minOf(a.width.toLong() * a.height, b.width.toLong() * b.height)
            if (smaller <= 0L) return 0f
            return (intersection.toDouble() / smaller).toFloat()
        }

        private fun GuiSnapshotNode.isMeaningful(): Boolean =
            !text.isNullOrBlank() ||
                !desc.isNullOrBlank() ||
                !placeholder.isNullOrBlank() ||
                clickable ||
                editable ||
                scrollable

        private data class NodeSignature(
            val className: String,
            val resourceId: String?,
            val text: String?,
            val desc: String?,
            val placeholder: String?,
            val bounds: GuiBounds,
            val clickable: Boolean,
            val scrollable: Boolean,
            val editable: Boolean,
            val enabled: Boolean,
            val checked: Boolean,
            val selected: Boolean,
            val focused: Boolean,
            val showingHint: Boolean,
            val secret: Boolean,
            val depth: Int,
        )

        private fun GuiSnapshotNode.structuralSignature() = NodeSignature(
            className = className,
            resourceId = resourceId,
            text = if (secret) null else text,
            desc = desc,
            placeholder = placeholder,
            bounds = bounds,
            clickable = clickable,
            scrollable = scrollable,
            editable = editable,
            enabled = enabled,
            checked = checked,
            selected = selected,
            focused = focused,
            showingHint = showingHint,
            secret = secret,
            depth = depth,
        )

        private fun GuiSnapshotNode.signatureSignatureWithoutBounds(): String =
            "$className|${resourceId.orEmpty()}|${bounds.format()}"
    }
}
