package com.lshai.cloudscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Owns a player that outlives its screen. All methods run on the main thread. */
internal object BackgroundPlayback {
    const val SESSION_EXTRA = "background_session"
    var player: ExoPlayer? = null
    var detached = false
    var token = ""
    var returnIntent: Intent? = null
    var urls: List<String> = emptyList()
    var title = ""
    var series = ""
    var vodId = ""
    var sourceUrl = ""
    var sourceName = ""
    var cover = ""
    var running = false
    private val timerHandler = Handler(Looper.getMainLooper())
    var timerPlayer: ExoPlayer? = null
    private val timer = object : Runnable {
        override fun run() {
            if (sleepUntil <= 0L) return
            val left = sleepUntil - android.os.SystemClock.elapsedRealtime()
            if (left > 0L) { timerHandler.postDelayed(this, left); return }
            sleepUntil = 0L
            (timerPlayer ?: player)?.pause()
        }
    }
    var sleepUntil = 0L
        set(value) {
            field = value
            timerHandler.removeCallbacks(timer)
            if (value > 0L) timerHandler.postDelayed(timer, (value - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        }

    fun releaseDetached() {
        if (!detached) return
        sleepUntil = 0L
        player?.release()
        timerPlayer = null
        player = null
        detached = false
        PlaybackCache.playerClosed()
    }

    fun persist(context: Context) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val url = urls.getOrNull(index) ?: return
        val position = p.currentPosition
        val entry = if (position > 5000) HistoryEntry(
            title = if (urls.size > 1) "$series · 第 ${index + 1}/${urls.size} 集" else title,
            playUrl = url, positionMs = position, durationMs = p.duration.coerceAtLeast(0),
            updatedAt = System.currentTimeMillis(), cover = cover, seriesTitle = series,
            vodId = vodId, sourceUrl = sourceUrl, sourceName = sourceName, episodeIndex = index,
        ) else null
        Prefs(context).savePlaybackSnapshot(url, series, index, position, entry)
    }
}

/** Foreground lifetime and system media controls for an active playback. */
class PlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var observedPlayer: ExoPlayer? = null
    private val tick = object : Runnable {
        override fun run() {
            if (BackgroundPlayback.detached) BackgroundPlayback.persist(applicationContext)
            updateNotification()
            handler.postDelayed(this, 30_000)
        }
    }
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateNotification()
        override fun onPlaybackStateChanged(playbackState: Int) = updateNotification()
    }

    override fun onCreate() {
        super.onCreate()
        BackgroundPlayback.running = true
        session = MediaSession(this, "ShiguangCinema").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { BackgroundPlayback.player?.play(); updateNotification() }
                override fun onPause() { BackgroundPlayback.player?.pause(); updateNotification() }
                override fun onStop() { stopPlayback() }
            })
            isActive = true
        }
        observeCurrentPlayer()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        observeCurrentPlayer()
        if (intent?.action == ACTION_STOP) { stopPlayback(); return START_NOT_STICKY }
        if (intent?.action == ACTION_TOGGLE) {
            BackgroundPlayback.player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        try {
            startForeground(NOTIFICATION_ID, notification())
            getSystemService(NotificationManager::class.java).cancel(0x5EC0)
        } catch (_: RuntimeException) {
            // The player remains owned by the Activity if the platform refuses foreground service.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun observeCurrentPlayer() {
        val next = BackgroundPlayback.player
        if (observedPlayer === next) return
        observedPlayer?.removeListener(listener)
        observedPlayer = next
        next?.addListener(listener)
    }

    private fun stopPlayback() {
        if (BackgroundPlayback.detached) {
            BackgroundPlayback.persist(applicationContext)
            BackgroundPlayback.releaseDetached()
            stopSelf()
        } else {
            PlayerActivity.command(PlayerActivity.CMD_STOP)
            if (BackgroundPlayback.player == null) stopSelf()
        }
    }

    private fun notification(): Notification {
        val channel = "background_media_service"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "后台播放", NotificationManager.IMPORTANCE_LOW))
        }
        val p = BackgroundPlayback.player
        val state = PlaybackState.Builder().setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (p?.isPlaying == true) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                p?.currentPosition ?: 0L, p?.playbackParameters?.speed ?: 1f).build()
        session?.setPlaybackState(state)
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun action(name: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, PlaybackService::class.java).setAction(name), immutable)
        val openIntent = BackgroundPlayback.returnIntent ?: Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, openIntent, immutable)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(BackgroundPlayback.title.ifEmpty { "时光影院" })
            .setContentText("点击返回播放页面")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, if (p?.isPlaying == true) "暂停" else "继续", action(ACTION_TOGGLE, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 2))
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun updateNotification() {
        if (!BackgroundPlayback.running) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    override fun onDestroy() {
        observedPlayer?.removeListener(listener)
        observedPlayer = null
        handler.removeCallbacks(tick)
        session?.release()
        session = null
        BackgroundPlayback.running = false
        if (BackgroundPlayback.detached) {
            BackgroundPlayback.persist(applicationContext)
            BackgroundPlayback.releaseDetached()
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 0x5EC2
        private const val ACTION_TOGGLE = "com.lshai.cloudscreen.SERVICE_TOGGLE"
        private const val ACTION_STOP = "com.lshai.cloudscreen.SERVICE_STOP"
    }
}
