package com.diagramm.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diagramm.R
import com.diagramm.data.StorageAccess
import com.diagramm.data.UsageAccess
import com.diagramm.model.Collector
import com.diagramm.storage.DeleteMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagrammApp(
    vm: MainViewModel,
    onConnect: (SourceType) -> Unit,
    onUninstall: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val fmt = rememberSizeFormatter()

    // ---- one-off messages ----
    val currentFmt by rememberUpdatedState(fmt)
    LaunchedEffect(vm) {
        vm.messages.collect { msg -> snackbar.showSnackbar(messageText(context, msg, currentFmt)) }
    }

    // ---- storage permission ----
    val hasAccess by rememberPermissionCheck(StorageAccess::has)
    var askPermissionFor by remember { mutableStateOf<SourceItem?>(null) }
    val hasUsageAccess by rememberPermissionCheck(UsageAccess::has)
    var askUsageFor by remember { mutableStateOf<SourceItem?>(null) }
    val legacyPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(hasAccess) {
        val pending = askPermissionFor
        if (hasAccess && pending != null) {
            askPermissionFor = null
            vm.startScan(pending)
        }
    }

    LaunchedEffect(hasUsageAccess) {
        val pending = askUsageFor
        if (hasUsageAccess && pending != null) {
            askUsageFor = null
            vm.startScan(pending)
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = state.screen != Screen.HOME) { vm.onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val title = when (state.screen) {
                Screen.HOME -> stringResource(R.string.app_name)
                Screen.SCANNING -> state.scan?.let { sourceTitle(it.source) }.orEmpty()
                Screen.EXPLORER -> state.explorer?.let { sourceTitle(it.source) }.orEmpty()
                Screen.TRASH -> stringResource(R.string.trash_title)
            }
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (state.screen != Screen.HOME) {
                        IconButton(onClick = { vm.onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    when (state.screen) {
                        Screen.EXPLORER -> {
                            IconButton(onClick = { vm.rescan() }) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.rescan))
                            }
                        }
                        Screen.TRASH -> if (state.trash?.entries?.isNotEmpty() == true) {
                            IconButton(onClick = { vm.emptyTrash() }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.trash_empty_all))
                            }
                        }
                        else -> Unit
                    }
                },
            )
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (state.screen) {
            Screen.HOME -> HomeScreen(
                sources = state.sources,
                onScan = { source ->
                    when {
                        source.type == SourceType.LOCAL && !hasAccess -> askPermissionFor = source
                        source.type == SourceType.APPS && !hasUsageAccess -> askUsageFor = source
                        else -> vm.startScan(source)
                    }
                },
                onConnect = onConnect,
                onDisconnect = vm::disconnect,
                onOpenTrash = vm::openTrash,
                modifier = content,
            )
            Screen.SCANNING -> state.scan?.let { ScanScreen(it, onCancel = { vm.onBack() }, modifier = content) }
            Screen.EXPLORER -> state.explorer?.let {
                ExplorerScreen(
                    it, vm,
                    onDelete = { showDeleteDialog = true },
                    onUninstall = onUninstall,
                    modifier = content,
                )
            }
            Screen.TRASH -> state.trash?.let {
                TrashScreen(it.entries, onRestore = vm::restore, onPurge = vm::purge, modifier = content)
            }
        }
    }

    // ---- dialogs ----
    askPermissionFor?.let {
        AlertDialog(
            onDismissRequest = { askPermissionFor = null },
            title = { Text(stringResource(R.string.perm_title)) },
            text = { Text(stringResource(R.string.perm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        openAllFilesSettings(context)
                    } else {
                        legacyPermissions.launch(
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        )
                    }
                }) { Text(stringResource(R.string.perm_grant)) }
            },
            dismissButton = { TextButton(onClick = { askPermissionFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    askUsageFor?.let {
        AlertDialog(
            onDismissRequest = { askUsageFor = null },
            title = { Text(stringResource(R.string.usage_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.usage_text), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.usage_restricted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { openUsageAccessSettings(context) }) { Text(stringResource(R.string.usage_open)) }
            },
            dismissButton = {
                TextButton(onClick = { openAppSettings(context, context.packageName) }) {
                    Text(stringResource(R.string.usage_app_info))
                }
            },
        )
    }

    val explorer = state.explorer
    if (showDeleteDialog && explorer != null && !explorer.collector.isEmpty) {
        DeleteDialog(
            collector = explorer.collector,
            local = explorer.source.type == SourceType.LOCAL,
            initialMode = state.lastDeleteMode,
            fmt = fmt,
            onConfirm = { mode ->
                showDeleteDialog = false
                vm.delete(mode)
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
    if (state.deleting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.deleting)) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
    }
}

@Composable
private fun DeleteDialog(
    collector: Collector,
    local: Boolean,
    initialMode: DeleteMode,
    fmt: (Long) -> String,
    onConfirm: (DeleteMode) -> Unit,
    onDismiss: () -> Unit,
) {
    // starts on whatever was chosen last time in this run of the app
    var mode by remember { mutableStateOf(initialMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title, collector.items.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_total, fmt(collector.totalSize)))
                ModeOption(
                    selected = mode == DeleteMode.TO_TRASH,
                    text = stringResource(if (local) R.string.delete_opt_trash_local else R.string.delete_opt_trash_cloud),
                    onSelect = { mode = DeleteMode.TO_TRASH },
                )
                ModeOption(
                    selected = mode == DeleteMode.PERMANENT,
                    text = stringResource(R.string.delete_opt_permanent),
                    onSelect = { mode = DeleteMode.PERMANENT },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(mode) }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ModeOption(selected: Boolean, text: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun messageText(context: Context, msg: UiMessage, fmt: (Long) -> String): String {
    val unknown = context.getString(R.string.msg_unknown_error)
    return when (msg) {
        is UiMessage.ScanFailed -> context.getString(R.string.msg_scan_failed, msg.detail ?: unknown)
        is UiMessage.SignInRequired -> context.getString(R.string.msg_sign_in_required)
        is UiMessage.Deleted -> context.getString(R.string.msg_deleted, fmt(msg.bytes))
        is UiMessage.DeleteFailed -> context.getString(R.string.msg_delete_partial, msg.count, msg.reason ?: unknown)
        is UiMessage.RestoreFailed -> context.getString(R.string.msg_restore_failed, msg.reason ?: unknown)
        is UiMessage.ConnectFailed -> context.getString(R.string.msg_connect_failed, msg.reason ?: unknown)
    }
}

private fun openAllFilesSettings(context: Context) {
    val app = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

private fun openUsageAccessSettings(context: Context) {
    try {
        context.startActivity(UsageAccess.settingsIntent(context))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

/** Re-runs [check] whenever the app comes back to the foreground (e.g. from the Settings screen). */
@Composable
private fun rememberPermissionCheck(check: (Context) -> Boolean): State<Boolean> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(check(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = check(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}
