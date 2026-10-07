@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.adman234.openamp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import io.github.adman234.openamp.R

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The download progress notification needs this on Android 13 and later.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            val dark = when (vm.theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // Status and navigation bar icons follow the app's theme, not the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            val context = LocalContext.current
            val scheme = when {
                // Android 12 and later: colours follow the phone's wallpaper.
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme(primary = Color(0xFF56C9C0), secondary = Color(0xFFFFB84A))
                else -> lightColorScheme(primary = Color(0xFF0A6F6B), secondary = Color(0xFFB06A00))
            }
            MaterialTheme(colorScheme = scheme) { App(vm) }
        }
    }
}

@Composable
private fun App(vm: AppViewModel) {
    BackHandler(enabled = vm.canGoBack) { vm.back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val hasPlayer = vm.nowId != null
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val miniHeight = MiniHeight + navBar
            Column(
                Modifier.fillMaxSize().statusBarsPadding().padding(bottom = if (hasPlayer) miniHeight else navBar),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val screen = vm.screen) {
                        Screen.Setup -> SetupScreen(vm)
                        Screen.Browse -> BrowseScreen(vm)
                        Screen.Downloads -> DownloadsScreen(vm)
                        Screen.Requests -> RequestScreen(vm)
                        is Screen.RequestArtist -> RequestArtistScreen(vm, screen)
                        is Screen.Section -> SectionScreen(vm, screen)
                        is Screen.Artist -> ArtistScreen(vm, screen)
                        is Screen.Tracks -> TracksScreen(vm, screen)
                    }
                }
                DownloadStrip(vm)
            }
            if (hasPlayer) PlayerSheet(vm, maxHeight, miniHeight)
            vm.menu?.let { LongPressMenu(vm, it) }
        }
    }
}

/** Square artwork with a plain tile behind it while it loads or when there is none. */
@Composable
fun Art(model: Any?, size: Dp, shape: Shape = RoundedCornerShape(4.dp)) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Title row with a back arrow on the left when there is somewhere to go back to. */
@Composable
fun TopBar(vm: AppViewModel, title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (vm.canGoBack) 4.dp else 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.canGoBack) {
            IconButton(onClick = vm::back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Text(
            title,
            Modifier.weight(1f).padding(vertical = 12.dp),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** A thin line above the player while tracks are downloading. Tap it for the queue. */
@Composable
private fun DownloadStrip(vm: AppViewModel) {
    val queue by vm.downloadQueue.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val notice by vm.downloadNotice.collectAsState()
    val paused by vm.downloadsPaused.collectAsState()
    val waiting = queue.count { !it.failed }
    if (queue.isEmpty() || vm.screen == Screen.Downloads) return

    Column(Modifier.fillMaxWidth().clickable { vm.open(Screen.Downloads) }) {
        HorizontalDivider()
        val failed = queue.size - waiting
        Text(
            when {
                waiting == 0 -> "$failed downloads failed. Tap to retry."
                paused -> "Downloads paused, $waiting waiting"
                else -> notice ?: "Downloading, $waiting left" + if (failed > 0) ", $failed failed" else ""
            },
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        progress?.takeIf { it.total > 0 }?.let {
            LinearProgressIndicator(progress = { it.bytes.toFloat() / it.total }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MenuRow(icon: Painter, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null)
        Text(label)
    }
}

/** The long press menu for an album or a track. */
@Composable
private fun LongPressMenu(vm: AppViewModel, target: MenuTarget) {
    fun done(action: () -> Unit): () -> Unit = { action(); vm.menu = null }

    ModalBottomSheet(onDismissRequest = { vm.menu = null }) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            when (target) {
                is MenuTarget.OfAlbum -> {
                    val album = target.album
                    val onPhone = vm.albumTracks(album.id)
                    MenuHeader(album.title, album.artist)
                    if (onPhone.isNotEmpty()) {
                        MenuRow(painterResource(R.drawable.ic_play), "Play now", done { vm.play(onPhone, 0) })
                        MenuRow(painterResource(R.drawable.ic_playlist_play), "Play next", done { vm.playNext(onPhone) })
                        MenuRow(painterResource(R.drawable.ic_playlist_add), "Add album to queue", done { vm.addToQueue(onPhone) })
                    }
                    MenuRow(painterResource(R.drawable.ic_download), "Download", done { vm.downloadAlbum(album) })
                    if (onPhone.isNotEmpty()) {
                        MenuRow(rememberVectorPainter(Icons.Default.Delete), "Remove download", done { vm.removeDownloads(onPhone) })
                    }
                    MenuRow(rememberVectorPainter(Icons.Default.Person), "Go to artist", done { vm.openArtist(album.artist) })
                }
                is MenuTarget.OfTrack -> {
                    val track = target.local
                    val item = target.item
                    MenuHeader(track?.title ?: item?.title.orEmpty(), track?.artist ?: item?.grandparentTitle.orEmpty())
                    if (track != null) {
                        MenuRow(painterResource(R.drawable.ic_play), "Play now", done { vm.play(listOf(track), 0) })
                        MenuRow(painterResource(R.drawable.ic_playlist_play), "Play next", done { vm.playNext(listOf(track)) })
                        MenuRow(painterResource(R.drawable.ic_playlist_add), "Add to queue", done { vm.addToQueue(listOf(track)) })
                        MenuRow(rememberVectorPainter(Icons.Default.Delete), "Remove download", done { vm.removeDownloads(listOf(track)) })
                    } else if (item != null) {
                        MenuRow(painterResource(R.drawable.ic_download), "Download", done { vm.download(target.source, listOf(item)) })
                    }
                    val artist = track?.albumArtist ?: item?.grandparentTitle.orEmpty()
                    if (artist.isNotBlank()) {
                        MenuRow(rememberVectorPainter(Icons.Default.Person), "Go to artist", done { vm.openArtist(artist) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider()
}
