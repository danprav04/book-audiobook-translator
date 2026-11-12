package io.github.danprav04.polyglotbooks.core.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi

/**
 * Builds and updates the ongoing playback notification for audiobooks and ebook narration.
 */
@UnstableApi
class PlaybackNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "polyglot_playback_channel"
        const val NOTIFICATION_ID = 1001
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Polyglot Books Playback"
            val description = "Controls and media status for Polyglot Books"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Builds a Media3 notification for the current playback state.
     */
    fun buildNotification(
        title: String,
        author: String,
        isPlaying: Boolean,
        subtitleText: String? = null
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (subtitleText.isNullOrBlank()) author else "$author • $subtitleText")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        return builder.build()
    }
}
