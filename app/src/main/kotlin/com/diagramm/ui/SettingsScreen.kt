package com.diagramm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

@Composable
fun SettingsScreen(
    settings: SettingsUi,
    onMode: (AppRemovalMode) -> Unit,
    onShizukuAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(16.dp),
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

        // Shizuku state and the one next step the user can take
        val (statusText, actionText) = when (settings.shizuku) {
            ShizukuStatus.READY -> R.string.shizuku_ready to null
            ShizukuStatus.NEEDS_PERMISSION -> R.string.shizuku_needs_permission to R.string.shizuku_action_grant
            ShizukuStatus.NOT_RUNNING -> R.string.shizuku_not_running to R.string.shizuku_action_open
            ShizukuStatus.NOT_INSTALLED -> R.string.shizuku_not_installed to R.string.shizuku_action_download
            ShizukuStatus.UNSUPPORTED -> R.string.shizuku_unsupported to R.string.shizuku_action_download
            ShizukuStatus.UNKNOWN -> R.string.shizuku_unknown to R.string.shizuku_action_refresh
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text(
                stringResource(statusText),
                color = if (settings.shizuku == ShizukuStatus.READY) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (actionText != null) {
                OutlinedButton(onClick = onShizukuAction) { Text(stringResource(actionText)) }
            }
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
