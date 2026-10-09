package com.diagramm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diagramm.R
import com.diagramm.storage.TrashEntry

@Composable
fun ScanScreen(scan: ScanUi, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val fmt = rememberSizeFormatter()
    val used = scan.source.quota?.usedBytes ?: 0
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.scanning_title, sourceTitle(scan.source)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            stringResource(
                if (scan.source.type == SourceType.APPS) R.string.scanning_stats_apps else R.string.scanning_stats,
                scan.files, fmt(scan.bytes),
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (used > 0) {
            LinearProgressIndicator(
                progress = { (scan.bytes.toFloat() / used).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
        Text(
            scan.currentPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
fun TrashScreen(
    entries: List<TrashEntry>,
    onRestore: (TrashEntry) -> Unit,
    onPurge: (TrashEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = rememberSizeFormatter()
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.trash_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(fmt(entry.sizeBytes))
                }
                Text(
                    entry.originalPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onRestore(entry) }) { Text(stringResource(R.string.trash_restore)) }
                    TextButton(onClick = { onPurge(entry) }) {
                        Text(stringResource(R.string.trash_delete_forever), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
