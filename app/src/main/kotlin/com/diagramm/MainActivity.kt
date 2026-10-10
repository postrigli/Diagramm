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

    // Uninstalling is the system's own dialog for one app; afterwards we check whether it really went.
    private var pendingUninstall: String? = null
    private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingUninstall?.let { pkg -> if (!isInstalled(pkg)) vm.onAppsRemoved(listOf(pkg)) }
        pendingUninstall = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleRedirect(intent)
        setContent {
            DiagrammTheme {
                DiagrammApp(vm = vm, onConnect = ::connect, onUninstall = ::uninstall)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onAppResumed() // back from an app's system settings: re-read that app's sizes
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

    private fun uninstall(packageName: String) {
        pendingUninstall = packageName
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        try {
            uninstallLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            pendingUninstall = null
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
