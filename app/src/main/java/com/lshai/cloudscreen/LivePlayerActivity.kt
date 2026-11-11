package com.lshai.cloudscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import java.io.File

/** Live TV has its own controls and never writes VOD history or episode progress. */
class LivePlayerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var playlistFile: File? = null
    private var channels = emptyList<Channel>()
    private var index = 0
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private var picker: AlertDialog? = null
    private var lastSwitch = 0L
    private var digits = ""
    private var resumeWanted = true
    private val hideBanner = Runnable {
        banner.visibility = View.GONE
        controls.visibility = View.GONE
    }
    private val commitDigits = Runnable {
        val number = digits.toIntOrNull()
        digits = ""
        if (number != null && number in 1..channels.size) tune(number - 1)
        else {
            Toast.makeText(this, "频道号不存在", Toast.LENGTH_SHORT).show()
            showBanner()
        }
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val filename = intent.getStringExtra("playlist_file").orEmpty()
        val file = File(cacheDir, filename)
        try {
            require(filename.startsWith("live-session-") && filename == file.name && file.isFile)
            val json = JSONArray(file.readText(Charsets.UTF_8))
            channels = (0 until json.length()).mapNotNull { i ->
                val o = json.getJSONObject(i)
                val url = o.optString("url")
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) null
                else Channel(o.optString("name"), url, o.optString("group", "其他"), "")
            }
            playlistFile = file
            require(channels.isNotEmpty())
        } catch (_: Exception) {
            Toast.makeText(this, "无法读取频道列表，请重新进入电视直播", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = PlayerView(this).apply {
            useController = false
            isFocusable = false
            setOnClickListener { showChannels() }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        banner = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(0xCC103E38.toInt()); cornerRadius = dp(12).toFloat()
            }
        }
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(28), dp(24), dp(28), 0)
        })
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(0xAA000000.toInt())
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xBB102D29.toInt())
        }
        fun action(label: String, callback: () -> Unit) {
            controls.addView(TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 16f
                setPadding(dp(18), dp(12), dp(18), dp(12))
                isClickable = true; setOnClickListener { callback() }
            })
        }
        action("上一台") { change(-1) }
        action("选频道") { showChannels() }
        action("下一台") { change(1) }
        action("返回首页") { finish() }
        root.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24)
        })
        setContentView(root)
        BackgroundPlayback.player?.pause()
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http))
            .build().also { p ->
            p.setAudioAttributes(AudioAttributes.DEFAULT, true)
            p.setHandleAudioBecomingNoisy(true)
            video.player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> status.visibility = View.GONE
                        Player.STATE_BUFFERING -> {
                            status.text = "正在接收直播信号…"
                            status.visibility = View.VISIBLE
                        }
                        Player.STATE_ENDED -> {
                            status.text = "直播信号已结束，可上下换台"
                            status.visibility = View.VISIBLE
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    status.text = "当前频道暂时无法播放\n上下换台 · 确定选择频道或重试"
                    status.visibility = View.VISIBLE
                    showBanner()
                }
            })
        }
        tune((savedInstanceState?.getInt("channel_index") ?: intent.getIntExtra("channel_index", 0))
            .coerceIn(channels.indices))
    }

    private fun tune(target: Int) {
        index = target
        digits = ""
        handler.removeCallbacks(commitDigits)
        status.text = "正在打开 ${channels[index].name}…"
        status.visibility = View.VISIBLE
        player?.apply {
            setMediaItem(MediaItem.fromUri(channels[index].url))
            prepare()
            play()
        }
        showBanner()
    }

    private fun change(delta: Int) {
        tune(stepLiveChannel(index, delta, channels.size))
    }

    private fun showBanner() {
        if (!::banner.isInitialized) return
        banner.text = "${index + 1}  ${channels[index].name}\n${channels[index].group} · 上下换台 / 确定选频道"
        banner.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 4000)
    }

    private fun showChannels() {
        if (picker?.isShowing == true) return
        handler.removeCallbacks(commitDigits)
        digits = ""
        showBanner()
        picker = AlertDialog.Builder(this).setTitle("电视直播 · 选择频道")
            .setSingleChoiceItems(channels.mapIndexed { i, c -> "${i + 1}  ${c.name} · ${c.group}" }.toTypedArray(), index) { dialog, target ->
                dialog.dismiss()
                tune(target)
            }.setNegativeButton("关闭", null).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val navigation = code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)
        if (navigation) {
            if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || SystemClock.uptimeMillis() - lastSwitch >= 300)) {
                lastSwitch = SystemClock.uptimeMillis()
                change(if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN) -1 else 1)
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showChannels()
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, if (code == KeyEvent.KEYCODE_DPAD_RIGHT) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI)
            }
            return true
        }
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                digits = (digits + (code - KeyEvent.KEYCODE_0)).takeLast(4)
                banner.text = "频道 $digits"
                banner.visibility = View.VISIBLE
                handler.removeCallbacks(hideBanner)
                handler.removeCallbacks(commitDigits)
                handler.postDelayed(commitDigits, 1200)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (resumeWanted && player?.playbackState != Player.STATE_IDLE) {
            player?.seekToDefaultPosition()
            player?.play()
        }
    }

    override fun onStop() {
        handler.removeCallbacks(commitDigits)
        digits = ""
        resumeWanted = player?.playWhenReady == true
        player?.pause()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("channel_index", index)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        picker?.dismiss()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (isFinishing) playlistFile?.delete()
        super.onDestroy()
    }
}
