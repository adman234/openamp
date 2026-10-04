@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.adman234.openamp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.adman234.openamp.data.AlbumRef
import io.github.adman234.openamp.data.DownloadStatus
import io.github.adman234.openamp.data.Item
import io.github.adman234.openamp.data.LocalTrack

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(vm)
            }
        }
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val status by vm.download.collectAsState()
    BackHandler(enabled = vm.screen != Screen.Albums && vm.configured) { vm.screen = Screen.Albums }
    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    status?.let { DownloadBar(it) }
                    PlayerBar(vm)
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val screen = vm.screen) {
                Screen.Setup -> SetupScreen(vm)
                Screen.Albums -> AlbumsScreen(vm)
                is Screen.Album -> AlbumScreen(vm, screen.album)
            }
        }
    }
}

@Composable
private fun DownloadBar(status: DownloadStatus) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val counts = "${status.done} of ${status.total}" + if (status.failed > 0) ", ${status.failed} failed" else ""
        Text(
            (if (status.active) "Downloading " else "Downloaded ") + "${status.label}: $counts",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        status.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (status.active && status.total > 0) {
            LinearProgressIndicator(
                progress = { status.done.toFloat() / status.total },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PlayerBar(vm: AppViewModel) {
    val title = vm.nowPlaying ?: return
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = vm::togglePlay) { Text(if (vm.isPlaying) "Pause" else "Play") }
        TextButton(onClick = vm::next) { Text("Next") }
    }
}

private val qualities = listOf("original" to "Original", "high" to "High", "medium" to "Medium", "low" to "Low")

@Composable
private fun SetupScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.updateFolder(uri)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OpenAmp setup", style = MaterialTheme.typography.headlineMedium)

        Text("Plex account", style = MaterialTheme.typography.titleMedium)
        when {
            vm.signingIn -> Text("Finish signing in to Plex in the browser, then come back here.")
            vm.signedIn -> {
                Text(if (vm.serverName.isBlank()) "Signed in." else "Signed in. Server: ${vm.serverName}")
                vm.servers.takeIf { it.size > 1 }?.forEach { server ->
                    OutlinedButton(onClick = { vm.pickServer(server) }) { Text("Use ${server.name}") }
                }
                TextButton(onClick = vm::signOut) { Text("Sign out") }
            }
            else -> Button(onClick = {
                vm.signIn { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }) { Text("Sign in with Plex") }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Addresses", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = vm.plexUrl,
            onValueChange = vm::updatePlexUrl,
            label = { Text("Plex address") },
            placeholder = { Text("https://plex.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.fileUrl,
            onValueChange = vm::updateFileUrl,
            label = { Text("File service address") },
            placeholder = { Text("https://openamp.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Downloads", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { pickFolder.launch(null) }) {
            Text(if (vm.folderUri.isBlank()) "Choose download folder" else "Change download folder")
        }
        if (vm.folderUri.isNotBlank()) {
            Text(
                Uri.parse(vm.folderUri).lastPathSegment ?: vm.folderUri,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            qualities.forEach { (value, label) ->
                FilterChip(
                    selected = vm.quality == value,
                    onClick = { vm.updateQuality(value) },
                    label = { Text(label) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { vm.updateAllowMobile(!vm.allowMobile) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = vm.allowMobile, onCheckedChange = vm::updateAllowMobile)
            Text("Also download on mobile data")
        }

        Button(onClick = { vm.screen = Screen.Albums }, enabled = vm.configured) { Text("Done") }
        if (!vm.configured) {
            Text(
                "Sign in, fill in both addresses and choose a folder to continue.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AlbumArt(vm: AppViewModel, thumb: String?) {
    AsyncImage(
        model = vm.artUrl(thumb),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun AlbumsScreen(vm: AppViewModel) {
    val local by vm.local.collectAsState()
    val downloaded = remember(local) {
        local.groupBy { it.albumId }
            .map { (id, tracks) -> tracks.first().let { AlbumRef(id, it.album, it.albumArtist, it.thumb) } }
            .sortedBy { it.title.lowercase() }
    }
    val downloadedIds = remember(local) { local.mapTo(HashSet()) { it.albumId } }
    var onlyDownloaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (vm.albums.isEmpty()) vm.loadAlbums() }

    // With no answer from Plex, the download records are the library.
    val shown = if (onlyDownloaded || vm.albums.isEmpty()) downloaded else vm.albums

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Albums", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            FilterChip(
                selected = onlyDownloaded,
                onClick = { onlyDownloaded = !onlyDownloaded },
                label = { Text("Downloaded") },
            )
            TextButton(onClick = vm::loadAlbums) { Text("Refresh") }
            TextButton(onClick = { vm.screen = Screen.Setup }) { Text("Settings") }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let {
            Text(
                it,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (shown.isEmpty() && !vm.loading) {
            Text(
                if (onlyDownloaded) "Nothing downloaded yet." else "No albums to show.",
                Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { album ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openAlbum(album) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AlbumArt(vm, album.thumb)
                    Column(Modifier.weight(1f)) {
                        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            album.artist,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (album.id in downloadedIds) {
                        Text(
                            "On phone",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private data class TrackRow(val id: String, val number: String, val title: String, val item: Item?, val local: LocalTrack?)

@Composable
private fun AlbumScreen(vm: AppViewModel, album: AlbumRef) {
    val local by vm.local.collectAsState()
    val onPhone = remember(local, album.id) {
        local.filter { it.albumId == album.id }.sortedWith(compareBy({ it.disc }, { it.index }))
    }
    val rows = remember(vm.tracks, onPhone) {
        val byId = onPhone.associateBy { it.id }
        if (vm.tracks.isNotEmpty()) {
            vm.tracks.map { TrackRow(it.ratingKey, (it.index ?: 0).toString(), it.title, it, byId[it.ratingKey]) }
        } else {
            onPhone.map { TrackRow(it.id, it.index.toString(), it.title, null, it) }
        }
    }
    val missing = rows.filter { it.local == null }.mapNotNull { it.item }
    val qualityLabel = qualities.first { it.first == vm.quality }.second

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlbumArt(vm, album.thumb)
            Column(Modifier.weight(1f)) {
                Text(album.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.play(onPhone, 0) }, enabled = onPhone.isNotEmpty()) { Text("Play") }
            OutlinedButton(onClick = { vm.download(album, missing) }, enabled = missing.isNotEmpty()) {
                Text(if (missing.isEmpty() && rows.isNotEmpty()) "Downloaded" else "Download ($qualityLabel)")
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { _, row -> row.id }) { _, row ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            // A track on the phone plays. One that is not gets downloaded.
                            val at = onPhone.indexOfFirst { it.id == row.id }
                            if (at >= 0) vm.play(onPhone, at) else row.item?.let { vm.download(album, listOf(it)) }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(row.number, style = MaterialTheme.typography.bodySmall)
                    Text(row.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (row.local != null) "On phone" else "Tap to download",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.local != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
