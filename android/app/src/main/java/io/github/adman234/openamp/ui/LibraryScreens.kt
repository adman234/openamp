package io.github.adman234.openamp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.adman234.openamp.data.AlbumRef
import io.github.adman234.openamp.data.LocalTrack
import io.github.adman234.openamp.data.PlaylistRef

/** The albums to show: the Plex library when it loaded, otherwise what is on the phone. */
private fun baseAlbums(library: List<AlbumRef>, local: List<LocalTrack>, onlyDownloaded: Boolean): List<AlbumRef> {
    if (library.isEmpty()) {
        return local.groupBy { it.albumId }.map { (id, tracks) ->
            tracks.first().let { AlbumRef(id, it.album, it.albumArtist, it.thumb, it.year, 0, it.genres) }
        }
    }
    if (!onlyDownloaded) return library
    val ids = local.mapTo(HashSet()) { it.albumId }
    return library.filter { it.id in ids }
}

private fun sortAlbums(albums: List<AlbumRef>, sort: SortBy): List<AlbumRef> = when (sort) {
    SortBy.Title -> albums.sortedBy { it.title.lowercase() }
    SortBy.Artist -> albums.sortedWith(compareBy({ it.artist.lowercase() }, { it.year ?: 0 }, { it.title.lowercase() }))
    SortBy.Recent -> albums.sortedByDescending { it.addedAt }
    SortBy.Year -> albums.sortedWith(compareByDescending<AlbumRef> { it.year ?: 0 }.thenBy { it.title.lowercase() })
}

@Composable
private fun DownloadedMark() {
    Icon(
        Icons.Default.Check,
        contentDescription = "Downloaded",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun SortMenu(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("Sort: ${vm.sort.label}")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortBy.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { vm.sort = option; open = false },
                )
            }
        }
    }
}

