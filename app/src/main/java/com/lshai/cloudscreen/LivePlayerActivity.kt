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
class LivePlayerActivity : EdgeSwipeBackActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private lateinit var httpFactory: androidx.media3.datasource.DefaultHttpDataSource.Factory
    private var playlistFile: File? = null
    private var channels = emptyList<Channel>()
    private var channelLines = emptyList<List<Channel>>()
    private var lineIndex = 0
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
            val incoming = (0 until json.length()).mapNotNull { i ->
                val o = json.getJSONObject(i)
                val url = o.optString("url")
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) null
                else Channel(o.optString("name"), url, o.optString("group", "其他"), "", readLiveHeaders(o))
            }
            require(incoming.isNotEmpty())
            val initial = incoming[intent.getIntExtra("channel_index", 0).coerceIn(incoming.indices)]
            val grouped = linkedMapOf<String, MutableList<Channel>>()
            incoming.forEach { channel ->
                val key = liveChannelKey(channel.name).ifBlank { channel.url }
                val lines = grouped.getOrPut(key) { mutableListOf() }
                if (lines.none { it.url == channel.url && it.headers == channel.headers }) lines.add(channel)
            }
            channelLines = grouped.values.map { it.toList() }
            channels = channelLines.map { it.first() }
            index = channelLines.indexOfFirst { list -> list.any { it.url == initial.url } }.coerceAtLeast(0)
            playlistFile = file
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
        httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory))
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
                    val failedChannel = index
                    val failedLine = lineIndex
                    if (failedLine + 1 < channelLines[failedChannel].size) {
                        status.text = "当前线路不可用，正在切换备用线路…"
                        status.visibility = View.VISIBLE
                        handler.post {
                            if (index == failedChannel && lineIndex == failedLine && !isFinishing)
                                tune(failedChannel, failedLine + 1)
                        }
                    } else {
                        status.text = "当前频道暂时无法播放\n上下换台 · 确定选择频道或重试"
                        status.visibility = View.VISIBLE
                        showBanner()
                    }
                }
            })
        }
        tune((savedInstanceState?.getInt("channel_index") ?: index).coerceIn(channels.indices))
    }

    private fun tune(target: Int, line: Int = 0) {
        index = target
        lineIndex = line.coerceIn(channelLines[index].indices)
        digits = ""
        handler.removeCallbacks(commitDigits)
        status.text = "正在打开 ${channels[index].name}…"
        status.visibility = View.VISIBLE
        val line = channelLines[index][lineIndex]
        httpFactory.setDefaultRequestProperties(line.headers)
        player?.apply {
            setMediaItem(MediaItem.fromUri(line.url))
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
        val line = if (channelLines[index].size > 1) " · 线路 ${lineIndex + 1}/${channelLines[index].size}" else ""
        banner.text = "${index + 1}  ${channels[index].name}$line\n${channels[index].group} · 上下换台 / 确定选频道"
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
        var group = "全部分组"
        var visible = channels.indices.toList()
        val list = android.widget.ListView(this).apply {
            choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
        }
        val search = android.widget.EditText(this).apply {
            hint = "搜索频道"
            isSingleLine = true
        }
        val groupButton = android.widget.Button(this).apply { text = group }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        filters.addView(groupButton, LinearLayout.LayoutParams(-2, -2))
        filters.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(filters)
        content.addView(list, LinearLayout.LayoutParams(-1, minOf(dp(340), (resources.displayMetrics.heightPixels * 0.52f).toInt())))
        fun refresh() {
            val query = search.text.toString().trim()
            visible = channels.indices.filter { i ->
                (group == "全部分组" || channels[i].group == group) &&
                    (query.isEmpty() || channels[i].name.contains(query, ignoreCase = true))
            }
            list.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_single_choice,
                visible.map { i -> "${i + 1}  ${channels[i].name} · ${channels[i].group}" })
            val selected = visible.indexOf(index)
            if (selected >= 0) { list.setItemChecked(selected, true); list.setSelection(selected) }
        }
        groupButton.setOnClickListener {
            val groups = listOf("全部分组") + channels.map { it.group }.distinct().sorted()
            AlertDialog.Builder(this).setTitle("选择分组").setItems(groups.toTypedArray()) { _, which ->
                group = groups[which]; groupButton.text = group; refresh()
            }.setNegativeButton("取消", null).show()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val target = visible.getOrNull(position) ?: return@setOnItemClickListener
            picker?.dismiss()
            tune(target)
        }
        refresh()
        picker = AlertDialog.Builder(this).setTitle("电视直播 · 选择频道")
            .setView(content)
            .setNeutralButton("重试当前台") { _, _ -> tune(index) }
            .setNegativeButton("关闭", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                    list.requestFocus()
                }
                dialog.show()
            }
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
