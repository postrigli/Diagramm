package com.diagramm

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.diagramm.ui.DiagrammApp
import com.diagramm.ui.MainViewModel
import com.diagramm.ui.SourceType
import com.diagramm.ui.UiMessage
import com.diagramm.ui.theme.DiagrammTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.Factory }
    private val container: AppContainer get() = (application as DiagrammApp).container

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                container.googleAuth.finishConnect(this, result.data)
                vm.refreshSources()
            } catch (e: Exception) {
                vm.report(UiMessage.ConnectFailed(e.message))
            }
        }
    }

    // Uninstalling is a system dialog per app: run them one after another and report what really went.
    private val uninstallQueue = ArrayDeque<String>()
    private var currentUninstall: String? = null
    private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        currentUninstall?.let { pkg -> if (!isInstalled(pkg)) vm.onAppsRemoved(listOf(pkg)) }
        currentUninstall = null
        launchNextUninstall()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { vm.uninstallRequests.collect { startUninstall(it) } }
        handleRedirect(intent)
        setContent {
            DiagrammTheme {
                DiagrammApp(vm = vm, onConnect = ::connect)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
    }

    /** The Yandex OAuth redirect (yx<client id>://token#access_token=...) lands here. */
    private fun handleRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (container.yandexAuth.handleRedirect(uri)) vm.refreshSources()
    }

    private fun connect(type: SourceType) {
        when (type) {
            SourceType.GDRIVE -> lifecycleScope.launch {
                try {
                    val pending = container.googleAuth.beginConnect(this@MainActivity)
                    if (pending != null) {
                        googleConsent.launch(IntentSenderRequest.Builder(pending).build())
                    } else {
                        vm.refreshSources()
                    }
                } catch (e: Exception) {
                    vm.report(UiMessage.ConnectFailed(e.message))
                }
            }
            SourceType.YANDEX -> if (container.yandexAuth.isConfigured) {
                startActivity(Intent(Intent.ACTION_VIEW, container.yandexAuth.authorizeUri()))
            }
            SourceType.LOCAL, SourceType.APPS -> Unit
        }
    }

    private fun startUninstall(packages: List<String>) {
        uninstallQueue.addAll(packages)
        if (currentUninstall == null) launchNextUninstall()
    }

    private fun launchNextUninstall() {
        val pkg = uninstallQueue.removeFirstOrNull() ?: return
        currentUninstall = pkg
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        try {
            uninstallLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            currentUninstall = null
            launchNextUninstall()
        }
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
