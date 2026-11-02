package io.github.adman234.openamp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val statusWords = mapOf(
    "pending" to "Waiting to start",
    "queued" to "Queued",
    "downloading" to "Downloading",
    "awaiting_approval" to "Waiting for approval",
    "imported" to "Added to the library",
    "failed" to "Failed",
    "cancelled" to "Cancelled",
    "incomplete" to "Incomplete",
)

private fun statusWord(status: String) = statusWords[status] ?: status.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
}

/** What can be done with an album: nothing if it is in the library or already asked for, otherwise request it. */
@Composable
private fun RequestState(vm: AppViewModel, id: String, inLibrary: Boolean, requested: Boolean, onRequest: () -> Unit) {
    when {
        inLibrary -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("In library", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        requested || id in vm.dnRequested ->
            Text("Requested", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> OutlinedButton(onClick = onRequest) { Text("Request") }
    }
}

/** Search DroppedNeedle, request albums, and follow the requests already made. */
@Composable
fun RequestScreen(vm: AppViewModel) {
    val focus = LocalFocusManager.current
    // Requests move on their own, so the lists are fetched again every few seconds while this screen is open.
    LaunchedEffect(vm.dnSignedIn) {
        while (vm.dnSignedIn) {
            vm.dnRefresh()
            delay(5_000)
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Request music") {
            IconButton(onClick = vm::dnRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        }
        if (!vm.dnSignedIn) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Requests go through your DroppedNeedle server. Add its address and sign in under Settings first.")
                Button(onClick = { vm.open(Screen.Setup) }) { Text("Open settings") }
            }
            return@Column
        }
        OutlinedTextField(
            value = vm.dnQuery,
            onValueChange = { vm.dnQuery = it },
            placeholder = { Text("Search for an artist or album") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (vm.dnQuery.isNotEmpty()) {
                    IconButton(onClick = vm::dnClearSearch) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.dnSearch() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (vm.dnBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        vm.dnNotice?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        vm.dnError?.let {
            Text(
                it,
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val found = vm.dnResults
        LazyColumn(Modifier.fillMaxSize()) {
            if (found != null) {
                if (found.artists.isEmpty() && found.albums.isEmpty()) {
                    item { Text("Nothing found.", Modifier.padding(16.dp)) }
                }
                if (found.artists.isNotEmpty()) {
                    item { SectionTitle("Artists") }
                    items(found.artists, key = { "ar" + it.id }) { artist ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.dnOpenArtist(artist.id, artist.title) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Art(vm.dnCover(artist, artist = true), 48.dp, CircleShape)
                            Column(Modifier.weight(1f)) {
                                Text(artist.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                (artist.disambiguation ?: artist.typeInfo)?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text("See albums", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (found.albums.isNotEmpty()) {
                    item { SectionTitle("Albums") }
                    items(found.albums, key = { "al" + it.id }) { album ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Art(vm.dnCover(album), 56.dp)
                            Column(Modifier.weight(1f)) {
                                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(album.artist, album.year?.toString(), album.typeInfo).filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            RequestState(vm, album.id, album.inLibrary, album.requested) {
                                vm.dnRequest(album.id, album.artist, album.title, album.year)
                            }
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(top = 12.dp)) }
            }

            item { SectionTitle("In progress") }
            if (vm.dnActive.isEmpty()) {
                item { Text("Nothing is being fetched right now.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = muted) }
            }
            items(vm.dnActive, key = { "ac" + it.id }) { job ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Art(vm.dnAlbumCover(job.id), 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(job.album, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(job.artist, job.error ?: job.downloadStatus ?: statusWord(job.status)).filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (job.error != null) MaterialTheme.colorScheme.error else muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // DroppedNeedle reports either a fraction or a percentage.
                        job.progress?.takeIf { it > 0 }?.let { p ->
                            val fraction = (if (p > 1) p / 100 else p).toFloat().coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    IconButton(onClick = { vm.dnCancel(job.id) }) { Icon(Icons.Default.Clear, contentDescription = "Cancel this request") }
                }
            }

            if (vm.dnPast.isNotEmpty()) {
                item { SectionTitle("Earlier requests") }
                items(vm.dnPast, key = { "pa" + it.id }) { job ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Art(vm.dnAlbumCover(job.id), 48.dp)
                        Column(Modifier.weight(1f)) {
                            Text(job.album, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(job.artist, statusWord(job.status)).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (job.status == "failed") MaterialTheme.colorScheme.error else muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (job.status in setOf("failed", "cancelled", "incomplete")) {
                            TextButton(onClick = { vm.dnRetry(job.id) }) { Text("Try again") }
                        }
                    }
                }
            }
        }
    }
}

/** One artist's releases from the catalogue, each with a request button. */
@Composable
fun RequestArtistScreen(vm: AppViewModel, screen: Screen.RequestArtist) {
    val releases = vm.dnReleases
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, screen.name)
        if (vm.dnBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.dnNotice?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        vm.dnError?.let {
            Text(
                it,
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (releases == null) return@Column
        val groups = listOf("Albums" to releases.albums, "EPs" to releases.eps, "Singles" to releases.singles)
            .map { (title, list) -> title to list.filter { !it.id.isNullOrBlank() } }
            .filter { it.second.isNotEmpty() }
        if (groups.isEmpty()) Text("No releases found for this artist.", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (title, list) ->
                item(key = title) { SectionTitle(title) }
                items(list, key = { title + it.id }) { release ->
                    val id = release.id.orEmpty()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Art(vm.dnAlbumCover(id), 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(release.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            release.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = muted) }
                        }
                        RequestState(vm, id, release.inLibrary, release.requested) {
                            vm.dnRequest(id, screen.name, release.title, release.year)
                        }
                    }
                }
            }
        }
    }
}
