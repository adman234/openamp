package io.github.adman234.openamp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import io.github.adman234.openamp.OpenAmpApp
import io.github.adman234.openamp.R
import io.github.adman234.openamp.data.artFile
import io.github.adman234.openamp.ui.MainActivity

/** Home screen widget: artwork, title, artist, previous, play or pause, next. */
class PlayerWidget : AppWidgetProvider() {
    data class Now(val title: String, val artist: String, val albumId: String?, val playing: Boolean)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        /** Set by the playback service while it runs. */
        @Volatile
        var now: Now? = null

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as OpenAmpApp
            // With the player not running, show the track it would resume.
            val state = now ?: app.resume.load().let { resume ->
                val id = resume.ids.getOrNull(resume.index)
                app.store.tracks.value.firstOrNull { it.id == id }?.let { Now(it.title, it.artist, it.albumId, false) }
            }

            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(R.id.widget_title, state?.title ?: "OpenAmp")
            views.setTextViewText(R.id.widget_artist, state?.artist ?: "Nothing playing")
            views.setImageViewResource(R.id.widget_play, if (state?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
            val art = state?.albumId?.let { artFile(context, it) }?.takeIf { it.exists() }?.let {
                // Half size keeps the bitmap small enough to hand to the launcher.
                BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
            if (art != null) views.setImageViewBitmap(R.id.widget_art, art)
            else views.setImageViewResource(R.id.widget_art, R.drawable.ic_launcher_foreground)

            views.setOnClickPendingIntent(R.id.widget_prev, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_play, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT))
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(ids, views)
        }

        private fun mediaKey(context: Context, code: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClass(context, MediaButtonReceiver::class.java)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            return PendingIntent.getBroadcast(
                context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
