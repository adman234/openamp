@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.adman234.openamp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import coil.compose.AsyncImage
import io.github.adman234.openamp.R
import kotlinx.coroutines.launch

/** Height of the collapsed player, before the navigation bar's own space. */
val MiniHeight = 64.dp

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * The player. Collapsed it is a bar along the bottom. Tap it or drag it up
 * for the full screen player, drag down to collapse it again.
 */
@Composable
fun BoxScope.PlayerSheet(vm: AppViewModel, fullHeight: Dp, miniHeight: Dp) {
    val scope = rememberCoroutineScope()
    val expand = remember { Animatable(0f) }
    val travel = with(LocalDensity.current) { (fullHeight - miniHeight).toPx() }.coerceAtLeast(1f)
    var showQueue by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = expand.value > 0.5f) {
        if (showQueue) showQueue = false else scope.launch { expand.animateTo(0f) }
    }
    val drag = rememberDraggableState { delta ->
        scope.launch { expand.snapTo((expand.value - delta / travel).coerceIn(0f, 1f)) }
    }
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(miniHeight + (fullHeight - miniHeight) * expand.value)
            .draggable(
                state = drag,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    // A flick decides by direction, a slow drag by where it was let go.
                    val open = velocity < -600f || (velocity < 600f && expand.value > 0.5f)
                    expand.animateTo(if (open) 1f else 0f)
                },
            ),
    ) {
        if (expand.value < 0.5f) {
            MiniPlayer(
                vm,
                Modifier.alpha(1f - expand.value * 2f).clickable { scope.launch { expand.animateTo(1f) } },
            )
        } else {
            FullPlayer(
                vm,
                Modifier.alpha((expand.value - 0.5f) * 2f),
                showQueue = showQueue,
                onToggleQueue = { showQueue = !showQueue },
                onCollapse = { scope.launch { expand.animateTo(0f) } },
            )
        }
    }
}

@Composable
private fun nowArt(vm: AppViewModel): Any? {
    val local by vm.local.collectAsState()
    val id = vm.nowId
    val track = remember(local, id) { local.firstOrNull { it.id == id } }
    return vm.art(track?.albumId ?: vm.nowAlbumId, track?.thumb)
}

@Composable
private fun MiniPlayer(vm: AppViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { if (vm.durationMs > 0) vm.positionMs.toFloat() / vm.durationMs else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
        Row(
            Modifier.fillMaxWidth().height(MiniHeight - 2.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Art(nowArt(vm), 44.dp)
            Column(Modifier.weight(1f)) {
                Text(vm.nowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    vm.nowArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = vm::togglePlay) {
                Icon(
                    painterResource(if (vm.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (vm.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = vm::next) {
                Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next")
            }
        }
    }
}

@Composable
private fun Controls(vm: AppViewModel) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth(),
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
        IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) {
            Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous", modifier = Modifier.size(36.dp))
        }
        FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(72.dp)) {
            Icon(
                painterResource(if (vm.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (vm.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = vm::next, modifier = Modifier.size(56.dp)) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next", modifier = Modifier.size(36.dp))
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

@Composable
private fun FullPlayer(
    vm: AppViewModel,
    modifier: Modifier,
    showQueue: Boolean,
    onToggleQueue: () -> Unit,
    onCollapse: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    // The top of the screen takes on the album artwork's main colour.
    val tint = vm.artColor?.let { Color(it).copy(alpha = 0.55f).compositeOver(surface) } ?: surface
    Column(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint, surface)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse the player")
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                )
            }
            IconButton(onClick = onToggleQueue) {
                Icon(
                    painterResource(R.drawable.ic_queue_music),
                    contentDescription = if (showQueue) "Hide the queue" else "Show the queue",
                    tint = if (showQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (showQueue) {
            QueueView(vm, Modifier.weight(1f))
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = nowArt(vm),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Text(vm.nowTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(vm.nowArtist, vm.nowAlbum).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        var scrub by remember { mutableStateOf<Float?>(null) }
        val duration = vm.durationMs
        Slider(
            value = scrub ?: if (duration > 0) (vm.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { vm.seekTo((it * duration).toLong()) }
                scrub = null
            },
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = scrub?.let { (it * duration).toLong() } ?: vm.positionMs
            Text(clock(shown), style = MaterialTheme.typography.labelSmall)
            Text("-" + clock(duration - shown), style = MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.padding(top = 8.dp, bottom = 24.dp)) { Controls(vm) }
    }
}

/** The play queue. Drag a row by its handle to reorder, swipe it or tap the cross to remove it. */
@Composable
private fun QueueView(vm: AppViewModel, modifier: Modifier) {
    val rowHeight = 60.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    // While a row is being dragged the new order is kept here, and sent to the player on release.
    var order by remember(vm.queue) { mutableStateOf(vm.queue) }
    var dragUid by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var dragFrom by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = vm.queueIndex.coerceAtLeast(0))
    val playingUid = vm.queue.getOrNull(vm.queueIndex)?.uid

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (order.size == 1) "Queue, 1 track" else "Queue, ${order.size} tracks",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = vm::clearQueue) { Text("Clear queue") }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(order, key = { _, entry -> entry.uid }) { index, entry ->
                val dragging = entry.uid == dragUid
                val dismiss = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.Settled) false
                        else {
                            vm.removeFromQueue(vm.queue.indexOfFirst { it.uid == entry.uid })
                            true
                        }
                    },
                )
                SwipeToDismissBox(
                    state = dismiss,
                    backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)) },
                    modifier = Modifier.zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f },
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(rowHeight)
                            .background(
                                if (dragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                            )
                            .clickable { vm.jumpTo(index) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp).size(40.dp).padding(8.dp)
                                .pointerInput(entry.uid) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragUid = entry.uid
                                            dragFrom = order.indexOfFirst { it.uid == entry.uid }
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            val at = order.indexOfFirst { it.uid == entry.uid }
                                            // Passing the middle of a neighbour swaps places with it.
                                            if (dragOffset > rowPx / 2 && at in 0 until order.lastIndex) {
                                                order = order.toMutableList().apply { add(at + 1, removeAt(at)) }
                                                dragOffset -= rowPx
                                            } else if (dragOffset < -rowPx / 2 && at > 0) {
                                                order = order.toMutableList().apply { add(at - 1, removeAt(at)) }
                                                dragOffset += rowPx
                                            }
                                        },
                                        onDragEnd = {
                                            val to = order.indexOfFirst { it.uid == entry.uid }
                                            dragUid = null
                                            if (dragFrom >= 0 && to >= 0) vm.moveInQueue(dragFrom, to)
                                        },
                                        onDragCancel = {
                                            dragUid = null
                                            order = vm.queue
                                        },
                                    )
                                },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (entry.uid == playingUid) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                            Text(
                                entry.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { vm.removeFromQueue(vm.queue.indexOfFirst { it.uid == entry.uid }) }) {
                            Icon(Icons.Default.Clear, contentDescription = "Remove from queue")
                        }
                    }
                }
            }
        }
    }
}
