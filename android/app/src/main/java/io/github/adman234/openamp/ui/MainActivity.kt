package io.github.adman234.openamp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import io.github.adman234.openamp.R
import io.github.adman234.openamp.data.DownloadStatus

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
    BackHandler(enabled = vm.canGoBack) { vm.back() }
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
                Screen.Browse -> BrowseScreen(vm)
                is Screen.AlbumList -> AlbumListScreen(vm, screen)
                is Screen.Tracks -> TracksScreen(vm, screen)
            }
        }
    }
}

/** Square artwork with a plain tile behind it while it loads or when there is none. */
@Composable
fun Art(model: Any?, size: Dp) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
    val id = vm.nowId ?: return
    val local by vm.local.collectAsState()
    val track = local.firstOrNull { it.id == id }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Art(vm.art(track?.albumId, track?.thumb), 44.dp)
            Column(Modifier.weight(1f)) {
                Text(vm.nowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    vm.nowArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = off,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = vm::toggleShuffle) {
                Icon(
                    painterResource(R.drawable.ic_shuffle),
                    contentDescription = if (vm.shuffle) "Shuffle is on" else "Shuffle is off",
                    tint = if (vm.shuffle) on else off,
                )
            }
            IconButton(onClick = vm::previous) {
                Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous")
            }
            FilledIconButton(onClick = vm::togglePlay) {
                Icon(
                    painterResource(if (vm.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (vm.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = vm::next) {
                Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next")
            }
            IconButton(onClick = vm::cycleRepeat) {
                val one = vm.repeatMode == Player.REPEAT_MODE_ONE
                Icon(
                    painterResource(if (one) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                    contentDescription = when (vm.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repeating one track"
                        Player.REPEAT_MODE_ALL -> "Repeating all"
                        else -> "Repeat is off"
                    },
                    tint = if (vm.repeatMode == Player.REPEAT_MODE_OFF) off else on,
                )
            }
        }
    }
}
