package com.diagramm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diagramm.R
import com.diagramm.chart.Arc
import com.diagramm.chart.Sunburst
import com.diagramm.chart.SunburstLayout
import com.diagramm.model.Collector
import com.diagramm.model.FileCategorizer
import com.diagramm.model.FileCategory
import com.diagramm.model.Node
import com.diagramm.model.NodeKind
import com.diagramm.model.NodeTags
import com.diagramm.storage.AppsTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private class ExplorerData(val sunburst: Sunburst, val totals: Map<FileCategory, Long>)

@Composable
fun ExplorerScreen(
    ex: ExplorerState,
    vm: MainViewModel,
    shizukuReady: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val palette = remember(dark) { ChartPalette(dark) }
    val fmt = rememberSizeFormatter()
    val split = rememberSizeSplitter()
    val current = ex.current
    val isLocal = ex.source.type == SourceType.LOCAL
    val isApps = ex.source.type == SourceType.APPS

    // "Apps" has two views of the same data at its root: the apps themselves and their cache.
    val apps = ex.apps
    val atAppsRoot = apps != null && current.parent == null
    val cacheTab = atAppsRoot && apps?.tab == AppsTab.CACHE
    val shown: Node = if (cacheTab && apps != null) apps.cacheRoot else current
    val shownCollector: Collector = if (cacheTab && apps != null) apps.cacheCollector else ex.collector
    val cacheTitle = stringResource(R.string.apps_cache_title)

    // Layout and category totals walk the whole subtree: keep them off the main thread.
    val data by produceState<ExplorerData?>(null, shown) {
        value = withContext(Dispatchers.Default) {
            ExplorerData(SunburstLayout.layout(shown), if (isApps) emptyMap() else FileCategorizer.totals(shown))
        }
    }
    val readyData = data?.takeIf { it.sunburst.root === shown }

    val flat by produceState<List<Node>?>(null, current, ex.category) {
        val category = ex.category
        value = if (category == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                current.walk().filter { it.isFile && FileCategorizer.of(it) == category }
                    .sortedByDescending { it.size }.take(300).toList()
            }
        }
    }

    // text shown inside sectors (resolved here because string resources need a composable scope)
    val freeLabel = stringResource(R.string.free_space)
    val hiddenLabel = stringResource(R.string.hidden_space)
    val labelText: (Node) -> String = remember(freeLabel, hiddenLabel) {
        { node ->
            when (node.kind) {
                NodeKind.FREE_SPACE -> freeLabel
                NodeKind.HIDDEN_SPACE -> hiddenLabel
                else -> node.name
            }
        }
    }

    val listItems: List<Node> = if (ex.category == null) shown.children else flat.orEmpty()

    // Package whose settings page the info strip can open (the focused part, or the app we are inside).
    val appPackage = if (isApps) AppsTree.packageOf((ex.focus ?: current).id) else null
    val summary = when {
        cacheTab -> stringResource(R.string.apps_count, shown.children.size) + " · " + fmt(shown.size)
        isApps && current.parent == null ->
            stringResource(R.string.apps_count, current.children.size) + " · " + fmt(current.usedSize)
        isApps -> fmt(current.usedSize)
        else -> stringResource(R.string.files_count, current.fileCount.toInt()) + " · " + fmt(current.usedSize)
    }

    // colour of each depth-1 child, shared between chart and list
    val colorById: Map<String, Color> = remember(readyData, palette) {
        val sb = readyData?.sunburst
        if (sb == null) emptyMap() else sb.arcsAt(1).mapNotNull { a ->
            a.node?.let { it.id to palette.arc(a) }
        }.toMap()
    }

    Column(modifier.fillMaxSize()) {
        Breadcrumbs(current.pathFromRoot(), onClick = { vm.navigateTo(it) })

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val boxHeight = maxHeight
            val landscape = maxWidth > boxHeight
            val chart: @Composable (Modifier) -> Unit = { m ->
                SunburstChart(
                    sunburst = data?.sunburst,
                    palette = palette,
                    collector = shownCollector,
                    focusId = ex.focus?.id,
                    centerTitle = fmt(shown.usedSize),
                    centerSubtitle = if (cacheTab) cacheTitle else nodeTitle(current),
                    labelText = labelText,
                    sizeText = fmt,
                    onArc = { arc -> onArcTapped(arc, vm) },
                    onCenter = { vm.navigateUp() },
                    modifier = m,
                )
            }
            val swipeToSwitch = if (atAppsRoot && apps != null) {
                Modifier.pointerInput(apps.tab) {
                    var total = 0f
                    val threshold = 64.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -threshold && apps.tab == AppsTab.APPS) vm.setAppsTab(AppsTab.CACHE)
                            else if (total > threshold && apps.tab == AppsTab.CACHE) vm.setAppsTab(AppsTab.APPS)
                        },
                        onHorizontalDrag = { _, dx -> total += dx },
                    )
                }
            } else {
                Modifier
            }
            // vertical swipe on the strip between chart and list: down enlarges the chart, up enlarges the list
            val dragPanes = Modifier.pointerInput(ex.pane) {
                var total = 0f
                val threshold = 40.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total > threshold -> vm.setPane(if (ex.pane == Pane.LIST) Pane.SPLIT else Pane.CHART)
                            total < -threshold -> vm.setPane(if (ex.pane == Pane.CHART) Pane.SPLIT else Pane.LIST)
                        }
                    },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
            // the strip itself: apps tabs, the "N files . size" line with its actions, cache hint, type chips
            val middle: @Composable () -> Unit = {
                if (atAppsRoot && apps != null) {
                    AppsTabs(apps.tab, ex.root.usedSize, apps.cacheRoot.size, fmt, onSelect = { vm.setAppsTab(it) })
                }
                InfoStrip(
                    current = shown,
                    focus = ex.focus,
                    collected = ex.focus?.let { shownCollector.covers(it) } ?: false,
                    isLocal = isLocal,
                    summary = summary,
                    fmt = fmt,
                    appPackage = appPackage,
                    onToggle = {
                        ex.focus?.let { if (cacheTab) vm.toggleCache(it) else vm.toggleCollected(it) }
                    },
                    onOpen = { ex.focus?.let { openNode(context, it, isLocal) } },
                    onAppSettings = { appPackage?.let { openAppSettings(context, it) } },
                )
                if (cacheTab && apps != null) {
                    CacheHeader(
                        empty = apps.cacheRoot.children.isEmpty(),
                        shizukuReady = shizukuReady,
                        onSelectAll = { vm.selectAllCache() },
                        onSetup = { vm.openSettings() },
                    )
                }
                if (!isApps) {
                    CategoryChips(readyData?.totals.orEmpty(), ex.category, fmt, onSelect = { vm.setCategory(it) })
                }
            }
            val list: @Composable (Modifier) -> Unit = { m ->
                AnimatedContent(
                    targetState = cacheTab,
                    modifier = m.then(swipeToSwitch),
                    transitionSpec = {
                        val towardsCache = targetState
                        (slideInHorizontally { if (towardsCache) it / 3 else -it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (towardsCache) -it / 3 else it / 3 } + fadeOut())
                    },
                    label = "apps-tab",
                ) { cache ->
                    val tree = if (cache && apps != null) apps.cacheRoot else current
                    val collector = if (cache && apps != null) apps.cacheCollector else ex.collector
                    val nodes = if (ex.category == null) tree.children else flat.orEmpty()
                    Column(Modifier.fillMaxSize()) {
                        SelectAllHeader(nodes, collector) { select ->
                            vm.setSelection(cache, nodes.filter { it.isCollectible }, select)
                        }
                        NodeList(
                            nodes = nodes,
                            parent = tree,
                            colorById = colorById,
                            palette = palette,
                            collector = collector,
                            showPath = ex.category != null,
                            showFileCount = !isApps,
                            fmt = fmt,
                            onClick = { node ->
                                when {
                                    node.isSynthetic -> vm.focus(node)
                                    node.isDirectory -> vm.navigateTo(node)
                                    else -> {
                                        vm.focus(node)
                                        openNode(context, node, isLocal)
                                    }
                                }
                            },
                            onToggle = { if (cache) vm.toggleCache(it) else vm.toggleCollected(it) },
                            onRangeSelect = { base, range, add -> vm.applyRange(cache, base, range, add) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    chart(Modifier.weight(1f).fillMaxSize())
                    Column(Modifier.weight(1f).fillMaxSize()) {
                        middle()
                        list(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            } else {
                val chartWeight by animateFloatAsState(
                    if (ex.pane == Pane.LIST) 0.001f else 1f, tween(300), label = "chart-weight",
                )
                val listWeight by animateFloatAsState(
                    if (ex.pane == Pane.CHART) 0.001f else 1f, tween(300), label = "list-weight",
                )
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(chartWeight).fillMaxWidth().clipToBounds()) {
                        if (chartWeight > 0.05f) chart(Modifier.fillMaxSize())
                    }
                    Column(dragPanes.then(swipeToSwitch)) {
                        PaneHandle()
                        middle()
                    }
                    Box(Modifier.weight(listWeight).fillMaxWidth().clipToBounds()) {
                        list(Modifier.fillMaxSize())
                    }
                }
            }
        }

        AnimatedVisibility(visible = !shownCollector.isEmpty) {
            if (cacheTab) {
                CacheBar(
                    collector = shownCollector,
                    split = split,
                    shizukuReady = shizukuReady,
                    onClear = { vm.clearCacheSelection() },
                    onClean = { vm.clearCaches() },
                    onSetup = { vm.openSettings() },
                )
            } else {
                CollectorBar(ex.collector, split, onClear = { vm.clearCollector() }, onDelete = onDelete)
            }
        }
    }
}

private fun onArcTapped(arc: Arc, vm: MainViewModel) {
    val node = arc.node
    when {
        node == null -> vm.focus(null)
        node.isDirectory && !node.isSynthetic -> vm.navigateTo(node)
        else -> vm.focus(node)
    }
}

@Composable
private fun Breadcrumbs(path: List<Node>, onClick: (Node) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(path.size) { listState.animateScrollToItem((path.size - 1).coerceAtLeast(0)) }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(path, key = { _, n -> n.id }) { index, node ->
            val last = index == path.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    nodeTitle(node),
                    maxLines = 1,
                    fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                    color = if (last) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !last) { onClick(node) }
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoStrip(
    current: Node,
    focus: Node?,
    collected: Boolean,
    isLocal: Boolean,
    summary: String,
    fmt: (Long) -> String,
    appPackage: String?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onAppSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (focus == null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (appPackage != null) {
                    TextButton(onClick = onAppSettings) { Text(stringResource(R.string.app_settings)) }
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(
                        nodeTitle(focus),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        fmt(focus.size) + " · " + percent(focus.size, current.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (appPackage != null) {
                    TextButton(onClick = onAppSettings) { Text(stringResource(R.string.app_settings)) }
                }
                if (focus.isCollectible) {
                    if (focus.isFile && (isLocal || focus.link != null)) {
                        TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
                    }
                    TextButton(onClick = onToggle) {
                        Text(stringResource(if (collected) R.string.remove_from_collector else R.string.add_to_collector))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChips(
    totals: Map<FileCategory, Long>,
    selected: FileCategory?,
    fmt: (Long) -> String,
    onSelect: (FileCategory?) -> Unit,
) {
    val shown = totals.filter { it.value > 0 && it.key != FileCategory.FOLDER }.entries.sortedByDescending { it.value }
    if (shown.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.cat_all)) })
        }
        items(shown, key = { it.key.name }) { entry ->
            FilterChip(
                selected = selected == entry.key,
                onClick = { onSelect(if (selected == entry.key) null else entry.key) },
                label = { Text(categoryLabel(entry.key) + " · " + fmt(entry.value)) },
            )
        }
    }
}

/** State of one long-press-and-drag selection, kept outside Compose state: it changes on every pointer event. */
private class DragSelection {
    var base: Collector? = null
    var adding = true
    var startIndex = -1
    var lastIndex = -1
}

@Composable
private fun NodeList(
    nodes: List<Node>,
    parent: Node,
    colorById: Map<String, Color>,
    palette: ChartPalette,
    collector: Collector,
    showPath: Boolean,
    showFileCount: Boolean,
    fmt: (Long) -> String,
    onClick: (Node) -> Unit,
    onToggle: (Node) -> Unit,
    onRangeSelect: (base: Collector, range: List<Node>, add: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nodes.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.empty_folder), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val currentNodes by rememberUpdatedState(nodes)
    val currentCollector by rememberUpdatedState(collector)
    val currentRangeSelect by rememberUpdatedState(onRangeSelect)
    val selection = remember { DragSelection() }

    /** Index of the list row under [y] (viewport coordinates), or null. */
    fun rowAt(y: Float): Int? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index

    LazyColumn(
        state = listState,
        modifier = modifier.pointerInput(Unit) {
            val edge = 56.dp.toPx()
            awaitEachGesture {
                // Long press starts a selection that follows the finger; nothing is consumed before that, so
                // taps and normal scrolling keep working.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

                val first = rowAt(down.position.y)
                val node = first?.let { currentNodes.getOrNull(it) }
                val c = currentCollector
                if (first == null || node == null || !node.isCollectible || (c.covers(node) && !c.contains(node))) {
                    return@awaitEachGesture
                }
                selection.base = c
                selection.adding = !c.contains(node) // starting on a selected row means "un-select by dragging"
                selection.startIndex = first
                selection.lastIndex = first
                currentRangeSelect(c, listOf(node), selection.adding)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                // From here on the rows must not see the pointer (no click on release): consume on the way down.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (change.changedToUpIgnoreConsumed() || !change.pressed) break

                    val y = change.position.y
                    val viewportHeight = listState.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
                    if (y < edge) listState.dispatchRawDelta(-18f) else if (y > viewportHeight - edge) listState.dispatchRawDelta(18f)

                    val row = rowAt(y)
                    if (row != null && row != selection.lastIndex) {
                        selection.lastIndex = row
                        val lo = minOf(selection.startIndex, row)
                        val hi = maxOf(selection.startIndex, row)
                        val nodesNow = currentNodes
                        if (lo in nodesNow.indices && hi in nodesNow.indices) {
                            // base + (or -) everything between the first row and the finger: moving back undoes it
                            currentRangeSelect(c, nodesNow.subList(lo, hi + 1), selection.adding)
                        }
                    }
                }
                selection.base = null
            }
        },
    ) {
        items(nodes, key = { it.id }) { node ->
            val contained = collector.contains(node)
            val covered = contained || (node.isCollectible && collector.covers(node))
            NodeRow(
                node = node,
                color = colorById[node.id] ?: palette.smallObjects,
                parentSize = parent.size,
                checked = covered,
                checkEnabled = node.isCollectible && (contained || !covered),
                showPath = showPath,
                showFileCount = showFileCount,
                fmt = fmt,
                onClick = { onClick(node) },
                onToggle = { onToggle(node) },
            )
        }
    }
}

/**
 * Appears once something is selected: one tri-state checkbox, in the column of the row checkboxes,
 * that selects (or un-selects) everything in the list below.
 */
@Composable
private fun SelectAllHeader(nodes: List<Node>, collector: Collector, onSelectAll: (Boolean) -> Unit) {
    val selectable = nodes.filter { it.isCollectible }
    if (collector.isEmpty || selectable.isEmpty()) return
    val selected = selectable.count { collector.covers(it) }
    val state = when (selected) {
        0 -> ToggleableState.Off
        selectable.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.select_all),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.select_all_count, selected, selectable.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TriStateCheckbox(state = state, onClick = { onSelectAll(state != ToggleableState.On) })
        }
    }
}

/** A small grip on the strip: the strip can be dragged down (bigger chart) or up (bigger list). */
@Composable
private fun PaneHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
        )
    }
}

