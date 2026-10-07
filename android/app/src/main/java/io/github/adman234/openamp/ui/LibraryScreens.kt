@file:OptIn(ExperimentalFoundationApi::class)

package io.github.adman234.openamp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.adman234.openamp.R
import io.github.adman234.openamp.data.AlbumPlays
import io.github.adman234.openamp.data.AlbumRef
import io.github.adman234.openamp.data.LocalTrack
import io.github.adman234.openamp.data.PlaylistRef
import kotlinx.coroutines.launch

/** Albums that have at least one track on the phone, built from the download records. */
private fun downloadedAlbums(local: List<LocalTrack>): List<AlbumRef> =
    local.groupBy { it.albumId }.map { (id, tracks) ->
        tracks.first().let { AlbumRef(id, it.album, it.albumArtist, it.thumb, it.year, 0, it.genres) }
    }

/** The albums to show: the Plex library when it loaded, otherwise what is on the phone. */
private fun baseAlbums(library: List<AlbumRef>, local: List<LocalTrack>, onlyDownloaded: Boolean): List<AlbumRef> {
    if (library.isEmpty()) return downloadedAlbums(local)
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

private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

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
                DropdownMenuItem(text = { Text(option.label) }, onClick = { vm.sort = option; open = false })
            }
        }
    }
}

@Composable
private fun GridToggle(vm: AppViewModel) {
    IconButton(onClick = { vm.updateGrid(!vm.grid) }) {
        if (vm.grid) Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Show as a list")
        else Icon(painterResource(R.drawable.ic_grid), contentDescription = "Show as a grid")
    }
}

@Composable
private fun DownloadedOnlyCheckbox(vm: AppViewModel, modifier: Modifier = Modifier) {
    Row(modifier.clickable { vm.onlyDownloaded = !vm.onlyDownloaded }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = vm.onlyDownloaded, onCheckedChange = { vm.onlyDownloaded = it })
        Text("Show downloaded only", style = MaterialTheme.typography.bodyMedium)
    }
}

