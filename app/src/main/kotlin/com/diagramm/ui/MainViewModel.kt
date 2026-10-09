package com.diagramm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.diagramm.AppContainer
import com.diagramm.DiagrammApp
import com.diagramm.model.Collector
import com.diagramm.model.FileCategory
import com.diagramm.model.Node
import com.diagramm.model.NodeKind
import com.diagramm.net.AuthRequiredException
import com.diagramm.storage.DeleteMode
import com.diagramm.storage.DeleteResult
import com.diagramm.storage.DuplicateFinder
import com.diagramm.storage.DuplicateGroup
import com.diagramm.storage.LocalContentHasher
import com.diagramm.storage.ScanProgress
import com.diagramm.storage.StorageProvider
import com.diagramm.storage.StorageQuota
import com.diagramm.storage.LocalTrash
import com.diagramm.storage.TrashEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var scanJob: Job? = null
    private var duplicatesJob: Job? = null

    init {
        refreshSources()
    }

    // ---- sources -------------------------------------------------------------------------------

    fun refreshSources() {
        val locals = container.localVolumes().map { v ->
            SourceItem(
                id = "local:${v.root.path}",
                type = SourceType.LOCAL,
                label = v.label,
                isPrimary = v.isPrimary,
                rootPath = v.root.path,
                quota = StorageQuota(v.root.totalSpace, (v.root.totalSpace - v.root.freeSpace).coerceAtLeast(0)),
            )
        }
        val google = SourceItem("gdrive", SourceType.GDRIVE, connected = container.googleAuth.connected.value)
        val yandex = SourceItem(
            "yandex", SourceType.YANDEX,
            connected = container.yandexAuth.connected.value,
            configured = container.yandexAuth.isConfigured,
        )
        _state.update { it.copy(sources = locals + google + yandex) }

        viewModelScope.launch {
            // Trash sizes of local volumes
            for (s in locals) {
                val bytes = withContext(Dispatchers.IO) { container.trashFor(File(s.rootPath!!)).totalSize() }
                if (bytes > 0) updateSource(s.id) { it.copy(trashBytes = bytes) }
            }
        }
        // Cloud quotas, best effort
        for (s in listOf(google, yandex)) {
            if (!s.connected || !s.configured) continue
            viewModelScope.launch {
                try {
                    val q = withContext(Dispatchers.Default) { providerFor(s).quota() }
                    updateSource(s.id) { it.copy(quota = q) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthRequiredException) {
                    disconnectInternal(s.type)
                } catch (e: Exception) {
                    // quota is optional decoration; scanning will report real problems
                }
            }
        }
    }

    private fun updateSource(id: String, transform: (SourceItem) -> SourceItem) {
        _state.update { st -> st.copy(sources = st.sources.map { if (it.id == id) transform(it) else it }) }
    }

    fun disconnect(type: SourceType) {
        disconnectInternal(type)
    }

    private fun disconnectInternal(type: SourceType) {
        when (type) {
            SourceType.GDRIVE -> container.googleAuth.disconnect()
            SourceType.YANDEX -> container.yandexAuth.disconnect()
            SourceType.LOCAL -> Unit
        }
        refreshSources()
    }

    fun report(message: UiMessage) {
        _messages.tryEmit(message)
    }

    private fun providerFor(source: SourceItem): StorageProvider = when (source.type) {
        SourceType.LOCAL -> container.localProvider(source.id, source.label.orEmpty(), File(source.rootPath!!))
        SourceType.GDRIVE -> container.googleProvider()
        SourceType.YANDEX -> container.yandexProvider()
    }

    // ---- scanning ------------------------------------------------------------------------------

    fun startScan(source: SourceItem) {
        scanJob?.cancel()
        _state.update { it.copy(screen = Screen.SCANNING, scan = ScanUi(source), explorer = null) }
        scanJob = viewModelScope.launch {
            val progress = ScanProgress()
            val ticker = launch {
                while (true) {
                    _state.update { s ->
                        if (s.screen != Screen.SCANNING) {
                            s
                        } else {
                            s.copy(
                                scan = ScanUi(
                                    source, progress.files.get(), progress.directories.get(),
                                    progress.bytes.get(), progress.currentPath,
                                ),
                            )
                        }
                    }
                    delay(150)
                }
            }
            try {
                val tree = withContext(Dispatchers.Default) { providerFor(source).scan(progress) }
                _state.update {
                    it.copy(screen = Screen.EXPLORER, scan = null, explorer = ExplorerState(source, tree, tree))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                disconnectInternal(source.type)
                _messages.tryEmit(UiMessage.SignInRequired(source.type))
                toHome()
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage.ScanFailed(e.message ?: e.javaClass.simpleName))
                toHome()
            } finally {
                ticker.cancel()
            }
        }
    }

    fun rescan() {
        _state.value.explorer?.source?.let { startScan(it) }
    }

    private fun toHome() {
        _state.update { it.copy(screen = Screen.HOME, scan = null, explorer = null, duplicates = null, trash = null) }
        refreshSources()
    }

    // ---- navigation ----------------------------------------------------------------------------

    /** Handles the system back button / gesture. Returns false if the app should close. */
    fun onBack(): Boolean {
        val st = _state.value
        when (st.screen) {
            Screen.HOME -> return false
            Screen.SCANNING -> {
                scanJob?.cancel()
                toHome()
            }
            Screen.EXPLORER -> {
                val ex = st.explorer
                val parent = ex?.current?.parent
                if (ex != null && parent != null) navigateTo(parent) else toHome()
            }
            Screen.DUPLICATES -> {
                duplicatesJob?.cancel()
                _state.update { it.copy(screen = Screen.EXPLORER, duplicates = null) }
            }
            Screen.TRASH -> toHome()
        }
        return true
    }

    fun closeExplorer() = toHome()

    private fun updateExplorer(transform: (ExplorerState) -> ExplorerState) {
        _state.update { st -> st.explorer?.let { st.copy(explorer = transform(it)) } ?: st }
    }

    fun navigateTo(node: Node) {
        if (!node.isDirectory) return
        updateExplorer { it.copy(current = node, focus = null, category = null) }
    }

    fun navigateUp() {
        _state.value.explorer?.current?.parent?.let { navigateTo(it) }
    }

    fun focus(node: Node?) = updateExplorer { it.copy(focus = node) }

    fun setCategory(category: FileCategory?) = updateExplorer { it.copy(category = category, focus = null) }

    // ---- collector & deletion ------------------------------------------------------------------

    fun toggleCollected(node: Node) = updateExplorer { it.copy(collector = it.collector.toggle(node)) }

    fun clearCollector() = updateExplorer { it.copy(collector = it.collector.cleared()) }

    fun delete(mode: DeleteMode) {
        val ex = _state.value.explorer ?: return
        val items = ex.collector.items
        if (items.isEmpty() || _state.value.deleting) return
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            val result: DeleteResult = try {
                withContext(Dispatchers.Default) { providerFor(ex.source).delete(items, mode) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                disconnectInternal(ex.source.type)
                DeleteResult(emptySet(), items.associate { it.id to "sign-in required" })
            } catch (e: Exception) {
                DeleteResult(emptySet(), items.associate { it.id to (e.message ?: e.javaClass.simpleName) })
            }

            val freed = items.filter { it.id in result.deletedIds }.sumOf { it.size }
            var newRoot = ex.root.removing(result.deletedIds)
            if (mode == DeleteMode.PERMANENT) newRoot = newRoot.withMoreFreeSpace(freed)
            val newCurrent = newRoot.findById(ex.current.id) ?: newRoot
            var collector = Collector()
            for (id in result.failures.keys) newRoot.findById(id)?.let { collector = collector.plus(it) }

            _state.update {
                it.copy(
                    deleting = false,
                    explorer = ex.copy(root = newRoot, current = newCurrent, collector = collector, focus = null),
                )
            }
            if (result.deletedIds.isNotEmpty()) _messages.tryEmit(UiMessage.Deleted(freed))
            if (result.failures.isNotEmpty()) {
                _messages.tryEmit(UiMessage.DeleteFailed(result.failures.size, result.failures.values.firstOrNull()))
            }
        }
    }

    private fun Node.withMoreFreeSpace(bytes: Long): Node {
        if (bytes <= 0) return this
        val free = children.firstOrNull { it.kind == NodeKind.FREE_SPACE } ?: return this
        return withChildren(children - free + Node.freeSpace(free.size + bytes))
    }

    // ---- duplicates ----------------------------------------------------------------------------

    fun findDuplicates() {
        val ex = _state.value.explorer ?: return
        duplicatesJob?.cancel()
        _state.update { it.copy(screen = Screen.DUPLICATES, duplicates = DuplicatesUi.Loading) }
        duplicatesJob = viewModelScope.launch {
            val hasher = if (ex.source.type == SourceType.LOCAL) LocalContentHasher() else null
            val groups = try {
                withContext(Dispatchers.Default) { DuplicateFinder(hasher).find(ex.current, minSizeBytes = 1024) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { it.copy(duplicates = DuplicatesUi.Ready(groups)) }
        }
    }

    /** Puts every copy except the oldest one of each group into the collector and returns to the chart. */
    fun selectExtraCopies(groups: List<DuplicateGroup>) {
        updateExplorer { ex ->
            var c = ex.collector
            for (g in groups) {
                val keep = g.nodes.minWithOrNull(compareBy<Node> { if (it.modifiedMillis > 0) it.modifiedMillis else Long.MAX_VALUE }.thenBy { it.id.length })
                for (n in g.nodes) if (n !== keep) c = c.plus(n)
            }
            ex.copy(collector = c)
        }
        _state.update { it.copy(screen = Screen.EXPLORER, duplicates = null) }
    }

    // ---- local trash ---------------------------------------------------------------------------

    fun openTrash(source: SourceItem) {
        _state.update { it.copy(screen = Screen.TRASH, trash = TrashUi(source, emptyList())) }
        reloadTrash(source)
    }

    private fun reloadTrash(source: SourceItem) {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { container.trashFor(File(source.rootPath!!)).list() }
            _state.update { if (it.screen == Screen.TRASH) it.copy(trash = TrashUi(source, entries)) else it }
        }
    }

    fun restore(entry: TrashEntry) = trashAction { trash -> trash.restore(entry) }

    fun purge(entry: TrashEntry) = trashAction { trash -> trash.purge(entry) }

    fun emptyTrash() = trashAction { trash -> trash.empty() }

    private fun trashAction(action: (LocalTrash) -> Unit) {
        val source = _state.value.trash?.source ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action(container.trashFor(File(source.rootPath!!))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage.RestoreFailed(e.message))
            }
            reloadTrash(source)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as DiagrammApp
                MainViewModel(app.container)
            }
        }
    }
}