@Composable
private fun NodeRow(
    node: Node,
    color: Color,
    parentSize: Long,
    checked: Boolean,
    checkEnabled: Boolean,
    showPath: Boolean,
    showFileCount: Boolean,
    fmt: (Long) -> String,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(nodeTitle(node), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = when {
                node.accessDenied -> stringResource(R.string.access_denied)
                showPath -> node.parent?.let { nodeTitle(it) }.orEmpty() + " · " + percent(node.size, parentSize)
                node.tag == NodeTags.SYSTEM_APP -> stringResource(R.string.system_app) + " · " + percent(node.size, parentSize)
                node.isDirectory && showFileCount ->
                    stringResource(R.string.files_count, node.fileCount.toInt()) + " · " + percent(node.size, parentSize)
                else -> percent(node.size, parentSize)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.accessDenied) {
                    Icon(Icons.Default.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(fmt(node.size), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (!node.isCollectible) {
            Spacer(Modifier.width(48.dp))
        } else {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = checkEnabled)
        }
    }
}

@Composable
private fun CollectorBar(
    collector: Collector,
    split: (Long) -> Pair<String, String>,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    val (value, unit) = split(collector.totalSize)
    Surface(
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(unit + " " + stringResource(R.string.collected), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${collector.items.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear)) }
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.delete)) }
        }
    }
}

