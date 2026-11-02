package io.github.adman234.openamp.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Pauses playback after a set number of minutes. It belongs to the app, not
 * to a screen, so it keeps counting while the app is in the background.
 */
class SleepTimer(private val scope: CoroutineScope) {
    /** When the timer will fire, as a clock time in milliseconds. Null when it is off. */
    val endsAt = MutableStateFlow<Long?>(null)

    /** Set by the playback service, which owns the player. */
    var onFire: (() -> Unit)? = null

    private var job: Job? = null

    fun start(minutes: Int) {
        cancel()
        val wait = minutes * 60_000L
        endsAt.value = System.currentTimeMillis() + wait
        job = scope.launch {
            delay(wait)
            endsAt.value = null
            onFire?.invoke()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        endsAt.value = null
    }
}
