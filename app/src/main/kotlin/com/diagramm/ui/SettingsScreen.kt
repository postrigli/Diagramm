package com.diagramm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diagramm.R
import com.diagramm.data.AppRemovalMode
import com.diagramm.data.ShizukuStatus

/** What the buttons of the setup guide do; the screen itself stays free of Android calls. */
class SetupActions(
    val requestShizuku: () -> Unit,
    val openShizuku: () -> Unit,
    val downloadShizuku: () -> Unit,
    val openUsageAccess: () -> Unit,
    val openAppInfo: () -> Unit,
    val refresh: () -> Unit,
)

@Composable
fun SettingsScreen(
    settings: SettingsUi,
    onMode: (AppRemovalMode) -> Unit,
    actions: SetupActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.settings_removal_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        RemovalModeOption(
            selected = settings.removalMode == AppRemovalMode.SYSTEM_DIALOGS,
            title = stringResource(R.string.removal_system_title),
            description = stringResource(R.string.removal_system_desc),
            onSelect = { onMode(AppRemovalMode.SYSTEM_DIALOGS) },
        )
        RemovalModeOption(
            selected = settings.removalMode == AppRemovalMode.SHIZUKU,
            title = stringResource(R.string.removal_shizuku_title),
            description = stringResource(R.string.removal_shizuku_desc),
            onSelect = { onMode(AppRemovalMode.SHIZUKU) },
        )
        if (settings.removalMode == AppRemovalMode.SHIZUKU) {
            SetupGuide(settings, actions)
        }
    }
}

/**
 * Step-by-step setup of Shizuku, with a live tick on every step that can be detected, so the user always
 * sees what is left. Covers the special access Android demands: Shizuku's own permission, "Usage access"
 * (and the "restricted settings" lock that guards it for apps installed outside a store), and battery.
 */
@Composable
private fun SetupGuide(settings: SettingsUi, actions: SetupActions) {
    val installed = settings.shizuku != ShizukuStatus.NOT_INSTALLED && settings.shizuku != ShizukuStatus.UNKNOWN
    val running = settings.shizuku == ShizukuStatus.NEEDS_PERMISSION || settings.shizuku == ShizukuStatus.READY
    val granted = settings.shizuku == ShizukuStatus.READY

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.guide_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.guide_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GuideStep(1, installed, R.string.guide_install_title, R.string.guide_install_body) {
            if (settings.shizuku == ShizukuStatus.UNSUPPORTED) {
                Text(
                    stringResource(R.string.shizuku_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!installed || settings.shizuku == ShizukuStatus.UNSUPPORTED) {
                OutlinedButton(onClick = actions.downloadShizuku) { Text(stringResource(R.string.shizuku_action_download)) }
            }
        }
        GuideStep(2, running, R.string.guide_start_title, R.string.guide_start_body) {
            if (installed && !running) {
                OutlinedButton(onClick = actions.openShizuku) { Text(stringResource(R.string.shizuku_action_open)) }
            }
        }
        GuideStep(3, granted, R.string.guide_allow_title, R.string.guide_allow_body) {
            if (running && !granted) {
                Button(onClick = actions.requestShizuku) { Text(stringResource(R.string.shizuku_action_grant)) }
            }
        }
        GuideStep(4, settings.usageAccess, R.string.guide_usage_title, R.string.guide_usage_body) {
            if (!settings.usageAccess) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.openUsageAccess) { Text(stringResource(R.string.guide_usage_open)) }
                    TextButton(onClick = actions.openAppInfo) { Text(stringResource(R.string.guide_app_info)) }
                }
            }
        }
        GuideStep(5, null, R.string.guide_battery_title, R.string.guide_battery_body) {}

        TextButton(onClick = actions.refresh) { Text(stringResource(R.string.shizuku_action_refresh)) }
    }
}

/** One numbered step. [done] is null for a tip that cannot be detected from inside the app. */
@Composable
private fun GuideStep(
    number: Int,
    done: Boolean?,
    title: Int,
    body: Int,
    actions: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            StepBadge(number, done)
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(title), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                actions()
            }
        }
    }
}

@Composable
private fun StepBadge(number: Int, done: Boolean?) {
    val primary = MaterialTheme.colorScheme.primary
    when (done) {
        true -> Box(Modifier.size(28.dp).background(primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        }
        false -> Box(
            Modifier.size(28.dp).border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        null -> Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun RemovalModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
