package com.vf5plus.tiktokyoutube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

private const val CHANNEL_ID = "vf5_media_channel"
private const val NOTIFICATION_ID = 103

class VF5MediaPlaybackService : MediaBrowserServiceCompat(), WebAudioEngine.EngineListener {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var engine: WebAudioEngine

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        engine = WebAudioEngine.getInstance(this)
        engine.listener = this

        mediaSession = MediaSessionCompat(this, "VF5TikTokYouTubeMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setClass(this@VF5MediaPlaybackService, MediaButtonReceiver::class.java)
            }
            val mediaButtonPendingIntent = PendingIntent.getBroadcast(
                this@VF5MediaPlaybackService,
                0,
                mediaButtonIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            setMediaButtonReceiver(mediaButtonPendingIntent)

            setCallback(MediaSessionCallback())
            setSessionToken(sessionToken)
        }

        updatePlaybackState(engine.isPlaying)
        updateMetadata(engine.currentAuthor, engine.currentTitle)

        startForeground(NOTIFICATION_ID, buildNotification(engine.currentAuthor, engine.currentTitle, engine.isPlaying))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            engine.stopAndRelease()
            stopForeground(STOP_FOREGROUND_REMOVE)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            mediaSession.isActive = false
            mediaSession.release()
        } catch (e: Exception) {}
        stopSelf()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onDestroy() {
        engine.stopAndRelease()
        mediaSession.release()
        super.onDestroy()
    }

    // ─── EngineListener ───────────────────────────────────────────────────────

    override fun onMetadataUpdated(author: String, title: String) {
        updateMetadata(author, title)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(author, title, engine.isPlaying))
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        updatePlaybackState(isPlaying)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(engine.currentAuthor, engine.currentTitle, isPlaying))
    }

    // ─── MediaBrowserServiceCompat Implementation ─────────────────────────────

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot("vf5_media_root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId("tiktok_youtube_audio")
            .setTitle(engine.currentAuthor)
            .setSubtitle(engine.currentTitle)
            .setDescription("TikTok & YouTube cho thanh bên phải xe VF5")
            .build()
        items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        result.sendResult(items)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                      PlaybackStateCompat.ACTION_PAUSE or
                      PlaybackStateCompat.ACTION_PLAY_PAUSE or
                      PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                      PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                      PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMetadata(author: String, title: String) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "VF5 TikTok YouTube")
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VF5 Media Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Âm thanh nền TikTok & YouTube xe VF5"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(author: String, title: String, isPlaying: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        val prevIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        val playPauseAction = if (isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, playPauseAction)
        val nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Tạm dừng" else "Phát"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(author)
            .setContentText(title)
            .setSubText("Thanh bên phải xe VF5 (Vô-lăng chuyển bài)")
            .setContentIntent(openPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Bài trước", prevIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Bài tiếp", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng hẳn", stopIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .build()
    }

    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            engine.play()
        }

        override fun onPause() {
            engine.pause()
            try {
                stopForeground(STOP_FOREGROUND_DETACH)
            } catch (e: Exception) {}
        }

        override fun onSkipToNext() {
            engine.skipNext()
        }

        override fun onSkipToPrevious() {
            engine.skipPrevious()
        }

        override fun onStop() {
            engine.stopAndRelease()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
            } catch (e: Exception) {}
            stopSelf()
        }
    }
}