@Composable
private fun DownloadedOnlyCheckbox(vm: AppViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier.clickable { vm.onlyDownloaded = !vm.onlyDownloaded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = vm.onlyDownloaded, onCheckedChange = { vm.onlyDownloaded = it })
        Text("Show downloaded only", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AlbumRows(vm: AppViewModel, albums: List<AlbumRef>, downloadedIds: Set<String>, empty: String) {
    if (albums.isEmpty() && !vm.loading) Text(empty, Modifier.padding(16.dp))
    LazyColumn(Modifier.fillMaxSize()) {
        items(albums, key = { it.id }) { album ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.openAlbum(album) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Art(vm.art(album.id, album.thumb), 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(album.artist.ifBlank { null }, album.year?.toString()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (album.id in downloadedIds) DownloadedMark()
            }
        }
    }
}

/** A plain list of names with a count, used for artists, genres and playlists. */
@Composable
private fun <T> NameRows(rows: List<T>, title: (T) -> String, detail: (T) -> String, empty: String, onClick: (T) -> Unit) {
    if (rows.isEmpty()) Text(empty, Modifier.padding(16.dp))
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows) { row ->
            Column(
                Modifier.fillMaxWidth().clickable { onClick(row) }.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(title(row), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    detail(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

@Composable
fun BrowseScreen(vm: AppViewModel) {
    val local by vm.local.collectAsState()
    val savedPlaylists by vm.localPlaylists.collectAsState()
    LaunchedEffect(Unit) { if (vm.albums.isEmpty()) vm.loadLibrary() }

    val downloadedIds = remember(local) { local.mapTo(HashSet()) { it.albumId } }
    val albums = remember(vm.albums, local, vm.onlyDownloaded) { baseAlbums(vm.albums, local, vm.onlyDownloaded) }
    val query = vm.query.trim()
    var modeMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { modeMenu = true }) {
                    Text(vm.mode.label, style = MaterialTheme.typography.headlineSmall)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose what to browse")
                }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    BrowseMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { vm.mode = option; modeMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::loadLibrary) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
            IconButton(onClick = { vm.open(Screen.Setup) }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            placeholder = { Text("Search ${vm.mode.label.lowercase()}") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadedOnlyCheckbox(vm, Modifier.weight(1f))
            if (vm.mode == BrowseMode.Albums) SortMenu(vm)
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

        val nothing = if (query.isNotEmpty()) "Nothing matches \"$query\"." else "Nothing to show here yet."
        when (vm.mode) {
            BrowseMode.Albums -> {
                val shown = remember(albums, query, vm.sort) {
                    sortAlbums(
                        albums.filter { it.title.contains(query, true) || it.artist.contains(query, true) },
                        vm.sort,
                    )
                }
                AlbumRows(vm, shown, downloadedIds, nothing)
            }
            BrowseMode.Artists -> {
                val artists = remember(albums, query) {
                    albums.groupBy { it.artist }.filterKeys { it.isNotBlank() && it.contains(query, true) }
                        .map { (name, list) -> name to list.size }.sortedBy { it.first.lowercase() }
                }
                NameRows(artists, { it.first }, { count(it.second, "album", "albums") }, nothing) {
                    vm.open(Screen.AlbumList(title = it.first, artist = it.first))
                }
            }
            BrowseMode.Genres -> {
                val genres = remember(albums, query) {
                    albums.flatMap { album -> album.genres.map { it to album } }.groupBy({ it.first }, { it.second })
                        .filterKeys { it.contains(query, true) }
                        .map { (name, list) -> name to list.size }.sortedBy { it.first.lowercase() }
                }
                NameRows(genres, { it.first }, { count(it.second, "album", "albums") }, nothing) {
                    vm.open(Screen.AlbumList(title = it.first, genre = it.first))
                }
            }
            BrowseMode.Playlists -> {
                val playlists = remember(vm.playlists, savedPlaylists, vm.onlyDownloaded, query) {
                    val saved = savedPlaylists.associateBy { it.id }
                    val all = if (vm.playlists.isEmpty() || vm.onlyDownloaded) {
                        // Prefer the server's copy of a saved playlist, for its artwork and count.
                        val live = vm.playlists.associateBy { it.id }
                        savedPlaylists.map { live[it.id] ?: PlaylistRef(it.id, it.title, null, it.trackIds.size) }
                    } else {
                        vm.playlists
                    }
                    all.filter { it.title.contains(query, true) }.sortedBy { it.title.lowercase() }
                        .map { it to (it.id in saved) }
                }
                NameRows(
                    playlists,
                    { it.first.title },
                    { count(it.first.count, "track", "tracks") + if (it.second) ", downloaded" else "" },
                    nothing,
                ) { vm.openPlaylist(it.first) }
            }
        }
    }
}

@Composable
fun AlbumListScreen(vm: AppViewModel, screen: Screen.AlbumList) {
    val local by vm.local.collectAsState()
    val downloadedIds = remember(local) { local.mapTo(HashSet()) { it.albumId } }
    val shown = remember(vm.albums, local, vm.onlyDownloaded, vm.sort, screen) {
        sortAlbums(
            baseAlbums(vm.albums, local, vm.onlyDownloaded).filter {
                (screen.artist == null || it.artist == screen.artist) &&
                    (screen.genre == null || screen.genre in it.genres)
            },
            vm.sort,
        )
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, screen.title)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DownloadedOnlyCheckbox(vm, Modifier.weight(1f))
            SortMenu(vm)
        }
        AlbumRows(vm, shown, downloadedIds, "No albums to show.")
    }
}

@Composable
fun TracksScreen(vm: AppViewModel, screen: Screen.Tracks) {
    val album = screen.album
    val playlist = screen.playlist
    val local by vm.local.collectAsState()
    val savedPlaylists by vm.localPlaylists.collectAsState()

    val byId = remember(local) { local.associateBy { it.id } }
    val items = remember(vm.tracks) { vm.tracks.associateBy { it.ratingKey } }
    // The order comes from Plex when it answered. Offline it comes from the download records.
    val ids = remember(vm.tracks, local, savedPlaylists, screen) {
        when {
            vm.tracks.isNotEmpty() -> vm.tracks.map { it.ratingKey }
            album != null -> local.filter { it.albumId == album.id }
                .sortedWith(compareBy({ it.disc }, { it.index })).map { it.id }
            else -> savedPlaylists.firstOrNull { it.id == playlist?.id }?.trackIds.orEmpty()
        }
    }
    val onPhone = remember(ids, byId) { ids.mapNotNull { byId[it] } }
    val missing = remember(ids, byId, items) { ids.filter { it !in byId }.mapNotNull { items[it] } }
    val qualityLabel = qualities.first { it.first == vm.quality }.second

    Column(Modifier.fillMaxSize()) {
        TopBar(vm, if (album != null) "Album" else "Playlist")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Art(if (album != null) vm.art(album.id, album.thumb) else vm.art(null, playlist?.thumb), 72.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    album?.title ?: playlist?.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    album?.artist ?: count(ids.size, "track", "tracks"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.play(onPhone, 0) }, enabled = onPhone.isNotEmpty()) { Text("Play") }
            OutlinedButton(onClick = { vm.download(screen, missing) }, enabled = missing.isNotEmpty()) {
                Text(if (missing.isEmpty() && ids.isNotEmpty()) "Downloaded" else "Download ($qualityLabel)")
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
        if (ids.isEmpty()) Text("No tracks to show.", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            // No keys: a playlist can hold the same track twice.
            itemsIndexed(ids) { position, id ->
                val item = items[id]
                val track = byId[id]
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            // A track on the phone plays. One that is not gets downloaded.
                            if (track != null) vm.play(onPhone, ids.take(position).count { it in byId })
                            else item?.let { vm.download(screen, listOf(it)) }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (album != null) (item?.index ?: track?.index ?: 0).toString() else (position + 1).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item?.title ?: track?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (playlist != null) {
                            Text(
                                item?.let { it.originalTitle ?: it.grandparentTitle } ?: track?.artist.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (track != null) {
                        DownloadedMark()
                    } else {
                        Text(
                            "Tap to download",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
