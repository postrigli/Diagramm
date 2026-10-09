package com.diagramm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diagramm.R

@Composable
fun HomeScreen(
    sources: List<SourceItem>,
    onScan: (SourceItem) -> Unit,
    onConnect: (SourceType) -> Unit,
    onDisconnect: (SourceType) -> Unit,
    onOpenTrash: (SourceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = rememberSizeFormatter()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(
                    stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(sources, key = { it.id }) { source ->
            SourceCard(source, fmt, onScan, onConnect, onDisconnect, onOpenTrash)
        }
    }
}

@Composable
private fun SourceCard(
    source: SourceItem,
    fmt: (Long) -> String,
    onScan: (SourceItem) -> Unit,
    onConnect: (SourceType) -> Unit,
    onDisconnect: (SourceType) -> Unit,
    onOpenTrash: (SourceItem) -> Unit,
) {
    val cloud = source.type == SourceType.GDRIVE || source.type == SourceType.YANDEX
    val quota = source.quota
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(sourceTitle(source), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            val subtitle = when {
                source.type == SourceType.APPS -> stringResource(R.string.apps_subtitle)
                !source.configured -> stringResource(R.string.yandex_not_configured)
                cloud && !source.connected -> stringResource(R.string.not_connected)
                quota?.totalBytes != null -> stringResource(R.string.used_of, fmt(quota.usedBytes), fmt(quota.totalBytes!!))
                quota != null -> stringResource(R.string.used_only, fmt(quota.usedBytes))
                else -> null
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val total = quota?.totalBytes
            if (quota != null && total != null && total > 0 && (!cloud || source.connected)) {
                LinearProgressIndicator(
                    progress = { (quota.usedBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }

            if (source.trashBytes > 0) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.trash_summary, fmt(source.trashBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onOpenTrash(source) }) { Text(stringResource(R.string.action_open)) }
                }
            }

            if (source.type == SourceType.APPS && source.summaryBytes != null) {
                Text(
                    stringResource(R.string.apps_last_total, fmt(source.summaryBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cloud && !source.connected) {
                    Button(onClick = { onConnect(source.type) }, enabled = source.configured) {
                        Text(stringResource(R.string.action_connect))
                    }
                } else {
                    Button(onClick = { onScan(source) }) { Text(stringResource(R.string.action_scan)) }
                    if (cloud) {
                        TextButton(onClick = { onDisconnect(source.type) }) {
                            Text(stringResource(R.string.action_disconnect))
                        }
                    }
                }
            }
        }
    }
}
