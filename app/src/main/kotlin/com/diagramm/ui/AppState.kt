package com.diagramm.ui

import com.diagramm.model.Collector
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
)

data class TrashUi(val source: SourceItem, val entries: List<TrashEntry>)

data class AppState(
    val sources: List<SourceItem> = emptyList(),
    val screen: Screen = Screen.HOME,
    val scan: ScanUi? = null,
    val explorer: ExplorerState? = null,
    val trash: TrashUi? = null,
    val deleting: Boolean = false,
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