private fun percent(part: Long, whole: Long): String {
    if (whole <= 0) return ""
    val p = part * 100.0 / whole
    return if (p < 1.0) "<1%" else "${p.roundToInt()}%"
}

@Composable
private fun AppsTabs(
    tab: AppsTab,
    appsSize: Long,
    cacheSize: Long,
    fmt: (Long) -> String,
    onSelect: (AppsTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = tab.ordinal,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Tab(
            selected = tab == AppsTab.APPS,
            onClick = { onSelect(AppsTab.APPS) },
            text = { TabLabel(stringResource(R.string.apps_tab_apps), fmt(appsSize)) },
        )
        Tab(
            selected = tab == AppsTab.CACHE,
            onClick = { onSelect(AppsTab.CACHE) },
            text = { TabLabel(stringResource(R.string.apps_tab_cache), fmt(cacheSize)) },
        )
    }
}

@Composable
private fun TabLabel(title: String, size: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(size, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Explains what clearing a cache means, offers "select all", and says so when Shizuku is not ready. */
@Composable
private fun CacheHeader(
    empty: Boolean,
    shizukuReady: Boolean,
    onSelectAll: () -> Unit,
    onSetup: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (empty) R.string.cache_empty else R.string.cache_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!empty) TextButton(onClick = onSelectAll) { Text(stringResource(R.string.cache_select_all)) }
            }
            if (!shizukuReady && !empty) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.cache_need_shizuku),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSetup) { Text(stringResource(R.string.cache_setup)) }
                }
            }
        }
    }
}

/** The collector bar of the cache tab: calm primary colour, because clearing a cache loses nothing. */
@Composable
private fun CacheBar(
    collector: Collector,
    split: (Long) -> Pair<String, String>,
    shizukuReady: Boolean,
    onClear: () -> Unit,
    onClean: () -> Unit,
    onSetup: () -> Unit,
) {
    val (value, unit) = split(collector.totalSize)
    Surface(
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(unit + " " + stringResource(R.string.cache_selected), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.apps_count, collector.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear)) }
            if (shizukuReady) {
                Button(onClick = onClean) { Text(stringResource(R.string.cache_clear)) }
            } else {
                FilledTonalButton(onClick = onSetup) { Text(stringResource(R.string.cache_setup)) }
            }
        }
    }
}