/** A strip of letters down the right edge. Touch or slide on it to jump through a list sorted by name. */
@Composable
private fun BoxScope.AlphabetBar(names: List<String>, onJump: (Int) -> Unit) {
    val letters = remember { listOf('#') + ('A'..'Z') }
    // For each letter, the first row that starts with it or with a later letter.
    val targets = remember(names) {
        val first = HashMap<Char, Int>()
        names.forEachIndexed { i, name ->
            val c = name.trim().firstOrNull()?.uppercaseChar()
            first.putIfAbsent(if (c != null && c in 'A'..'Z') c else '#', i)
        }
        var next = names.lastIndex.coerceAtLeast(0)
        letters.reversed().map { letter -> first[letter]?.also { next = it } ?: next }.reversed()
    }
    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(22.dp).padding(vertical = 4.dp)
            .pointerInput(targets) {
                fun jump(y: Float) {
                    val slot = (y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                    onJump(targets[slot])
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    jump(down.position.y)
                    drag(down.id) { change ->
                        jump(change.position.y)
                        change.consume()
                    }
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach {
            Text(it.toString(), fontSize = 9.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AlbumRow(vm: AppViewModel, album: AlbumRef, downloaded: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { vm.openAlbum(album) }, onLongClick = { vm.menu = MenuTarget.OfAlbum(album) })
            .padding(start = 16.dp, end = 28.dp, top = 8.dp, bottom = 8.dp),
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
        if (downloaded) DownloadedMark()
    }
}

/** A square tile with the name underneath, for grids and the home screen's rows. */
@Composable
private fun Tile(
    art: Any?,
    title: String,
    subtitle: String,
    downloaded: Boolean,
    round: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .clip(if (round) CircleShape else RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (downloaded) DownloadedMark()
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AlbumTile(vm: AppViewModel, album: AlbumRef, downloaded: Boolean, modifier: Modifier = Modifier) {
    Tile(
        vm.art(album.id, album.thumb), album.title, album.artist, downloaded, round = false, modifier = modifier,
        onClick = { vm.openAlbum(album) }, onLongClick = { vm.menu = MenuTarget.OfAlbum(album) },
    )
}

/** Albums as a list or a grid, with the letter strip when they are in name order. */
@Composable
private fun AlbumCollection(vm: AppViewModel, albums: List<AlbumRef>, downloadedIds: Set<String>, letters: Boolean, empty: String) {
    if (albums.isEmpty()) {
        if (!vm.loading) Text(empty, Modifier.padding(16.dp))
        return
    }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        val showLetters = letters && albums.size > 30
        if (vm.grid) {
            val state = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                state = state,
                contentPadding = PaddingValues(start = 10.dp, end = if (showLetters) 26.dp else 10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(albums, key = { it.id }) { AlbumTile(vm, it, it.id in downloadedIds) }
            }
            if (showLetters) AlphabetBar(albums.map { it.title }) { scope.launch { state.scrollToItem(it) } }
        } else {
            val state = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(albums, key = { it.id }) { AlbumRow(vm, it, it.id in downloadedIds) }
            }
            if (showLetters) AlphabetBar(albums.map { it.title }) { scope.launch { state.scrollToItem(it) } }
        }
    }
}

private data class ArtistEntry(val name: String, val thumb: String?, val albums: Int, val addedAt: Long, val year: Int)

@Composable
private fun ArtistCollection(vm: AppViewModel, artists: List<ArtistEntry>, letters: Boolean, empty: String) {
    if (artists.isEmpty()) {
        if (!vm.loading) Text(empty, Modifier.padding(16.dp))
        return
    }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        val showLetters = letters && artists.size > 30
        if (vm.grid) {
            val state = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                state = state,
                contentPadding = PaddingValues(start = 10.dp, end = if (showLetters) 26.dp else 10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(artists, key = { it.name }) { artist ->
                    Tile(
                        vm.art(null, artist.thumb), artist.name, count(artist.albums, "album", "albums"),
                        downloaded = false, round = true, modifier = Modifier, onClick = { vm.openArtist(artist.name) },
                    )
                }
            }
            if (showLetters) AlphabetBar(artists.map { it.name }) { scope.launch { state.scrollToItem(it) } }
        } else {
            val state = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(artists, key = { it.name }) { artist ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.openArtist(artist.name) }
                            .padding(start = 16.dp, end = 28.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Art(vm.art(null, artist.thumb), 48.dp, CircleShape)
                        Column(Modifier.weight(1f)) {
                            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                count(artist.albums, "album", "albums"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (showLetters) AlphabetBar(artists.map { it.name }) { scope.launch { state.scrollToItem(it) } }
        }
    }
}

/** The albums behind one of the home screen's rows, in that row's own order. */
private fun sectionAlbums(
    mode: BrowseMode,
    library: List<AlbumRef>,
    local: List<LocalTrack>,
    plays: List<AlbumPlays>,
): List<AlbumRef> {
    val downloaded = downloadedAlbums(local)
    val byId = (downloaded + library).associateBy { it.id }
    return when (mode) {
        // The download records are in the order they were made, so the newest is last.
        BrowseMode.Downloads -> downloaded.reversed().map { byId.getValue(it.id) }
        BrowseMode.RecentlyAdded -> library.filter { it.addedAt > 0 }.sortedByDescending { it.addedAt }
        BrowseMode.RecentlyPlayed -> plays.sortedByDescending { it.last }.mapNotNull { byId[it.albumId] }
        BrowseMode.MostPlayed -> plays.sortedByDescending { it.count }.mapNotNull { byId[it.albumId] }
        else -> emptyList()
    }
}

private val homeRows = listOf(BrowseMode.Downloads, BrowseMode.RecentlyAdded, BrowseMode.RecentlyPlayed, BrowseMode.MostPlayed)

/** Home: a row of albums per section. Each heading opens the whole section. */
@Composable
private fun Home(vm: AppViewModel, local: List<LocalTrack>, downloadedIds: Set<String>) {
    val plays by vm.history.collectAsState()
    val rows = remember(vm.albums, local, plays) {
        homeRows.map { it to sectionAlbums(it, vm.albums, local, plays) }.filter { it.second.isNotEmpty() }
    }
    if (rows.isEmpty()) {
        if (!vm.loading) Text("Nothing here yet. Choose Albums from the menu above to start.", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.first.name }) { (mode, albums) ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.open(Screen.Section(mode)) }
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(mode.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(albums.take(20), key = { it.id }) { AlbumTile(vm, it, it.id in downloadedIds, Modifier.width(148.dp)) }
            }
        }
    }
}

/** One section's albums in full, filtered by the search box and the downloaded-only checkbox. */
@Composable
private fun SectionCollection(vm: AppViewModel, mode: BrowseMode, local: List<LocalTrack>, downloadedIds: Set<String>, query: String) {
    val plays by vm.history.collectAsState()
    val shown = remember(mode, vm.albums, local, plays, vm.onlyDownloaded, query) {
        sectionAlbums(mode, vm.albums, local, plays).filter {
            (!vm.onlyDownloaded || it.id in downloadedIds) &&
                (it.title.contains(query, true) || it.artist.contains(query, true))
        }
    }
    AlbumCollection(vm, shown, downloadedIds, letters = false, empty = "Nothing to show here yet.")
}

@Composable
fun SectionScreen(vm: AppViewModel, screen: Screen.Section) {
    val local by vm.local.collectAsState()
    val downloadedIds = remember(local) { local.mapTo(HashSet()) { it.albumId } }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, screen.mode.label) { GridToggle(vm) }
        if (screen.mode != BrowseMode.Downloads) DownloadedOnlyCheckbox(vm, Modifier.padding(horizontal = 4.dp))
        SectionCollection(vm, screen.mode, local, downloadedIds, "")
    }
}

@Composable
fun BrowseScreen(vm: AppViewModel) {
    val local by vm.local.collectAsState()
    val savedPlaylists by vm.localPlaylists.collectAsState()
    val downloads by vm.downloadQueue.collectAsState()
    LaunchedEffect(Unit) { if (vm.albums.isEmpty()) vm.loadLibrary() }

    val downloadedIds = remember(local) { local.mapTo(HashSet()) { it.albumId } }
    val albums = remember(vm.albums, local, vm.onlyDownloaded) { baseAlbums(vm.albums, local, vm.onlyDownloaded) }
    val query = vm.query.trim()
    var modeMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { modeMenu = true }) {
                    Text(vm.mode.label, style = MaterialTheme.typography.headlineSmall)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose what to browse")
                }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    vm.visibleModes.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { vm.mode = option; modeMenu = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (vm.dnReady) {
                IconButton(onClick = { vm.open(Screen.Requests) }) {
                    Icon(Icons.Default.Add, contentDescription = "Request music")
                }
            }
            IconButton(onClick = { vm.open(Screen.Downloads) }) {
                Icon(
                    painterResource(R.drawable.ic_download),
                    contentDescription = "Downloads and storage",
                    tint = if (downloads.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = vm::loadLibrary) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
            IconButton(onClick = { vm.open(Screen.Setup) }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        }
        if (vm.mode != BrowseMode.Home) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                placeholder = { Text("Search ${vm.mode.label.lowercase()}") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                DownloadedOnlyCheckbox(vm, Modifier.weight(1f))
                if (vm.mode == BrowseMode.Albums || vm.mode == BrowseMode.Artists) SortMenu(vm)
                if (vm.mode != BrowseMode.Playlists) GridToggle(vm)
            }
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
            BrowseMode.Home -> Home(vm, local, downloadedIds)
            BrowseMode.Albums -> {
                val shown = remember(albums, query, vm.sort) {
                    sortAlbums(albums.filter { it.title.contains(query, true) || it.artist.contains(query, true) }, vm.sort)
                }
                AlbumCollection(vm, shown, downloadedIds, letters = vm.sort == SortBy.Title && query.isEmpty(), empty = nothing)
            }
            BrowseMode.Artists -> {
                val artists = remember(albums, vm.artists, query, vm.sort) {
                    val photos = vm.artists.associate { it.name to it.thumb }
                    val all = albums.groupBy { it.artist }.filterKeys { it.isNotBlank() && it.contains(query, true) }
                        .map { (name, list) ->
                            ArtistEntry(name, photos[name], list.size, list.maxOf { it.addedAt }, list.maxOf { it.year ?: 0 })
                        }
                    // An artist is as recent as their newest album.
                    when (vm.sort) {
                        SortBy.Recent -> all.sortedByDescending { it.addedAt }
                        SortBy.Year -> all.sortedWith(compareByDescending<ArtistEntry> { it.year }.thenBy { it.name.lowercase() })
                        else -> all.sortedBy { it.name.lowercase() }
                    }
                }
                ArtistCollection(vm, artists, letters = vm.sort == SortBy.Title || vm.sort == SortBy.Artist, empty = nothing)
            }
            BrowseMode.RecentlyPlayed, BrowseMode.MostPlayed, BrowseMode.Downloads, BrowseMode.RecentlyAdded ->
                SectionCollection(vm, vm.mode, local, downloadedIds, query)
            BrowseMode.Playlists -> {
                val playlists = remember(vm.playlists, savedPlaylists, vm.onlyDownloaded, query) {
                    val saved = savedPlaylists.mapTo(HashSet()) { it.id }
                    val all = if (vm.playlists.isEmpty() || vm.onlyDownloaded) {
                        // Prefer the server's copy of a saved playlist, for its count.
                        val live = vm.playlists.associateBy { it.id }
                        savedPlaylists.map { live[it.id] ?: PlaylistRef(it.id, it.title, null, it.trackIds.size) }
                    } else {
                        vm.playlists
                    }
                    all.filter { it.title.contains(query, true) }.sortedBy { it.title.lowercase() }.map { it to (it.id in saved) }
                }
                if (playlists.isEmpty() && !vm.loading) Text(nothing, Modifier.padding(16.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(playlists, key = { it.first.id }) { (playlist, saved) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.openPlaylist(playlist) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    count(playlist.count, "track", "tracks"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (saved) DownloadedMark()
                        }
                    }
                }
            }
        }
    }
}

/** One artist: photo, name, and their albums. */
@Composable
fun ArtistScreen(vm: AppViewModel, screen: Screen.Artist) {
    val local by vm.local.collectAsState()
    val downloadedIds = remember(local) { local.mapTo(HashSet()) { it.albumId } }
    val shown = remember(vm.albums, local, vm.onlyDownloaded, screen, vm.sort) {
        sortAlbums(baseAlbums(vm.albums, local, vm.onlyDownloaded).filter { it.artist == screen.name }, vm.sort)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Artist") { GridToggle(vm) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Art(vm.art(null, screen.thumb), 112.dp, CircleShape)
            Column(Modifier.weight(1f)) {
                Text(screen.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    count(shown.size, "album", "albums"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DownloadedOnlyCheckbox(vm, Modifier.weight(1f))
            SortMenu(vm)
        }
        HorizontalDivider()
        AlbumCollection(vm, shown, downloadedIds, letters = false, empty = "No albums to show.")
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
    val saved = savedPlaylists.firstOrNull { it.id == playlist?.id }
    // The order comes from Plex when it answered. Offline it comes from the download records.
    val ids = remember(vm.tracks, local, saved, screen) {
        when {
            vm.tracks.isNotEmpty() -> vm.tracks.map { it.ratingKey }
            album != null -> vm.albumTracks(album.id).map { it.id }
            else -> saved?.trackIds.orEmpty()
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
            Art(if (album != null) vm.art(album.id, album.thumb) else vm.art(null, playlist?.thumb), 88.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    album?.title ?: playlist?.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (album != null) {
                    Text(
                        listOfNotNull(album.artist.ifBlank { null }, album.year?.toString()).joinToString(" · "),
                        Modifier.clickable { vm.openArtist(album.artist) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        count(ids.size, "track", "tracks"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { vm.play(onPhone, 0) }, enabled = onPhone.isNotEmpty()) { Text("Play") }
            OutlinedButton(onClick = { vm.download(screen, missing) }, enabled = missing.isNotEmpty()) {
                Text(if (missing.isEmpty() && ids.isNotEmpty()) "Downloaded" else "Download ($qualityLabel)")
            }
            if (onPhone.isNotEmpty()) TextButton(onClick = { vm.removeDownloads(onPhone) }) { Text("Remove download") }
        }
        if (playlist != null) {
            val sync = saved?.sync == true
            Row(
                Modifier.fillMaxWidth().clickable { vm.setPlaylistSync(playlist, !sync) }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = sync, onCheckedChange = { vm.setPlaylistSync(playlist, it) })
                Text("Keep in sync: download tracks added to this playlist", style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        if (ids.isEmpty()) Text("No tracks to show.", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            // No keys: a playlist can hold the same track twice.
            itemsIndexed(ids) { position, id ->
                val item = items[id]
                val track = byId[id]
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                // A track on the phone plays. One that is not gets downloaded.
                                if (track != null) vm.play(onPhone, ids.take(position).count { it in byId })
                                else item?.let { vm.download(screen, listOf(it)) }
                            },
                            onLongClick = { vm.menu = MenuTarget.OfTrack(screen, item, track) },
                        )
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
                        Text(
                            item?.title ?: track?.title.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (id == vm.nowId) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
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
