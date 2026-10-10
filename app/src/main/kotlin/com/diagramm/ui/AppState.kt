package com.diagramm.ui

import com.diagramm.model.Collector
import com.diagramm.storage.AppsTreeLabels
import com.diagramm.storage.DeleteMode
import com.diagramm.storage.AppUsage
import com.diagramm.model.FileCategory
import com.diagramm.model.Node
import com.diagramm.storage.StorageQuota
import com.diagramm.storage.TrashEntry

enum class SourceType { LOCAL, GDRIVE, YANDEX, APPS }

data class SourceItem(
    val id: String,
    val type: SourceType,
    /** Volume description for removable local storage. */
    val label: String? = null,
    val isPrimary: Boolean = false,
    val rootPath: String? = null,
    val quota: StorageQuota? = null,
    val trashBytes: Long = 0,
    /** "Apps" only: total size found by the last visit, minus what was removed since (not recomputed live). */
    val summaryBytes: Long? = null,
    val connected: Boolean = true,
    val configured: Boolean = true,
)

enum class Screen { HOME, SCANNING, EXPLORER, TRASH }

data class ScanUi(
    val source: SourceItem,
    val files: Long = 0,
    val directories: Long = 0,
    val bytes: Long = 0,
    val currentPath: String = "",
)

/** What the "Apps" chart and list are sized and sorted by. */
enum class AppsSort { TOTAL, CACHE }

/** How the explorer shares the screen: chart above list (default), chart enlarged, or list enlarged. */
enum class Pane { SPLIT, CHART, LIST }

/** Extra state of the "Apps" explorer: the raw figures (single source of truth) and the by-cache view. */
data class AppsExplorer(
    val usages: List<AppUsage>,
    val labels: AppsTreeLabels,
    /** Apps sized by cache only; shown when sorting by cache. */
    val cacheRoot: Node,
    val sort: AppsSort = AppsSort.TOTAL,
)

data class ExplorerState(
    val source: SourceItem,
    val root: Node,
    /** The folder shown in the chart; [Node.pathFromRoot] gives the breadcrumbs. */
    val current: Node,
    val collector: Collector = Collector(),
    /** A file or folder the user tapped in the chart: shown in the info strip. */
    val focus: Node? = null,
    /** When set, the list shows the biggest files of this category instead of the folder's children. */
    val category: FileCategory? = null,
    val apps: AppsExplorer? = null,
    val pane: Pane = Pane.SPLIT,
)

data class TrashUi(val source: SourceItem, val entries: List<TrashEntry>)

data class AppState(
    val sources: List<SourceItem> = emptyList(),
    val screen: Screen = Screen.HOME,
    val scan: ScanUi? = null,
    val explorer: ExplorerState? = null,
    val trash: TrashUi? = null,
    val deleting: Boolean = false,
    /** Last deletion type picked in this run of the app; offered again next time. Not persisted on purpose. */
    val lastDeleteMode: DeleteMode = DeleteMode.TO_TRASH,
)

/** One-off events for the UI to show (snackbars). Text is resolved in the UI layer, which has resources. */
sealed interface UiMessage {
    data class ScanFailed(val detail: String?) : UiMessage
    data class SignInRequired(val type: SourceType) : UiMessage
    data class Deleted(val bytes: Long) : UiMessage
    data class DeleteFailed(val count: Int, val reason: String?) : UiMessage
    data class RestoreFailed(val reason: String?) : UiMessage
    data class ConnectFailed(val reason: String?) : UiMessage
}
