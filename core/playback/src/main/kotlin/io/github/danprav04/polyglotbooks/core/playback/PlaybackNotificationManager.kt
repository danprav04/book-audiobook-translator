package io.github.danprav04.polyglotbooks.core.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider

/**
 * Builds and updates the ongoing playback notification and Media3 [DefaultMediaNotificationProvider]
 * for audiobooks and ebook narration.
 */
@UnstableApi
class PlaybackNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "polyglot_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val MAIN_ACTIVITY_CLASS_NAME = "io.github.danprav04.polyglotbooks.MainActivity"

        fun createSessionActivityPendingIntent(context: Context): PendingIntent? {
            return try {
                val launchIntent = context.packageManager?.getLaunchIntentForPackage(context.packageName)
                    ?: Intent().setClassName(context, MAIN_ACTIVITY_CLASS_NAME)
                launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                PendingIntent.getActivity(context, 0, launchIntent, flags)
            } catch (_: Throwable) {
                null
            }
        }
    }

    init {
        createNotificationChannel()
    }

    fun createNotificationChannel() {
        try {
            val name = "Polyglot Books Playback"
            val description = "Controls and media status for Polyglot Books"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
                setShowBadge(false)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        } catch (_: Throwable) {
        }
    }

    /**
     * Creates a Media3 [DefaultMediaNotificationProvider] configured with [CHANNEL_ID] and
     * [NOTIFICATION_ID] so [PolyglotPlaybackService] renders rich `MediaStyle` notifications
     * with transport controls (play/pause, seek, chapter navigation).
     */
    fun createMediaNotificationProvider(): DefaultMediaNotificationProvider {
        createNotificationChannel()
        return DefaultMediaNotificationProvider.Builder(context)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()
    }

    /**
     * Builds a fallback notification for the current playback state.
     */
    fun buildNotification(
        title: String,
        author: String,
        isPlaying: Boolean,
        subtitleText: String? = null
    ): android.app.Notification {
        val contentIntent = createSessionActivityPendingIntent(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (subtitleText.isNullOrBlank()) author else "$author • $subtitleText")
            .setSmallIcon(
                if (isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            )
            .setOngoing(isPlaying)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }

        return builder.build()
    }
}
