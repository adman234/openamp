package io.github.adman234.openamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.adman234.openamp.data.LocalTrack

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

private data class AlbumUse(val title: String, val artist: String, val tracks: List<LocalTrack>, val bytes: Long)

/** The download queue, then what is on the phone and how much room it takes. */
@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val queue by vm.downloadQueue.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val paused by vm.downloadsPaused.collectAsState()
    val notice by vm.downloadNotice.collectAsState()
    val local by vm.local.collectAsState()

    val usage = remember(local) {
        local.groupBy { it.albumId }.values
            .map { tracks -> AlbumUse(tracks.first().album, tracks.first().albumArtist, tracks, tracks.sumOf { it.size }) }
            .sortedByDescending { it.bytes }
    }
    val failed = queue.count { it.failed }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Downloads")
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Queue", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            queue.isEmpty() -> "Nothing is waiting to download."
                            paused -> "Paused. ${queue.size - failed} waiting."
                            else -> notice ?: "${queue.size - failed} waiting" + if (failed > 0) ", $failed failed" else ""
                        },
                        color = muted,
                    )
                    if (queue.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.pauseDownloads(!paused) }) { Text(if (paused) "Resume" else "Pause") }
                            if (failed > 0) OutlinedButton(onClick = vm::retryDownloads) { Text("Retry failed") }
                            OutlinedButton(onClick = vm::cancelAllDownloads) { Text("Cancel all") }
                        }
                    }
                }
            }
            items(queue, key = { "q" + it.id }) { task ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(task.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (task.failed) "Failed: ${task.error ?: "unknown reason"}" else task.album.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (task.failed) MaterialTheme.colorScheme.error else muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        progress?.takeIf { it.trackId == task.id && it.total > 0 }?.let {
                            LinearProgressIndicator(
                                progress = { it.bytes.toFloat() / it.total },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            Text("${size(it.bytes)} of ${size(it.total)}", style = MaterialTheme.typography.labelSmall, color = muted)
                        }
                    }
                    IconButton(onClick = { vm.cancelDownload(task.id) }) {
                        Icon(Icons.Default.Clear, contentDescription = "Cancel this download")
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("On this phone", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (local.isEmpty()) "Nothing downloaded yet."
                        else "${local.size} tracks in ${usage.size} albums, ${size(local.sumOf { it.size })} in total.",
                        color = muted,
                    )
                }
            }
            items(usage, key = { "a" + it.tracks.first().albumId }) { album ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${album.artist}, ${album.tracks.size} tracks, ${size(album.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { vm.removeDownloads(album.tracks) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove this album from the phone")
                    }
                }
            }
        }
    }
}
