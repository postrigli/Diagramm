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
import com.diagramm.storage.AppUsage
import com.diagramm.storage.AppsTree
import com.diagramm.storage.DeleteMode
import com.diagramm.storage.DeleteResult
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

    /** App whose system settings page the user just opened: its figures are re-read when we come back. */
    private var pendingAppRefresh: String? = null

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
        val apps = SourceItem("apps", SourceType.APPS, summaryBytes = container.appsSummary.read()?.totalBytes)
        val google = SourceItem("gdrive", SourceType.GDRIVE, connected = container.googleAuth.connected.value)
        val yandex = SourceItem(
            "yandex", SourceType.YANDEX,
            connected = container.yandexAuth.connected.value,
            configured = container.yandexAuth.isConfigured,
        )
        _state.update { it.copy(sources = locals + apps + google + yandex) }

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
            SourceType.LOCAL, SourceType.APPS -> Unit
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
        SourceType.APPS -> container.appsProvider()
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
                val (tree, apps) = withContext(Dispatchers.Default) {
                    if (source.type == SourceType.APPS) {
                        val provider = container.appsProvider()
                        val usages = provider.loadUsages(progress)
                        val labels = provider.labels
                        AppsTree.build(usages, labels) to AppsExplorer(
                            usages, labels, AppsTree.buildCache(usages, labels),
                            sort = if (container.settings.appsSortByCache) AppsSort.CACHE else AppsSort.TOTAL,
                        )
                    } else {
                        providerFor(source).scan(progress) to null
                    }
                }
                _state.update {
                    it.copy(screen = Screen.EXPLORER, scan = null, explorer = ExplorerState(source, tree, tree, apps = apps))
                }
                if (apps != null) container.appsSummary.write(tree.size, apps.cacheRoot.size)
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
        _state.update { it.copy(screen = Screen.HOME, scan = null, explorer = null, trash = null) }
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
                when {
                    ex != null && ex.pane != Pane.SPLIT -> setPane(Pane.SPLIT) // first undo a collapsed chart/list
                    ex != null && parent != null -> navigateTo(parent)
                    else -> toHome()
                }
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
        updateExplorer {
            // inside an app we always show the total breakdown (code / data / cache)
            val apps = if (node.parent != null) it.apps?.copy(sort = AppsSort.TOTAL) else it.apps
            it.copy(current = node, focus = null, category = null, apps = apps)
        }
    }

    fun navigateUp() {
        _state.value.explorer?.current?.parent?.let { navigateTo(it) }
    }

    fun focus(node: Node?) = updateExplorer { it.copy(focus = node) }

    fun setCategory(category: FileCategory?) = updateExplorer { it.copy(category = category, focus = null) }

    // ---- layout of the explorer ----------------------------------------------------------------

    fun setPane(pane: Pane) = updateExplorer { if (it.pane == pane) it else it.copy(pane = pane) }

    // ---- multi-selection -----------------------------------------------------------------------

    /**
     * Drag-selection: the selection is [base] (taken when the long press started) plus or minus the nodes
     * [range] the finger has swept over, so moving back un-selects again.
     */
    fun applyRange(base: Collector, range: List<Node>, add: Boolean) {
        setCollector(range.fold(base) { c, n -> if (add) c.plus(n) else c.minus(n) })
    }

    /** The "select all" checkbox above the list: (un)select every node of the shown list. */
    fun setSelection(nodes: List<Node>, select: Boolean) {
        val base = _state.value.explorer?.collector ?: return
        setCollector(nodes.fold(base) { c, n -> if (select) c.plus(n) else c.minus(n) })
    }

    private fun setCollector(collector: Collector) = updateExplorer { it.copy(collector = collector) }

    // ---- collector & deletion ------------------------------------------------------------------

    fun toggleCollected(node: Node) = updateExplorer { it.copy(collector = it.collector.toggle(node)) }

    fun clearCollector() = updateExplorer { it.copy(collector = it.collector.cleared()) }

    fun delete(mode: DeleteMode) {
        val ex = _state.value.explorer ?: return
        val items = ex.collector.items
        if (items.isEmpty() || _state.value.deleting) return
        _state.update { it.copy(lastDeleteMode = mode) }
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

    // ---- apps: sort order, per-app actions ---------------------------------------------------------

    fun setAppsSort(sort: AppsSort) {
        container.settings.appsSortByCache = sort == AppsSort.CACHE
        updateExplorer { ex ->
            val apps = ex.apps
            if (apps == null || apps.sort == sort) ex else ex.copy(apps = apps.copy(sort = sort), focus = null)
        }
    }

    /** The user is about to leave for [packageName]'s system settings: re-read that app when we are back. */
    fun markAppForRefresh(packageName: String) {
        pendingAppRefresh = packageName
    }

    /** Called whenever the app returns to the foreground. */
    fun onAppResumed() {
        val pkg = pendingAppRefresh ?: return
        pendingAppRefresh = null
        refreshApp(pkg)
    }

    /** Called after the system uninstaller reported this package gone. */
    fun onAppsRemoved(packages: Collection<String>) {
        updateExplorer { ex ->
            val apps = ex.apps ?: return@updateExplorer ex
            ex.withUsages(AppsTree.withoutPackages(apps.usages, packages.toSet()))
        }
        persistAppsSummary()
    }

    private fun refreshApp(packageName: String) {
        viewModelScope.launch {
            val fresh = try {
                withContext(Dispatchers.IO) { container.appsProvider().loadUsage(packageName) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch // keep the old figures
            }
            updateExplorer { ex ->
                val apps = ex.apps ?: return@updateExplorer ex
                val usages = if (fresh == null) {
                    AppsTree.withoutPackages(apps.usages, setOf(packageName))
                } else {
                    AppsTree.withReplaced(apps.usages, fresh)
                }
                ex.withUsages(usages)
            }
            persistAppsSummary()
        }
    }

    /** Remembers the current totals so the main screen can show them next time ("minus what was deleted"). */
    private fun persistAppsSummary() {
        val ex = _state.value.explorer ?: return
        val apps = ex.apps ?: return
        container.appsSummary.write(ex.root.size, apps.cacheRoot.size)
    }

    /** Rebuilds both trees from fresh figures, keeping the user's place. */
    private fun ExplorerState.withUsages(newUsages: List<AppUsage>): ExplorerState {
        val apps = apps ?: return this
        val newRoot = AppsTree.build(newUsages, apps.labels)
        return copy(
            root = newRoot,
            current = newRoot.findById(current.id) ?: newRoot,
            focus = null,
            apps = apps.copy(usages = newUsages, cacheRoot = AppsTree.buildCache(newUsages, apps.labels)),
        )
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
