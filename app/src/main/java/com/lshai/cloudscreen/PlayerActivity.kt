package com.lshai.cloudscreen

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 原生播放页（Media3 / ExoPlayer）。
 *
 * 按键逻辑（电视遥控器）：
 *   · OK           暂停 / 播放
 *   · 上           打开倍速菜单
 *   · 左右         快退 / 快进（短按 10 秒，按住 15 秒连发）
 *   · 下 / 菜单键   呼出底部操作栏（下集/上集/刷新/重播/倍速/解码/比例）
 *   · 返回         退出播放
 *
 * 操作栏出现时，按键交给操作栏自己处理（左右移动、OK 执行），不再触发 seek。
 *
 * 片名默认隐藏，只在暂停、操作栏打开、或出错时出现。
 * 缓冲时在「缓冲中…」下面显示实时网速（按累计字节差分计算）。
 */
class PlayerActivity : Activity() {

    private val isTvDevice: Boolean by lazy {
        resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }

    private var coverUrl: String = ""

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"
        // 选集回查用：只带一个播放地址（例如从历史进来）时，靠这几项恢复整季列表
        const val EXTRA_SERIES_NAME = "series_name"
        const val EXTRA_VOD_ID = "vod_id"
        const val EXTRA_SOURCE_URL = "source_url"
        const val EXTRA_SOURCE_NAME = "source_name"
        const val CMD_TOGGLE = "toggle"
        const val CMD_STOP = "stop"

        /** 当前活着的播放页。通知上的按钮通过它控制播放器（不存在就忽略） */
        @Volatile
        private var live: PlayerActivity? = null

        private const val SAVE_INTERVAL_MS = 5_000L
        /** 进度真正落盘的最小间隔：轮询 5 秒一次，但写盘 30 秒一次就够 */
        private const val PERSIST_INTERVAL_MS = 30_000L
        private const val NOTIFICATION_ID = 0x5EC0
        /** 外挂字幕文件选择（SAF） */
        private const val REQ_SUBTITLE = 0x5EC1

        /** 供 [PlaybackReceiver] 调用 */
        fun command(cmd: String) {
            val a = live ?: return
            a.runOnUiThread {
                when (cmd) {
                    CMD_TOGGLE -> {
                        val p = a.player ?: return@runOnUiThread
                        if (p.isPlaying) p.pause() else p.play()
                        a.showPlaybackNotification()
                    }
                    CMD_STOP -> {
                        a.persistProgress()
                        a.finish()
                    }
                }
            }
        }
        private const val HIDE_UI_DELAY_MS = 4_000L
        private const val HIDE_ACTIONS_DELAY_MS = 6_000L
        /** 画面上下滑的两种目标：左半屏亮度、右半屏音量 */
        private const val SWIPE_BRIGHTNESS = 1
        private const val SWIPE_VOLUME = 2
        /** 长按多久开始临时加速 / 加速到几倍 */
        private const val LONG_PRESS_BOOST_MS = 480L
        private const val BOOST_SPEED = 2f
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleText: TextView
    private lateinit var progressRow: LinearLayout
    private lateinit var playPauseIcon: ImageView
    private lateinit var seekBar: MarkedSeekBar
    private lateinit var elapsedText: TextView
    private lateinit var timeText: TextView
    private lateinit var bufferingText: TextView
    private lateinit var speedText: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var speedActionBtn: TextView
    /** 手机底部一级菜单里的倍速按钮（和遥控器操作栏那个共用一份标签） */
    private var speedBarBtn: TextView? = null
    private var introBarBtn: TextView? = null
    private var outroBarBtn: TextView? = null
    /** 暂停时浮在画面正中的播放按钮：点中间暂停之后，继续播就在原地 */
    private var centerPlay: ImageView? = null
    private lateinit var actionScroll: HorizontalScrollView
    private lateinit var prefs: Prefs

    private var urls: List<String> = emptyList()
    private var currentIndex = 0
    private var seriesTitle = ""
    /** 干净剧名（不含「 · 第 x/y 集」），用于回查整季 */
    private var seriesName = ""
    private var vodId = ""
    private var sourceUrl = ""
    private var sourceName = ""
    /** 只有一个地址时的显示标题（历史记录里带集号的那句） */
    private var singleLabel = ""
    private var baseText = ""
    private var pendingSeek = -1L
    private var hasError = false
    /** 已经挂上的外挂字幕（每集一份，跟着播放地址存） */
    private var subtitleUri = ""
    private var subtitleMime = ""
    private var subtitleName = ""
    private var aspectMode = 0
    /** 选集面板（同一时间只会有一个） */
    private var episodeDialog: Dialog? = null

    // 选集面板分页状态：一季动辄上千集，一次性铺满几千个 TextView 会明显卡顿，
    // 所以按页渲染（默认每页 120 集），只在翻页时才重建网格。
    private val epPageSize = 120
    private var epPage = 0
    private var epPageCount = 1
    private var epColumns = 5
    private var epRowHeight = 0
    private var epGridBox: LinearLayout? = null
    private var epScroll: ScrollView? = null
    private var epPageLabel: TextView? = null
    private var epPrevBtn: TextView? = null
    private var epNextBtn: TextView? = null
    private var epContent: LinearLayout? = null
    private var epCurrentCell: TextView? = null

    // 自动连播提示：片尾 6 秒内浮出一行倒计时，按返回可取消（不退出去）。
    // 以前点了「自动连播」就只能眼看着它切走，想停一下回个消息都不行。
    private lateinit var rootBox: FrameLayout
    /** 底部整条控制条（按钮行 + 进度行），全屏纯净模式 / 锁屏时整体隐藏 */
    private lateinit var bottomChrome: LinearLayout
    private var nextHint: TextView? = null
    private var nextCancelledFor = -1
    /** 全屏纯净模式：画面之外什么都不显示（点屏幕才临时唤出控制条） */
    private var pureMode = false
    /** 刚标完片尾开始点的一段静默期：不然点完「记当前位置」立刻就被自动跳走 */
    private var outroSkipGraceUntil = 0L
    /** 标记片尾的这一集照常播完；只从后续集数开始自动跳。 */
    private var outroMarkedUrl: String? = null

    // 手机左侧常驻锁按钮；锁定后透明覆盖层吃掉其余所有触摸。
    // 电视没有触屏，仍允许遥控器返回键解锁。
    private var locked = false
    private var lockOverlay: View? = null
    private var lockButton: View? = null
    private var backButton: TextView? = null
    private var backGestureCallback: OnBackInvokedCallback? = null
    private var lastLockHintAt = 0L
    /** 「全屏 / 还原」的按钮，两处入口（触屏条 + 遥控操作栏）共用一份标签 */
    private val fullscreenButtons = ArrayList<TextView>()

    // 手指点击判定：按下到抬起的位移、耗时都在阈值内才算「轻点」，
    // 这样滑动（比如从画面上起手去拖进度条）不会被误判成一次点击。
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L
    /** 这一串手势已经变成上下滑（调亮度/音量），抬起来就不再算点击 */
    private var swipeMode = 0
    /** 上下滑开始时的基准值（0~1）：亮度或音量 */
    private var swipeBase = 0f
    /** 上一次轻点的时间/位置，用来识别「双击两侧 = 快进快退」 */
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    /** 滑动时浮出的百分比提示（亮度 / 音量共用一块） */
    private var gestureHud: TextView? = null
    private val hideGestureHud = Runnable { gestureHud?.visibility = View.GONE }

    /** 长按画面临时加速：按满这段时间就开始，松手恢复 */
    private var boosting = false
    private val startBoost = Runnable {
        if (locked || boosting) return@Runnable
        val p = player ?: return@Runnable
        if (!p.isPlaying) return@Runnable
        boosting = true
        p.setPlaybackSpeed(BOOST_SPEED)
        showGestureHud("${BOOST_SPEED.toInt()} 倍速播放中 · 松手恢复")
    }

    /** 正在用手指拖进度条：这期间不能被 500ms 的轮询把进度改回去 */
    private var seekingBar = false
    /** 上次把进度写进 SharedPreferences 的时间：每 5 秒重写整份历史 JSON 太浪费 */
    private var lastPersistAt = 0L
    /** 睡眠定时：到点的 uptimeMillis，0 = 没设 */
    private var sleepUntil = 0L
    /** 另一种语义：不按时间，播完当前这一集就停（不想让自动连播往下跑） */
    private var stopAtEpisodeEnd = false
    /** 方向锁：竖屏片源会自动转竖屏，但侧躺看的时候不希望它跟着转 */
    private var orientationLocked = false

    private val handler = Handler(Looper.getMainLooper())

    /** 网速采样：用累计字节/累计耗时做差分 */
    private val statsListener = PlaybackStatsListener(false, null)
    private var lastBytes = 0L
    private var lastMs = 0L

    private val saveTick = object : Runnable {
        override fun run() {
            // 不是每 5 秒都落盘：upsertHistory 要把整份历史数组序列化后重写，
            // 长剧久播时会周期性 GC 抖动（进度条偶尔一顿）。改成最多 30 秒落一次，
            // 真正的收尾（onStop / 退出）仍然立即写。
            val now = SystemClock.uptimeMillis()
            if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                lastPersistAt = now
                persistProgress()
            }
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private val uiTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val speedTick = object : Runnable {
        override fun run() {
            if (!::speedText.isInitialized || speedText.visibility != View.VISIBLE) return
            val stats = statsListener.playbackStats
            val bytes = stats?.totalBandwidthBytes ?: 0L
            val ms = stats?.totalBandwidthTimeMs ?: 0L
            if (ms > lastMs && bytes >= lastBytes) {
                val db = bytes - lastBytes
                val dm = ms - lastMs
                if (dm > 0) speedText.text = "网速 " + formatSpeed(db * 8000L / dm)
            }
            lastBytes = bytes
            lastMs = ms
            handler.postDelayed(this, 500)
        }
    }

    private val clearPendingSeek = Runnable { pendingSeek = -1L }

    /** 片尾倒计时：靠轮询播放位置实现，不依赖播放器自己的「播完」时机，切集不会重复触发 */
    private val nextTick = object : Runnable {
        override fun run() {
            updateNextHint()
            checkOutroSkip()
            checkSleepTimer()
            checkStopAtEpisodeEnd()
            handler.postDelayed(this, 400)
        }
    }

    private val hideUi = Runnable {
        val p = player
        val paused = p != null && !p.playWhenReady && p.playbackState != Player.STATE_IDLE
        handler.removeCallbacks(uiTick)
        // 暂停时控制条常驻；操作栏打开时也不隐藏
        if (!paused && !isActionBarVisible()) {
            // 整条一起收：手机上按钮也不再常驻，点画面唤出 —— 跟主流播放器一致，
            // 「全屏只剩视频」这个诉求也顺带满足了
            if (isTvDevice) progressRow.visibility = View.GONE
            // 手机底部快捷栏常驻；只收起进度行。纯净全屏和锁屏仍会收起整条。
            if (isTvDevice && ::bottomChrome.isInitialized) bottomChrome.visibility = View.GONE
        }
        updateChrome()
    }

    private val hideActions = Runnable {
        if (isActionBarVisible()) setActionBarVisible(false)
        updateChrome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        prefs = Prefs(this)
        // 告诉缓存层「有播放器活着」：此时清播放缓存会 release 分片缓存，播放器会崩
        PlaybackCache.playerOpened()
        urls = intent.getStringArrayListExtra(EXTRA_URLS)?.toList().orEmpty()
        currentIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        singleLabel = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        seriesName = intent.getStringExtra(EXTRA_SERIES_NAME).orEmpty()
        vodId = intent.getStringExtra(EXTRA_VOD_ID).orEmpty()
        sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL).orEmpty()
        sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME).orEmpty()
        seriesTitle = seriesName.ifEmpty { singleLabel }
        coverUrl = intent.getStringExtra(EXTRA_COVER).orEmpty()
        loadSubtitleForCurrent()

        // 恢复上次调好的亮度：窗口级亮度只对当前窗口有效，不主动恢复就会退回系统亮度，
        // 那上一版加的「左半屏上下滑调亮度」每次进来都白调
        val savedBrightness = prefs.playerBrightness
        if (savedBrightness >= 0f) {
            val attrs = window.attributes
            attrs.screenBrightness = savedBrightness.coerceIn(0.02f, 1f)
            window.attributes = attrs
        }

        val root = FrameLayout(this)
        rootBox = root
        root.setBackgroundColor(android.graphics.Color.BLACK)
        playerView = PlayerView(this).apply {
            // 关掉 media3 自带的控制层：它跟我们的底部控制条会叠成两套，
            // 而且它那套样式和全站不一致。进度条的拖动改由下面的 seekBar 自己实现。
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // 画面手势（手机）：
        //   · 轻点中间竖带 → 播放/暂停
        //   · 轻点左右 → 唤出/收起控制条
        //   · 双击左右 → 快退/快进 10 秒
        //   · 左半屏上下滑 → 亮度；右半屏上下滑 → 音量
        //   · 长按画面 → 临时 2 倍速，松手回到原速
        // 只认「按下→抬起」位移/耗时都在阈值内的轻点，滑动不会被误判成点击。
        playerView.setOnTouchListener { v, e ->
            if (locked) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = e.x
                    touchDownY = e.y
                    touchDownAt = SystemClock.uptimeMillis()
                    swipeMode = 0
                    handler.removeCallbacks(startBoost)
                    handler.postDelayed(startBoost, LONG_PRESS_BOOST_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - touchDownX
                    val dy = e.y - touchDownY
                    // 手指一动就取消「长按加速」，免得滑动调音量时突然变 2 倍速
                    if (kotlin.math.abs(dx) > dp(12) || kotlin.math.abs(dy) > dp(12)) {
                        handler.removeCallbacks(startBoost)
                    }
                    if (swipeMode == 0 && kotlin.math.abs(dy) > dp(28) && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f) {
                        // 第一次判定成上下滑：记下基准值，之后整串手势都按滑动处理
                        val leftHalf = touchDownX < v.width / 2f
                        swipeMode = if (leftHalf) SWIPE_BRIGHTNESS else SWIPE_VOLUME
                        swipeBase = if (leftHalf) currentBrightness() else (audioManager()?.let {
                            it.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
                                it.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        } ?: 0f)
                        handler.removeCallbacks(hideUi)
                    }
                    if (swipeMode != 0) {
                        // 屏幕高度的 60% 走完全程：行程太长手指要挪很远，太短又会误调
                        val span = (v.height * 0.6f).coerceAtLeast(1f)
                        val target = (swipeBase - dy / span).coerceIn(0f, 1f)
                        if (swipeMode == SWIPE_BRIGHTNESS) setBrightness(target) else setVolume(target)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(startBoost)
                    if (boosting) {
                        // 长按加速松手：恢复原速，这次不算点击
                        endBoost()
                    } else if (swipeMode != 0) {
                        // 滑动结束：不算点击，只把提示和自动隐藏的计时收尾
                        swipeMode = 0
                        handler.removeCallbacks(hideGestureHud)
                        handler.postDelayed(hideGestureHud, 600)
                        handler.postDelayed(hideUi, HIDE_UI_DELAY_MS)
                    } else {
                        val dx = kotlin.math.abs(e.x - touchDownX)
                        val dy = kotlin.math.abs(e.y - touchDownY)
                        val held = SystemClock.uptimeMillis() - touchDownAt
                        if (dx < dp(24) && dy < dp(24) && held < 400) {
                            val w = v.width.coerceAtLeast(1)
                            val ratio = e.x / w
                            val now = SystemClock.uptimeMillis()
                            val doubleTap = now - lastTapAt < 280 &&
                                kotlin.math.abs(e.x - lastTapX) < dp(72) &&
                                kotlin.math.abs(e.y - lastTapY) < dp(72)
                            when {
                                doubleTap && ratio <= 0.3f -> {
                                    lastTapAt = 0L
                                    seekBy(-10_000L)
                                }
                                doubleTap && ratio >= 0.7f -> {
                                    lastTapAt = 0L
                                    seekBy(10_000L)
                                }
                                // 中间 40% 的竖带算「点中间」，两侧各 30% 用来叫出控制条
                                ratio > 0.3f && ratio < 0.7f -> {
                                    lastTapAt = now
                                    lastTapX = e.x
                                    lastTapY = e.y
                                    togglePlayPause()
                                }
                                else -> {
                                    lastTapAt = now
                                    lastTapX = e.x
                                    lastTapY = e.y
                                    toggleChrome()
                                }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(startBoost)
                    if (boosting) endBoost()
                    swipeMode = 0
                }
            }
            true
        }

        // 暂停时画面正中的播放按钮。
        // 手机上「点中间暂停」之后，继续播的入口就在手指刚点的地方 —— 不用再低头去
        // 底部控制条里找那个 16dp 的小图标。播放中它一直是隐藏的，不挡画面。
        val center = ImageView(this).apply {
            setImageBitmap(drawPauseIcon(paused = false, sizePx = dp(32)))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000)
                setStroke(dp(1), 0x4DFFFFFF)
            }
            visibility = View.GONE
            isClickable = true
            setOnClickListener { togglePlayPause() }
        }
        root.addView(center, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
        centerPlay = center

        // 片名：默认隐藏，暂停 / 操作栏 / 出错时才显示
        titleText = TextView(this).apply {
            baseText = labelFor(currentIndex)
            setText(baseText)
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(15f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(65), dp(20), dp(20), 0)
            visibility = View.GONE
        }
        root.addView(
            titleText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        if (!isTvDevice) {
            backButton = TextView(this).apply {
                text = "‹"
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(34f)
                gravity = Gravity.CENTER
                visibility = View.GONE
                isClickable = true
                contentDescription = "退出播放"
                setOnClickListener { persistProgress(); finish() }
            }.also {
                root.addView(it, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.START)
                    .apply { leftMargin = dp(8); topMargin = dp(2) })
            }
        }

        // 进度条：左播放/暂停图标 + 进度 + 时间 + 倍速标签（都不可聚焦）
        progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(3))
            visibility = View.GONE
        }
        playPauseIcon = ImageView(this).apply {
            setPadding(dp(6), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 关掉 media3 控制层之后，手机上没有别的暂停入口了，这里必须可点
            isClickable = true
            setOnClickListener { togglePlayPause() }
        }
        seekBar = MarkedSeekBar(this).apply {
            max = 1000
            // 自绘：轨道、已播段、白色拖动点都在 onDraw 里画，不再用 progressDrawable
            // 高度给 16dp：进度条本身 3dp 居中，剩下的空间留给片头/片尾刻度线和手指落点
            layoutParams = LinearLayout.LayoutParams(0, dp(16), 1f)
            isClickable = true
            // 手指拖动定位：关掉 media3 控制层之后，这条就是手机上唯一的拖动入口
            setOnTouchListener { v, e ->
                val bar = v as MarkedSeekBar
                if (v.width <= 0) return@setOnTouchListener false
                val dur = player?.duration ?: 0L
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE,
                    android.view.MotionEvent.ACTION_UP,
                    -> {
                        if (dur <= 0) return@setOnTouchListener false
                        (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                        val fraction = bar.fractionAt(e.x)
                        val target = (dur * fraction).toLong()
                        bar.progress = (fraction * 1000).toInt()
                        // 拖动过程中时间跟着走，松手才真正跳
                        elapsedText.text = formatTime(target)
                        timeText.text = formatTime(dur)
                        when (e.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                // 先掐住 500ms 的轮询，否则手指还在拖、进度条已经被
                                // updateProgress() 拉回播放位置，看起来就是「拖不动 / 来回弹」
                                seekingBar = true
                                handler.removeCallbacks(uiTick)
                                handler.removeCallbacks(hideUi)
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                seekingBar = false
                                // 记下目标位置：seek 落地前 currentPosition 还是旧值，
                                // 不记的话轮询会把进度条拽回去跳一下
                                pendingSeek = target
                                handler.removeCallbacks(clearPendingSeek)
                                handler.postDelayed(clearPendingSeek, 1_500)
                                player?.seekTo(currentIndex, target)
                                showProgress()
                            }
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        seekingBar = false
                        showProgress()
                        true
                    }
                    else -> false
                }
            }
        }
        elapsedText = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(13f)
            // 压在亮画面上也要看得清
            setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
            setPadding(0, 0, dp(10), 0)
        }
        timeText = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(13f)
            setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
            setPadding(dp(10), 0, 0, 0)
        }
        // 参考图：左侧当前时间 → 中间进度条 → 右侧总时长。
        // 电视端多一个播放/暂停图标，放到最右边，避免把「当前时间」挤出左端。
        progressRow.addView(elapsedText)
        progressRow.addView(seekBar)
        progressRow.addView(timeText)
        if (isTvDevice) progressRow.addView(playPauseIcon)

        // 底部控制条：按钮行 + 进度行合成**一整条**，共用一层从下往上的黑色渐变。
        // 以前是 6 个实心圆角块直接飘在画面上：既挡内容，又和全站「细描边 + 深色底」的语言不一致。
        // 现在整条贴在画面底部，按钮是扁平文字（轻按/聚焦才浮起一层浅浅的白），进度条同一底色，
        // 全屏模式下整条还能整体隐掉 —— 一箭双雕。
        bottomChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF0000000.toInt(), 0xA9000000.toInt(), 0x00000000),
            )
        }
        bottomChrome.addView(progressRow, LinearLayout.LayoutParams(-1, -2))
        if (!isTvDevice) {
            val touchBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(1), dp(8), dp(7))
            }
            fun addTouchAction(button: TextView) {
                button.setTextSize(13f)
                button.gravity = Gravity.CENTER
                button.setPadding(dp(2), dp(10), dp(2), dp(10))
                touchBar.addView(button, LinearLayout.LayoutParams(0, dp(43), 1f).apply {
                    rightMargin = dp(2)
                })
            }
            touchBar.addView(playPauseIcon, LinearLayout.LayoutParams(dp(46), dp(43)))
            introBarBtn = actionButton("片头") { markIntroHere() }.also {
                it.setOnLongClickListener { showSkipMarks(); true }
                addTouchAction(it)
                it.background = GradientDrawable().apply {
                    setColor(0x66000000)
                    cornerRadius = dp(20).toFloat()
                }
            }
            outroBarBtn = actionButton("片尾") { markOutroHere() }.also {
                it.setOnLongClickListener { showSkipMarks(); true }
                addTouchAction(it)
                it.background = GradientDrawable().apply {
                    setColor(0x66000000)
                    cornerRadius = dp(20).toFloat()
                }
            }
            speedBarBtn = actionButton(speedButtonLabel()) { showSpeedMenu() }
            addTouchAction(speedBarBtn!!)
            addTouchAction(actionButton("选集") { showEpisodePicker() })
            addTouchAction(fullscreenButton())
            addTouchAction(actionButton("更多") { showMoreMenu() })
            updateSkipButtons()
            bottomChrome.addView(
                touchBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            bottomChrome,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        // 底部操作栏（默认隐藏）
        actionBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 选集放在第一位：按下键 → 焦点就落在这里，两下就能跳到任意一集
        actionBar.addView(actionButton("选集") { showEpisodePicker() })
        actionBar.addView(actionButton("跳过") { showSkipMarks() })
        actionBar.addView(actionButton("下集") {
            if (player?.hasNextMediaItem() == true) {
                player?.seekToNextMediaItem()
            } else {
                toast("已是最后一集")
            }
            showActions()
        })
        actionBar.addView(actionButton("上集") {
            if (player?.hasPreviousMediaItem() == true) {
                player?.seekToPreviousMediaItem()
            } else {
                toast("已是第一集")
            }
            showActions()
        })
        actionBar.addView(actionButton("刷新") {
            player?.let {
                it.seekTo(currentIndex, it.currentPosition)
                it.prepare()
            }
            toast("已重新加载")
            showActions()
        })
        actionBar.addView(actionButton("重播") {
            player?.seekTo(currentIndex, 0L)
            player?.play()
            showActions()
        })
        speedActionBtn = actionButton("倍速 X1.00") { showSpeedMenu() }
        actionBar.addView(speedActionBtn)
        actionBar.addView(actionButton("解码") { showDecodeMenu() })
        actionBar.addView(actionButton("比例") { showAspectMenu() })
        actionBar.addView(fullscreenButton())
        actionBar.addView(actionButton("锁屏") { lockScreen() })
        actionBar.addView(actionButton("音轨/字幕") { showTrackMenu() })
        // 开启后台播放后，返回键只是退到后台；要真正停下来用这个
        actionBar.addView(actionButton("退出") {
            persistProgress()
            finish()
        })

        actionScroll = HorizontalScrollView(this).apply {
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(dp(48), 0, dp(48), 0)
            addView(actionBar)
            visibility = View.GONE
        }
        root.addView(
            actionScroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply { bottomMargin = dp(48) },
        )

        // 缓冲提示 + 网速
        bufferingText = TextView(this).apply {
            setText("缓冲中…")
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            setTextSize(18f)
            visibility = View.VISIBLE
        }
        speedText = TextView(this).apply {
            setText("测速中…")
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
            setTextSize(13f)
            setPadding(0, dp(6), 0, 0)
            visibility = View.VISIBLE
        }
        val bufferingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bufferingText)
            addView(speedText)
        }
        root.addView(
            bufferingBox,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        if (!isTvDevice) addLockButton()

        setContentView(root)
        applyPlayerSafeArea(root)
        if (Build.VERSION.SDK_INT >= 33) {
            backGestureCallback = OnBackInvokedCallback { onBackPressed() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, it,
                )
            }
        }

        if (urls.isEmpty()) {
            Toast.makeText(this, "没有播放地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 起播缓冲按设置分档：minBufferMs 决定"等多久才开播"，
        // 均衡 1.5 秒 ≈ 公共采集站的正常首片耗时；快速档牺牲抗抖动换秒开。
        val (minBuf, maxBuf, playBuf, rebootBuf) = when (prefs.bufferMode) {
            0 -> listOf(8_000, 25_000, 500, 1_000)
            2 -> listOf(40_000, 120_000, 3_000, 6_000)
            else -> listOf(20_000, 60_000, 1_500, 3_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, playBuf, rebootBuf)
            .build()

        // 分片级磁盘缓存：看过的片段落盘，回拖/重看不再重复下载
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("CloudScreen-Android")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(PlaybackCache.get(this))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory =
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory)

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setRenderersFactory(buildRenderers())
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addAnalyticsListener(statsListener)
                exo.setPlaybackSpeed(prefs.playbackSpeed)
                exo.pauseAtEndOfMediaItems = !prefs.autoNext

                exo.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        currentIndex = exo.currentMediaItemIndex
                        hasError = false
                        baseText = labelFor(currentIndex)
                        // 每集字幕是分开存的，换集时把「当前挂的是哪份」同步过来，
                        // 不然菜单里还显示着上一集的字幕名
                        loadSubtitleForCurrent()
                        if (!hasError) {
                            titleText.text = baseText
                            titleText.setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
                        }
                        bufferingText.visibility = View.VISIBLE
                        updateChrome()
                        if (urls.size > 1 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            toast("自动播放：第 ${currentIndex + 1} 集")
                        }
                        // 每一集（含起播、自动连播、手动切集）都过一遍片头跳过。
                        // 延迟给 setMediaItems 的起播位置落地，否则刚 seek 到续播点就被误判成"在片头"
                        handler.postDelayed({ applyAutoIntroSkip() }, 600)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val buffering = playbackState == Player.STATE_BUFFERING
                        bufferingText.visibility = if (buffering) View.VISIBLE else View.GONE
                        speedText.visibility = if (buffering) View.VISIBLE else View.GONE
                        handler.removeCallbacks(speedTick)
                        if (buffering) {
                            lastBytes = 0L
                            lastMs = 0L
                            handler.post(speedTick)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            prefs.clearProgress(currentUrl())
                            // 跨源那份也要清，否则换源重看会从片尾开始
                            prefs.clearEpisodeProgress(skipKey(), currentIndex)
                        }
                        updateChrome()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayPauseIcon()
                        updateChrome()
                        if (!isPlaying) showProgress()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val decodeFail = error.errorCodeName.contains("DECODER", true) ||
                            error.errorCodeName.contains("DECODING", true)
                        // 硬解挂了先别急着让用户换源：同一个片源换软解往往就能放。
                        // 只把「这部剧」标成软解（不写全局 decodeMode，否则所有片源都会变卡），
                        // 记下当前位置后重启播放页，起来能接着放；标记生效后不会再进这个分支。
                        if (decodeFail && prefs.decodeMode != 1 && !prefs.softDecodeFor(skipKey())) {
                            prefs.setSoftDecodeFor(skipKey(), true)
                            // 出错时播放器已经是 IDLE，persistProgress 会自己早退，
                            // 这里直接写当前位置，重启后能接着放而不是从头上开始
                            val pos = player?.currentPosition ?: 0L
                            if (pos > 3_000L) prefs.saveProgress(currentUrl(), pos)
                            toast("硬解失败，本剧已改用软解，正在重试…")
                            handler.postDelayed({ recreate() }, 300)
                            return
                        }
                        val msg = if (decodeFail) {
                            "解码失败：这个片源可能是 H.265 / 不支持的编码 · 按返回键换源"
                        } else {
                            "播放失败：${error.errorCodeName} · 按返回键换源"
                        }
                        hasError = true
                        baseText = msg
                        titleText.text = msg
                        titleText.setTextColor(android.graphics.Color.parseColor("#fca5a5"))
                        bufferingText.visibility = View.GONE
                        speedText.visibility = View.GONE
                        updateChrome()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        applyOrientationForVideo(videoSize.width, videoSize.height)
                    }
                })

                val items = buildMediaItems()
                val resume = resumePositionOf(currentUrl(), currentIndex)
                val threshold = prefs.resumeThresholdSeconds * 1000L
                exo.setMediaItems(items, currentIndex, if (resume > threshold) resume else 0L)
                exo.prepare()
                if (resume > threshold) {
                    toast("从上次位置继续（${formatTime(resume)}）")
                }
                exo.playWhenReady = true
            }

        updateSpeedAction()
        if (kotlin.math.abs(prefs.playbackSpeed - 1f) > 0.01f) {
            toast("当前倍速 ${speedLabel(prefs.playbackSpeed)}（在上键菜单里可改回 X1.00）")
        }
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
        updatePlayPauseIcon()
        updateChrome()
        // 只有一个播放地址时（从历史 / 单集直链进来），起播 4 秒后静默回查整季。
        // 不回查的话 urls 永远只有一条，片尾跳过会把「第 1033 集」当成最后一集而不跳。
        // 放 4 秒是为了避开起播那几秒的解码/缓冲高峰。
        handler.postDelayed({ silentResolveEpisodes() }, 4_000)
        // 起播后 3 秒把控制条收起来（暂停时不收）：进页面时能看到按钮在哪，之后只剩画面
        handler.postDelayed(hideUi, 3_000)
    }

    /* ------------------------------------------------------------ 显示逻辑 */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 播放页安全区：横屏时挖孔/机身圆角会压住进度条两端。
     *
     * 只给覆盖层（片名、控制条）加内边距，画面本身继续铺满 —— 视频被缩进一圈
     * 反而更难看。沉浸式下系统栏是隐藏的，所以这里只处理 displayCutout。
     */
    private fun applyPlayerSafeArea(root: View) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        root.setOnApplyWindowInsetsListener { _, insets ->
            val cut = insets.displayCutout
            val left = cut?.safeInsetLeft ?: 0
            val right = cut?.safeInsetRight ?: 0
            val bottom = cut?.safeInsetBottom ?: 0
            if (::bottomChrome.isInitialized) bottomChrome.setPadding(left, 0, right, bottom)
            if (::titleText.isInitialized) titleText.setPadding(48 + left, 28, 48 + right, 0)
            insets
        }
        root.requestApplyInsets()
    }

    /**
     * 竖屏片源自动切竖屏。
     *
     * manifest 里默认 landscape（绝大多数剧集是横屏），但竖屏短剧/短视频在横屏下
     * 两侧会留出一条画面、其余全是黑边。按宽高比判断后切到对应方向，用户不用手动转。
     */
    private fun applyOrientationForVideo(w: Int, h: Int) {
        if (orientationLocked) return
        if (w <= 0 || h <= 0) return
        val target = if (h > w * 1.08f) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (requestedOrientation != target) {
            runCatching { requestedOrientation = target }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /* ------------------------------------------------------ 片尾自动连播提示 */

    /** 片尾多少毫秒开始浮出倒计时。3 秒太短（数字只跳两下），6 秒留得住反应时间 */
    private val nextHintWindowMs = 6_000L

    private fun updateNextHint() {
        if (locked) {
            hideNextHint()
            return
        }
        val p = player
        val canNext = prefs.autoNext && urls.size > 1 && currentIndex + 1 in urls.indices
        if (p == null || !canNext || p.playbackState != Player.STATE_READY || !p.isPlaying) {
            hideNextHint()
            return
        }
        if (nextCancelledFor == currentIndex) {
            hideNextHint()
            return
        }
        val duration = p.duration
        if (duration <= 0) {
            hideNextHint()
            return
        }
        val remain = duration - p.currentPosition
        if (remain < 0 || remain > nextHintWindowMs) {
            hideNextHint()
            return
        }
        val secs = ((remain + 999) / 1000).toInt().coerceAtLeast(1)
        showNextHint("${secs} 秒后播放第 ${currentIndex + 2} 集 · 返回取消 · 下键选集")
    }

    private fun showNextHint(text: String) {
        if (!::rootBox.isInitialized) return
        val tv = nextHint ?: TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(15f)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                setColor(0xD910141C.toInt())
                cornerRadius = dp(20).toFloat()
            }
            rootBox.addView(
                this,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = dp(120) },
            )
        }.also { nextHint = it }
        tv.text = text
        tv.visibility = View.VISIBLE
    }

    private fun hideNextHint() {
        nextHint?.visibility = View.GONE
    }

    /**
     * 取消这一集的自动连播：让播放器在本集播完时停下（pauseAtEndOfMediaItems）。
     * 只对当前这一集生效 —— 手动切到下一集后自动恢复连播，不会「取消一次，永久关闭」。
     */
    private fun cancelAutoNext() {
        nextCancelledFor = currentIndex
        player?.pauseAtEndOfMediaItems = true
        hideNextHint()
        toast("已取消自动播放，本集播完会停下")
    }

    /** 换集/重播时重置：连播能力跟着当集状态走 */
    private fun resetAutoNextState() {
        nextCancelledFor = -1
        outroSkipGraceUntil = 0L
        player?.pauseAtEndOfMediaItems = !prefs.autoNext
        hideNextHint()
    }

    /* ------------------------------------------------------ 片头 / 片尾跳过 */

    /**
     * 跳过标记挂在「剧名」上：同一部剧的每一集共用一组秒数。
     * 单集视频（直播、电影）没有剧名时退化成 label，但那种场景本来也不需要跳片头。
     */
    private fun skipKey(): String {
        val key = seriesTitle.trim().ifEmpty { seriesName.trim().ifEmpty { singleLabel.trim() } }
        return key
    }

    /**
     * 续播位置：先按播放地址找，找不到再按「剧名 + 集号」兜底。
     *
     * 采集站换源就是换 URL，只按地址记的话换完源同一集又要从头拖 ——
     * 而换源恰恰是因为原源卡了，这个场景很常见。
     */
    private fun resumePositionOf(url: String, index: Int): Long {
        if (!prefs.resumeEnabled) return 0L
        val byUrl = prefs.progressOf(url)
        if (byUrl > 0L) return byUrl
        return prefs.episodeProgressOf(skipKey(), index)
    }

    /**
     * 起播 / 切集后自动跳片头。
     *
     * 只在「当前位置还在片头区间内」时才跳 —— 续播点已经在 20 分钟时不能被拽回片头。
     * 延迟 600ms 执行：要给 setMediaItems(…, startPosition) 的 seek 留出落地时间。
     */
    private fun applyAutoIntroSkip() {
        if (!prefs.skipIntroEnabled) return
        val key = skipKey()
        if (key.isEmpty()) return
        val end = prefs.introEndOf(key)
        if (end <= 0) return
        val p = player ?: return
        if (p.currentMediaItemIndex != currentIndex) return
        val pos = p.currentPosition
        // 余量 1.5 秒：手动往前拖一点重看片头结尾时不要再被弹走
        if (pos >= end - 1_500) return
        // 标记来自别的源（时长不同的版本）时可能超过本集总长，直接判为无效，免得一跳到底
        if (p.duration > 0 && end > p.duration - 60_000) return
        p.seekTo(currentIndex, end)
        toast("已跳过片头（${formatTime(end)} 处开始）")
    }

    /**
     * 到达片尾开始点时直接切下一集。
     *
     * 放在 400ms 的 nextTick 里跑，而不是等 STATE_ENDED：片尾曲通常有几十秒，
     * 等自然播完再切等于让用户白看这段；同时这也让「跳过片尾」和「自动连播」互不干扰 ——
     * 用户按返回取消过连播的那一集，这里也不再自动跳。
     */
    private fun checkOutroSkip() {
        if (!prefs.skipOutroEnabled || !prefs.autoNext) return
        if (nextCancelledFor == currentIndex) return
        if (currentUrl() == outroMarkedUrl) return
        if (System.currentTimeMillis() < outroSkipGraceUntil) return
        val key = skipKey()
        if (key.isEmpty()) return
        val start = prefs.outroStartOf(key)
        if (start <= 0) return
        val p = player ?: return
        if (p.playbackState != Player.STATE_READY || !p.isPlaying) return
        if (p.currentPosition < start) return
        // 片尾点比总时长还靠后（换了个更短的源）时不跳，否则一进去就没了
        if (p.duration > 0 && start > p.duration - 5_000) return
        if (currentIndex + 1 !in urls.indices) {
            // 列表只有一条时，很可能只是「还没回查到整季」，并不真的是最后一集。
            // 这里再补一次静默回查：拿到了列表，下一帧就会正常跳片尾。
            silentResolveEpisodes()
            return
        }
        val next = currentIndex + 1
        switchEpisode(next)
        toast("已跳过片尾，播放第 ${next + 1} 集")
    }

    private fun markIntroHere() {
        val key = skipKey()
        val pos = player?.currentPosition ?: 0L
        if (key.isEmpty() || pos < 1_000L) {
            toast("播放到片头结束处，再点片头")
            return
        }
        prefs.saveIntroEnd(key, pos)
        prefs.skipIntroEnabled = true
        updateSeekMarks(player?.duration ?: 0L)
        updateSkipButtons()
        toast("已记片头 ${formatTime(pos)}，本剧后续集数自动跳过")
    }

    private fun markOutroHere() {
        val key = skipKey()
        val p = player ?: return
        val pos = p.currentPosition
        if (key.isEmpty() || pos < 1_000L || (p.duration > 0 && pos >= p.duration - 5_000L)) {
            toast("播放到片尾开始处，再点片尾")
            return
        }
        prefs.saveOutroStart(key, pos)
        prefs.skipOutroEnabled = true
        // 此集刚标记时不立即切集；下一集开始恢复自动跳片尾。
        outroMarkedUrl = currentUrl()
        updateSeekMarks(p.duration)
        updateSkipButtons()
        toast("已记片尾 ${formatTime(pos)}，本剧后续集数自动跳过")
    }

    private fun updateSkipButtons() {
        val key = skipKey()
        introBarBtn?.setTextColor(if (prefs.introEndOf(key) > 0) 0xFFB7E981.toInt() else 0xFFE5E7EB.toInt())
        outroBarBtn?.setTextColor(if (prefs.outroStartOf(key) > 0) 0xFFB7E981.toInt() else 0xFFE5E7EB.toInt())
    }

    /**
     * 「跳过」面板：把当前位置记成片头结束 / 片尾开始，或清除记录。
     *
     * 为什么不做"自动识别片头"：采集站给的 m3u8 没有章节信息，靠黑帧/音量检测在
     * 这种转码过一遍的源上误判率很高，用户会碰到"每集都被跳掉前 3 分钟"。
     * 标一次、整部剧复用，是所有国产 TV 端 App 的通行做法，也最可控。
     */
    private fun showSkipMarks() {
        val key = skipKey()
        if (key.isEmpty()) {
            toast("这个视频没有剧名，无法按剧记录片头片尾")
            return
        }
        val p = player
        val pos = p?.currentPosition ?: 0L
        val duration = p?.duration ?: 0L
        val intro = prefs.introEndOf(key)
        val outro = prefs.outroStartOf(key)

        lateinit var dlg: AlertDialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        box.addView(TextView(this).apply {
            text = "《$key》\n当前位置 ${formatTime(pos)}" +
                if (duration > 0) " / ${formatTime(duration)}" else ""
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(13f)
            setPadding(0, 0, 0, dp(10))
        })
        box.addView(hintLine("片头结束：" + if (intro > 0) formatTime(intro) else "未设置"))
        box.addView(skipAction("记当前位置为片头结束") {
            prefs.saveIntroEnd(key, pos)
            updateSeekMarks(player?.duration ?: 0L)
            updateSkipButtons()
            toast("片头结束已记为 ${formatTime(pos)}")
        })
        if (intro > 0) {
            box.addView(skipAction("试跳片头（跳到 ${formatTime(intro)}）") {
                player?.seekTo(currentIndex, intro)
                dlg.dismiss()
            })
        }
        box.addView(hintLine("片尾开始：" + if (outro > 0) formatTime(outro) else "未设置"))
        box.addView(skipAction("记当前位置为片尾开始") {
            prefs.saveOutroStart(key, pos)
            updateSeekMarks(player?.duration ?: 0L)
            outroMarkedUrl = currentUrl()
            updateSkipButtons()
            toast("片尾开始已记为 ${formatTime(pos)}，之后到这就会自动下一集")
        })
        box.addView(skipAction(
            "自动跳片头：" + (if (prefs.skipIntroEnabled) "开" else "关"),
        ) {
            prefs.skipIntroEnabled = !prefs.skipIntroEnabled
            toast(if (prefs.skipIntroEnabled) "自动跳片头已开启" else "自动跳片头已关闭")
        })
        box.addView(skipAction(
            "自动跳片尾：" + (if (prefs.skipOutroEnabled) "开" else "关"),
        ) {
            prefs.skipOutroEnabled = !prefs.skipOutroEnabled
            toast(if (prefs.skipOutroEnabled) "自动跳片尾已开启" else "自动跳片尾已关闭")
        })
        if (intro > 0 || outro > 0) {
            box.addView(skipAction("清除本剧的片头片尾记录") {
                prefs.clearSkipMarks(key)
                updateSeekMarks(player?.duration ?: 0L)
                updateSkipButtons()
                toast("已清除")
            })
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("片头 / 片尾")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    private fun hintLine(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(0xFFE5E7EB.toInt())
        setTextSize(14f)
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun skipAction(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        setText(label)
        setTextColor(0xFFD9F5A9.toInt())
        setTextSize(15f)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { onClick() }
        background = GradientDrawable().apply {
            setColor(0xFF141A26.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0xFF2A3648.toInt())
        }
    }

    /** 片名可见性：出错 / 暂停 / 控制条或操作栏显示中 */
    private fun updateChrome() {
        // 中央播放按钮跟着「是否暂停」走，所以放在最前面：锁定 / 出错时它自己会收掉
        updateCenterPlay()
        if (!::titleText.isInitialized) return
        if (locked) {
            // 锁屏期间任何操作层都不许自己冒出来
            titleText.visibility = View.GONE
            backButton?.visibility = View.GONE
            return
        }
        val p = player
        val paused = p != null && !p.playWhenReady && p.playbackState != Player.STATE_IDLE
        val chromeVisible = (::progressRow.isInitialized && progressRow.visibility == View.VISIBLE) ||
            isActionBarVisible()
        val visible = if (hasError || paused || chromeVisible) View.VISIBLE else View.GONE
        titleText.visibility = visible
        backButton?.visibility = visible
    }

    /**
     * 中央播放按钮只在「暂停」时出现：播放中、缓冲中、锁屏、出错都不显示。
     * 播完停在最后一帧也算暂停（pauseAtEndOfMediaItems），这时同样给一个播放键。
     */
    private fun updateCenterPlay() {
        val icon = centerPlay ?: return
        // 画中画小窗里不要这个大播放键，挡住画面
        if (inPipMode()) {
            icon.visibility = View.GONE
            return
        }
        val p = player
        val paused = p != null && !p.playWhenReady && p.playbackState != Player.STATE_IDLE
        icon.visibility = if (paused && !locked && !hasError) View.VISIBLE else View.GONE
    }

    private fun updateProgress() {
        val p = player ?: return
        // 手指正在拖进度条：这段谁也别碰进度和时间，交给 OnTouchListener
        if (seekingBar) return
        val dur = p.duration
        // seek 刚发出、还没落地时用 pendingSeek，避免进度条先回跳再跳到目标位置
        val pos = if (pendingSeek >= 0) pendingSeek else p.currentPosition
        if (dur > 0) {
            seekBar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            // 已缓冲部分用白色画在蓝色之后（secondaryProgress）
            seekBar.secondaryProgress = (p.bufferedPosition * 1000 / dur).toInt().coerceIn(0, 1000)
            elapsedText.text = formatTime(pos)
            timeText.text = formatTime(dur)
        } else {
            seekBar.progress = 0
            seekBar.secondaryProgress = 0
            elapsedText.text = formatTime(pos)
            timeText.text = "--:--"
        }
        updatePlayPauseIcon()
        updateSeekMarks(dur)
    }

    /** 把片头结束点 / 片尾开始点换算成比例，画成进度条上的两道刻度 */
    private fun updateSeekMarks(duration: Long) {
        val key = skipKey()
        if (duration <= 0L || key.isEmpty()) {
            seekBar.introFraction = -1f
            seekBar.outroFraction = -1f
            return
        }
        val intro = prefs.introEndOf(key)
        val outro = prefs.outroStartOf(key)
        seekBar.introFraction = if (intro in 1 until duration) intro.toFloat() / duration else -1f
        seekBar.outroFraction = if (outro in 1 until duration) outro.toFloat() / duration else -1f
    }

    /** 用 Canvas 画播放/暂停图标，省掉两张 png */
    private fun drawPauseIcon(paused: Boolean, sizePx: Int = dp(16)): android.graphics.Bitmap {
        val size = sizePx
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#e5e7eb")
            style = android.graphics.Paint.Style.FILL
        }
        if (paused) {
            val w = size * 0.20f
            val gap = size * 0.18f
            val top = size * 0.10f
            c.drawRect(size / 2f - gap / 2f - w, top, size / 2f - gap / 2f, size - top, paint)
            c.drawRect(size / 2f + gap / 2f, top, size / 2f + gap / 2f + w, size - top, paint)
        } else {
            val path = android.graphics.Path()
            path.moveTo(size * 0.26f, size * 0.12f)
            path.lineTo(size * 0.84f, size * 0.5f)
            path.lineTo(size * 0.26f, size * 0.88f)
            path.close()
            c.drawPath(path, paint)
        }
        return bmp
    }

    private fun updatePlayPauseIcon() {
        if (!::playPauseIcon.isInitialized) return
        val playing = player?.isPlaying == true
        playPauseIcon.setImageBitmap(drawPauseIcon(paused = playing, sizePx = dp(23)))
    }

    private fun formatSpeed(bitsPerSecond: Long): String {
        if (bitsPerSecond <= 0L) return "测速中…"
        val kbps = bitsPerSecond / 1000.0
        return if (kbps < 1000) "${kbps.toInt()} KB/s"
        else String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1000.0)
    }

    /* ------------------------------------------------------------ 控制条 */

    private fun showProgress() {
        if (locked) return
        if (::bottomChrome.isInitialized) bottomChrome.visibility = View.VISIBLE
        progressRow.visibility = View.VISIBLE
        updateProgress()
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        if (!isActionBarVisible()) {
            handler.postDelayed(hideUi, HIDE_UI_DELAY_MS)
        }
        updateChrome()
    }

    private fun isActionBarVisible(): Boolean =
        ::actionScroll.isInitialized && actionScroll.visibility == View.VISIBLE

    /**
     * 点击画面左右两侧：控制条可见就收起，不可见就唤出。
     *
     * 收起是自己动手收（不等 4 秒超时），但**暂停时不收**：暂停状态下控制条是唯一的
     * 继续播放入口，收掉了就只剩点中间这一条路，容易让人以为卡住了。
     */
    private fun toggleChrome() {
        val visible = (::bottomChrome.isInitialized && bottomChrome.visibility == View.VISIBLE) ||
            (::progressRow.isInitialized && progressRow.visibility == View.VISIBLE)
        if (!visible) {
            showProgress()
            return
        }
        val p = player
        val paused = p != null && !p.playWhenReady && p.playbackState != Player.STATE_IDLE
        if (paused) {
            // 暂停中再点侧边 → 只把它重新亮一次（顺便续上自动隐藏的计时）
            showProgress()
            return
        }
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(uiTick)
        if (isActionBarVisible()) setActionBarVisible(false)
        progressRow.visibility = View.GONE
        if (::bottomChrome.isInitialized) bottomChrome.visibility = View.GONE
        updateChrome()
    }

    /* -------------------------------------------------- 亮度 / 音量手势 */

    /** 当前亮度（0~1）。窗口没设过是 -1（跟随系统），那就从系统设置里换算一个当前值当基准 */
    private fun currentBrightness(): Float {
        val w = window.attributes.screenBrightness
        if (w >= 0f) return w.coerceIn(0.02f, 1f)
        val sys = runCatching {
            android.provider.Settings.System.getInt(
                contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
            )
        }.getOrDefault(128)
        return (sys / 255f).coerceIn(0.02f, 1f)
    }

    private fun setBrightness(value: Float) {
        val level = value.coerceIn(0.02f, 1f)
        val attrs = window.attributes
        attrs.screenBrightness = level
        window.attributes = attrs
        // 记下来，下次进播放页直接恢复（窗口亮度不跨会话）
        prefs.playerBrightness = level
        showGestureHud("亮度 ${(level * 100).toInt()}%")
    }

    private fun audioManager(): android.media.AudioManager? =
        getSystemService(AUDIO_SERVICE) as? android.media.AudioManager

    private fun setVolume(value: Float) {
        val am = audioManager() ?: return
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = (value * max).toInt().coerceIn(0, max)
        // 自己调就不要再让系统弹出那个音量条，否则和我们的 HUD 叠在一起
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, index, 0)
        showGestureHud("音量 ${index * 100 / max}%")
    }

    /** 松手恢复用户自己设定的倍速（不一定是 1.0，他可能本来就选着 1.5 倍在看） */
    private fun endBoost() {
        if (!boosting) return
        boosting = false
        player?.setPlaybackSpeed(prefs.playbackSpeed)
        handler.removeCallbacks(hideGestureHud)
        handler.postDelayed(hideGestureHud, 400)
        updateSpeedAction()
    }

    /** 顶部居中的小胶囊：滑动时显示「亮度 62%」这类提示，900ms 后自己消失 */
    private fun showGestureHud(text: String) {
        val hud = gestureHud ?: TextView(this).apply {
            setTextColor(0xFFE5E7EB.toInt())
            setTextSize(14f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
            visibility = View.GONE
        }.also { tv ->
            gestureHud = tv
            rootBox.addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { topMargin = dp(72) },
            )
        }
        hud.text = text
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideGestureHud)
        handler.postDelayed(hideGestureHud, 900)
    }

    private fun setActionBarVisible(visible: Boolean) {
        if (!::actionScroll.isInitialized) return
        actionScroll.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            actionBar.clearFocus()
        }
    }

    /** 下键 / 菜单键：呼出操作栏，焦点落到第一个按钮 */
    /**
     * 「更多」：把低频操作收进一个列表。
     *
     * 手机上点原来的「设置」会拉出一整排 12 个按钮，和底部按钮条同时存在 —— 屏幕上两套操作层，
     * 这才是"冗余"的来源。现在一次只展开一屏列表，选完就走。
     */
    private fun showMoreMenu() {
        val items = arrayOf(
            "返回（退出播放）",
            "重新加载这一集",
            "从头重播",
            "上一集",
            "下一集",
            "倍速：${speedLabel(prefs.playbackSpeed)}",
            "解码方式：${decodeLabel()}",
            "显示比例",
            "音轨 / 字幕",
            sleepLabel(),
            if (orientationLocked) "方向：已锁定（点击解锁）" else "方向：自动旋转（点击锁定）",
            "画中画（缩成小窗继续播）",
            subtitleLabel(),
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("更多操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        persistProgress()
                        finish()
                    }
                    1 -> {
                        player?.let {
                            it.seekTo(currentIndex, it.currentPosition)
                            it.prepare()
                        }
                        toast("已重新加载")
                        showProgress()
                    }
                    2 -> {
                        player?.seekTo(currentIndex, 0L)
                        player?.play()
                        showProgress()
                    }
                    3 -> {
                        if (player?.hasPreviousMediaItem() == true) player?.seekToPreviousMediaItem()
                        else toast("已是第一集")
                        showProgress()
                    }
                    4 -> {
                        if (player?.hasNextMediaItem() == true) player?.seekToNextMediaItem()
                        else toast("已是最后一集")
                        showProgress()
                    }
                    5 -> showSpeedMenu()
                    6 -> showDecodeMenu()
                    7 -> showAspectMenu()
                    8 -> showTrackMenu()
                    9 -> showSleepMenu()
                    10 -> toggleOrientationLock()
                    11 -> enterPip()
                    12 -> showSubtitleMenu()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /* ------------------------------------------------------- 定时关闭 / 方向锁 */

    private fun sleepLabel(): String {
        if (stopAtEpisodeEnd) return "定时关闭：播完本集就停"
        if (sleepUntil <= 0L) return "定时关闭"
        val left = (sleepUntil - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        val minutes = (left + 59_999L) / 60_000L
        return "定时关闭：还剩 $minutes 分钟"
    }

    private fun showSleepMenu() {
        val options = arrayOf(
            "关闭定时",
            "播完这一集就停",
            "30 分钟后暂停",
            "60 分钟后暂停",
            "90 分钟后暂停",
        )
        AlertDialog.Builder(this)
            .setTitle("定时关闭")
            .setItems(options) { _, which ->
                sleepUntil = 0L
                stopAtEpisodeEnd = false
                when (which) {
                    1 -> stopAtEpisodeEnd = true
                    2 -> sleepUntil = SystemClock.uptimeMillis() + 30 * 60_000L
                    3 -> sleepUntil = SystemClock.uptimeMillis() + 60 * 60_000L
                    4 -> sleepUntil = SystemClock.uptimeMillis() + 90 * 60_000L
                }
                val msg = when {
                    stopAtEpisodeEnd -> "本集播完就暂停，不会自动连播"
                    sleepUntil > 0L -> "已设定：${options[which]}自动暂停"
                    else -> "已取消定时关闭"
                }
                toast(msg)
                showProgress()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 到点就暂停，并把控制条亮出来（屏幕暗着的时候得让用户知道为什么停了） */
    private fun checkSleepTimer() {
        if (sleepUntil <= 0L) return
        if (SystemClock.uptimeMillis() < sleepUntil) return
        sleepUntil = 0L
        player?.pause()
        toast("定时关闭时间到，已暂停播放")
        showProgress()
    }

    /**
     * 「播完本集就停」：在片尾最后 0.4 秒自己暂停。
     *
     * 不用 `pauseAtEndOfMediaItems` —— 那是全局开关，改了之后下一集也不会自动播；
     * 这里只在这一集末尾拦一次，拦完就把标记清掉，对后续没有任何影响。
     */
    private fun checkStopAtEpisodeEnd() {
        if (!stopAtEpisodeEnd) return
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0L) return
        if (dur - p.currentPosition > 400L) return
        stopAtEpisodeEnd = false
        p.pause()
        toast("本集播完，已按定时关闭暂停")
        showProgress()
    }

    private fun toggleOrientationLock() {
        orientationLocked = !orientationLocked
        if (!orientationLocked) {
            // 解锁时立刻按当前画面比例归位，不用等下一次 onVideoSizeChanged
            val v = player?.videoSize
            applyOrientationForVideo(v?.width ?: 0, v?.height ?: 0)
        }
        toast(if (orientationLocked) "方向已锁定" else "方向已解锁（按画面自动旋转）")
        showProgress()
    }

    /* ------------------------------------------------------------- 画中画 */

    private fun inPipMode(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode

    /** 缩成小窗继续播：边看边刷别的。返回键/Home 之后再点「画中画」也能进 */
    private fun enterPip() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            toast("系统版本不支持画中画（需要 Android 7.0 以上）")
            return
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } else {
                @Suppress("DEPRECATION")
                enterPictureInPictureMode()
            }
        } catch (t: Throwable) {
            toast("当前设备不支持画中画")
            return
        }
        // 系统直接忽略时不会有异常，只会在几百毫秒后还是原样 —— 那时再提示一次
        handler.postDelayed({
            if (!inPipMode() && !isFinishing) toast("未能进入画中画，可能被系统或厂商限制")
        }, 600)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // 小窗里不需要任何覆盖层
            handler.removeCallbacks(hideUi)
            handler.removeCallbacks(uiTick)
            handler.removeCallbacks(hideActions)
            if (::progressRow.isInitialized) progressRow.visibility = View.GONE
            if (::bottomChrome.isInitialized) bottomChrome.visibility = View.GONE
            if (::titleText.isInitialized) titleText.visibility = View.GONE
            backButton?.visibility = View.GONE
            if (::actionScroll.isInitialized) actionScroll.visibility = View.GONE
            hideNextHint()
            gestureHud?.visibility = View.GONE
        } else {
            showProgress()
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updatePlayPauseIcon()
        showProgress()
    }

    private fun showActions() {
        // 手机已有贴底触控栏；不要再叠出电视遥控器专用的第二排按钮。
        if (!isTvDevice) {
            showProgress()
            return
        }
        setActionBarVisible(true)
        progressRow.visibility = View.VISIBLE
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        updateChrome()
        if (actionBar.childCount > 0) {
            actionBar.post {
                actionBar.getChildAt(0).requestFocus()
            }
        }
        handler.removeCallbacks(hideActions)
        handler.postDelayed(hideActions, HIDE_ACTIONS_DELAY_MS)
    }

    /**
     * 底部控制条上的按钮。
     *
     * 全站按钮是「深色底 + 白色描边焦点框」，但那个样式放在视频画面上会变成一排色块。
     * 这里退成扁平文字 + 状态色：常态透明，按下/聚焦才浮一层浅浅的白（25%）。
     * 视觉上属于画面而不是贴在画面上，同时保留了遥控器聚焦时「一眼看出停在哪」。
     */
    private fun actionButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(0xFFE5E7EB.toInt())
        setTextSize(15f)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val normal = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = dp(8).toFloat()
        }
        val active = GradientDrawable().apply {
            setColor(0x40FFFFFF)
            cornerRadius = dp(8).toFloat()
        }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), active)
            addState(intArrayOf(android.R.attr.state_focused), active)
            addState(intArrayOf(), normal)
        }
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { v, has ->
            (v as TextView).setTextColor(
                if (has) android.graphics.Color.WHITE else 0xFFE5E7EB.toInt(),
            )
        }
        setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.rightMargin = dp(6)
        layoutParams = lp
    }

    /** 倍速显示成 X1.00 这种形式（大写 X + 两位小数） */
    private fun speedLabel(v: Float): String =
        "X" + String.format(java.util.Locale.US, "%.2f", v)

    private fun decodeLabel(): String {
        val base = when (prefs.decodeMode) {
            1 -> "强制软解"
            2 -> "强制硬解"
            3 -> "硬解优先"
            else -> "自动"
        }
        // 让用户看得见「本剧走了软解」这件事，否则他会以为自己设的自动失效了
        val autoPick = prefs.decodeMode == 0 || prefs.decodeMode == 3
        return if (autoPick && prefs.softDecodeFor(skipKey())) "$base（本剧软解）" else base
    }

    private fun updateSpeedAction() {
        val s = player?.playbackParameters?.speed ?: prefs.playbackSpeed
        if (::speedActionBtn.isInitialized) speedActionBtn.text = "倍速 " + speedLabel(s)
        speedBarBtn?.text = speedButtonLabel(s)
    }

    /** 底部一级菜单上的倍速标签：1.0 倍时只写「倍速」，别的时候带上当前倍速 */
    private fun speedButtonLabel(speed: Float = player?.playbackParameters?.speed ?: prefs.playbackSpeed): String =
        if (kotlin.math.abs(speed - 1f) < 0.01f) "倍速" else "倍速 " + speedLabel(speed)

    private fun showSpeedMenu() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { speedLabel(it) }
        val cur = speeds.indexOfFirst {
            kotlin.math.abs(it - (player?.playbackParameters?.speed ?: 1f)) < 0.01f
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(labels.toTypedArray(), cur.coerceAtLeast(-1)) { d, which ->
                player?.setPlaybackSpeed(speeds[which])
                prefs.playbackSpeed = speeds[which]
                updateSpeedAction()
                d.dismiss()
                showProgress()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDecodeMenu() {
        // 索引和 decodeMode 的取值不是一一对应，靠 values 映射，避免以后加档位时错位
        val names = arrayOf("硬解优先（推荐）", "自动", "强制软解", "强制硬解")
        val values = intArrayOf(3, 0, 1, 2)
        val checked = values.indexOf(prefs.decodeMode.coerceIn(0, 3)).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("解码方式（下次打开视频生效）")
            .setSingleChoiceItems(names, checked) { d, which ->
                prefs.decodeMode = values[which]
                d.dismiss()
                toast("已设为：${names[which]}，重新打开视频生效")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAspectMenu() {
        val names = arrayOf("适应屏幕", "裁切铺满", "拉伸铺满")
        android.app.AlertDialog.Builder(this)
            .setTitle("显示比例")
            .setSingleChoiceItems(names, aspectMode) { d, which ->
                applyAspect(which)
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ------------------------------------------------------ 全屏 / 锁屏 */

    /**
     * 「比例」里已经有三种模式，但手机上用户想要的是「一键满屏，别让我翻菜单」。
     * 这里做成一键切换：适应 ⇄ 裁切铺满，按钮文字同步变成「还原」，随时能切回去。
     */
    private fun fullscreenButton(): TextView =
        actionButton(fullscreenLabel()) { toggleFullscreen() }.also { fullscreenButtons.add(it) }

    private fun fullscreenLabel(): String = if (pureMode) "还原" else "全屏"

    private fun toggleFullscreen() {
        if (pureMode) exitPureFullscreen() else enterPureFullscreen()
    }

    /**
     * 全屏 = 只剩画面。
     *
     * 铺满（裁掉黑边）+ 把所有覆盖层收起来：底部控制条、片名、连播倒计时。
     * 想再看到按钮就点一下画面 —— 这也是退出全屏的唯一入口。
     */
    private fun enterPureFullscreen() {
        pureMode = true
        applyAspect(1)
        titleText.visibility = View.GONE
        backButton?.visibility = View.GONE
        hideNextHint()
        if (::bottomChrome.isInitialized) bottomChrome.visibility = View.GONE
        toast("全屏：点一下画面可唤出按钮")
    }

    private fun exitPureFullscreen() {
        pureMode = false
        applyAspect(0)
        showProgress()
        toast("已退出全屏")
    }

    private fun applyAspect(mode: Int) {
        aspectMode = mode.coerceIn(0, 2)
        playerView.resizeMode = when (aspectMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        fullscreenButtons.forEach { it.text = fullscreenLabel() }
    }

    /** 手机播放画面左侧的锁；自己画图标，避免旧设备缺少 emoji 字形。 */
    private fun addLockButton() {
        val button = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val save = canvas.save()
                canvas.translate((width - dp(48)) / 2f, (height - dp(48)) / 2f)
                canvas.scale(dp(48) / 48f, dp(48) / 48f)
                paint.style = Paint.Style.FILL
                paint.color = if (locked) 0xDD0F766E.toInt() else 0x99000000.toInt()
                canvas.drawCircle(24f, 24f, 22f, paint)
                paint.color = android.graphics.Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.strokeCap = Paint.Cap.ROUND
                if (locked) {
                    canvas.drawArc(RectF(17f, 11f, 31f, 29f), 180f, 180f, false, paint)
                } else {
                    canvas.drawArc(RectF(18f, 11f, 32f, 29f), 180f, 145f, false, paint)
                }
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(14f, 22f, 34f, 37f, 2.5f, 2.5f, paint)
                paint.color = if (locked) 0xFF0F766E.toInt() else 0xFF333333.toInt()
                canvas.drawCircle(24f, 29f, 1.8f, paint)
                canvas.restoreToCount(save)
            }
        }.apply {
            contentDescription = "锁定屏幕"
            isClickable = true
            setOnClickListener {
                if (locked) unlockScreen() else lockScreen()
            }
        }
        rootBox.addView(
            button,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { leftMargin = dp(12) },
        )
        lockButton = button
    }

    /** 锁的是操作，不影响继续播放或自动连播。 */
    private fun lockScreen() {
        if (locked) return
        locked = true
        hideNextHint()
        handler.removeCallbacks(uiTick)
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(hideActions)
        setActionBarVisible(false)
        progressRow.visibility = View.GONE
        titleText.visibility = View.GONE
        if (::bottomChrome.isInitialized) bottomChrome.visibility = View.GONE

        val overlay = View(this).apply {
            isClickable = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }
        rootBox.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        lockOverlay = overlay
        lockButton?.apply {
            contentDescription = "解锁屏幕"
            bringToFront()
            invalidate()
        }
        updateChrome()
        toast(if (isTvDevice) "已锁屏：按返回键解锁" else "已锁屏：点左侧锁图标解锁")
    }

    private fun unlockScreen() {
        if (!locked) return
        locked = false
        lockOverlay?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        lockOverlay = null
        lockButton?.apply {
            contentDescription = "锁定屏幕"
            invalidate()
        }
        // 全屏纯净模式下解锁后控制条仍然不显示，等用户点画面
        if (::bottomChrome.isInitialized && !pureMode) bottomChrome.visibility = View.VISIBLE
        updateChrome()
        if (!pureMode) showProgress()
        toast("已解锁")
    }

    /** 锁屏时按键提示限流，免得按住方向键时刷一屏 toast */
    private fun lockHint() {
        val now = System.currentTimeMillis()
        if (now - lastLockHintAt < 3_000) return
        lastLockHintAt = now
        toast(if (isTvDevice) "已锁屏：按返回键解锁" else "已锁屏：点左侧锁图标解锁")
    }

    /* ------------------------------------------------------- 后台播放通知 */

    private fun notificationChannel(): String {
        val id = "cloudscreen_playback"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm?.getNotificationChannel(id) == null) {
                nm?.createNotificationChannel(
                    android.app.NotificationChannel(
                        id,
                        "后台播放",
                        android.app.NotificationManager.IMPORTANCE_LOW,   // 静音、不弹横幅
                    ).apply { description = "退出播放页后继续播放时的控制条" },
                )
            }
        }
        return id
    }

    private fun canNotify(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun showPlaybackNotification() {
        if (!canNotify()) return
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        val p = player
        val playing = p?.isPlaying == true
        fun cmd(action: String, flag: Int): android.app.PendingIntent =
            android.app.PendingIntent.getBroadcast(
                this, action.hashCode(),
                android.content.Intent(this, PlaybackReceiver::class.java).setAction(action),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or flag,
            )
        val immutable = if (android.os.Build.VERSION.SDK_INT >= 23) {
            android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        // 点通知回到播放页
        val open = android.app.PendingIntent.getActivity(
            this, 1,
            android.content.Intent(this, PlayerActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or immutable,
        )
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, notificationChannel())
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        val n = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(baseText.ifEmpty { "后台播放中" })
            .setContentText("点按回到播放页")
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(
                android.app.Notification.Action.Builder(
                    null,
                    if (playing) "暂停" else "继续",
                    cmd(PlaybackReceiver.ACTION_TOGGLE, immutable),
                ).build(),
            )
            .addAction(
                android.app.Notification.Action.Builder(
                    null, "停止",
                    cmd(PlaybackReceiver.ACTION_STOP, immutable),
                ).build(),
            )
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private fun hidePlaybackNotification() {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    /* --------------------------------------------------------- 音轨/字幕 */

    /** 一条可选轨道 */
    private class TrackOption(
        val label: String,
        val group: Tracks.Group?,
        val trackIndex: Int,
        val disableText: Boolean = false,
    )

    /**
     * 音轨 / 字幕切换。
     *
     * iTunes 系、Netflix 系的 m3u8 经常带多语言音轨和内嵌字幕，没有这个菜单就只能听默认那条，
     * 而且字幕默认可能是关的，用户会以为"这个源没字幕"。
     */
    /* ---------------------------------------------------------- 外挂字幕 */

    /**
     * 组装播放列表。
     *
     * 每一条都按「自己的播放地址」去找已保存的外挂字幕 —— 同一部剧每集的字幕文件不同，
     * 所以不能只给当前这一条挂，否则选集/自动连播换集之后字幕就没了。
     */
    private fun buildMediaItems(): List<MediaItem> = urls.map { mediaItemOf(it) }

    /** 单条媒体项：带上这一集自己的外挂字幕（如果没有就是纯地址） */
    private fun mediaItemOf(u: String): MediaItem {
        val b = MediaItem.Builder().setUri(u)
        val sub = prefs.subtitleOf(u)
        if (sub.isNotEmpty()) {
            val mime = prefs.subtitleMimeOf(u).ifEmpty { MimeTypes.APPLICATION_SUBRIP }
            b.setSubtitleConfigurations(listOf(subtitleConfigOf(sub, mime, prefs.subtitleNameOf(u))))
        }
        return b.build()
    }

    private fun subtitleConfigOf(
        uri: String,
        mime: String,
        label: String,
    ): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(uri))
            .setMimeType(mime)
            .setLanguage("zh")
            .setLabel(label.ifEmpty { "外挂字幕" })
            // 片源自带的字幕一般没标 default，加上这个位能保证挂上就默认显示
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .build()

    /** 换集之后要重新读一遍：新的一集可能有自己的字幕，也可能什么都没有 */
    private fun loadSubtitleForCurrent() {
        val u = currentUrl()
        subtitleUri = prefs.subtitleOf(u)
        subtitleMime = prefs.subtitleMimeOf(u)
        subtitleName = prefs.subtitleNameOf(u)
    }

    private fun subtitleLabel(): String = when {
        subtitleUri.isEmpty() -> "外挂字幕：未挂载"
        subtitleName.isEmpty() -> "外挂字幕：已挂载"
        else -> "外挂字幕：$subtitleName"
    }

    private fun showSubtitleMenu() {
        val has = subtitleUri.isNotEmpty()
        val options = if (has) {
            arrayOf(
                "换一个字幕文件…",
                "移除外挂字幕" + if (subtitleName.isEmpty()) "" else "（$subtitleName）",
            )
        } else {
            arrayOf("选一个字幕文件…")
        }
        AlertDialog.Builder(this)
            .setTitle("外挂字幕")
            .setMessage(
                if (has) {
                    "当前：${subtitleName.ifEmpty { subtitleUri }}\n" +
                        "已挂在该集上，换集不会跟着串过去。"
                } else {
                    "手里有现成的字幕文件？直接挂上来，不用等片源自带。\n" +
                        "支持 srt / ass / ssa / vtt。"
                },
            )
            .setItems(options) { _, which ->
                if (has && which == 1) {
                    prefs.clearSubtitle(currentUrl())
                    loadSubtitleForCurrent()
                    applySubtitleNow()
                    toast("已移除外挂字幕")
                } else {
                    pickSubtitleFile()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 走系统文件选择器：比自绘浏览器可靠，也不用额外申请存储权限（SAF 授权） */
    private fun pickSubtitleFile() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SUBTITLE)
        } catch (t: Throwable) {
            toast("这台设备没有文件选择器，挂不了字幕")
        }
    }

    @Deprecated("SAF 选择结果仍走旧回调，Activity 没有 ActivityResultCaller")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SUBTITLE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // 记住授权，否则关掉 App 再进来这份字幕就读不到了
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = displayNameOf(uri)
        val mime = sniffSubtitleMime(uri)
        if (mime == null) {
            toast("认不出格式，只支持 srt / ass / ssa / vtt")
            return
        }
        prefs.saveSubtitle(currentUrl(), uri.toString(), name, mime)
        loadSubtitleForCurrent()
        toast("已挂载：$name，正在生效…")
        applySubtitleNow()
    }

    private fun displayNameOf(uri: android.net.Uri): String {
        val queried = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
    }

    /**
     * 嗅探字幕格式。
     *
     * 不靠扩展名：SAF 返回的 content:// 地址经常没有后缀，得看文件头。
     * 先读前 512 字节判特征，认不出来再退回按文件名猜。
     */
    private fun sniffSubtitleMime(uri: android.net.Uri): String? {
        val head = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(512)
                val n = input.read(buf)
                if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
            }.orEmpty()
        }.getOrDefault("")
        val lower = head.lowercase()
        when {
            lower.contains("webvtt") -> return MimeTypes.TEXT_VTT
            // ASS/SSA 的节头，比 "Dialogue:" 更早出现也更稳
            lower.contains("[script info]") || lower.contains("dialogue:") ->
                return MimeTypes.TEXT_SSA
            // SRT 最硬的标志：时间轴箭头
            lower.contains("-->") -> return MimeTypes.APPLICATION_SUBRIP
        }
        return when {
            displayNameOf(uri).lowercase().endsWith(".vtt") -> MimeTypes.TEXT_VTT
            displayNameOf(uri).lowercase().let { it.endsWith(".ass") || it.endsWith(".ssa") } ->
                MimeTypes.TEXT_SSA
            displayNameOf(uri).lowercase().endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            else -> null
        }
    }

    /**
     * 让刚改动的字幕立刻生效。
     *
     * 字幕配置是烤在 MediaItem 里的，改完只能替换这一条再重新准备；
     * 位置和播放状态自己记着，替换完停回原处，用户不会觉得「跳回开头了」。
     */
    private fun applySubtitleNow() {
        val p = player ?: return
        val idx = currentIndex
        val url = urls.getOrNull(idx) ?: return
        val pos = p.currentPosition
        val wasPlaying = p.isPlaying
        val item = buildMediaItems().getOrNull(idx)
            ?: MediaItem.Builder().setUri(url).build()
        val replaced = runCatching { p.replaceMediaItem(idx, item) }.isSuccess
        if (!replaced) {
            // 少数老内核没有 replaceMediaItem：整体重设一遍，位置照样保住
            runCatching { p.setMediaItems(buildMediaItems(), idx, pos) }
        }
        p.seekTo(idx, pos)
        p.prepare()
        if (wasPlaying) p.play()
        hasError = false
        baseText = labelFor(idx)
        titleText.text = baseText
        titleText.setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
        showProgress()
    }

    private fun showTrackMenu() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter {
            it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT
        }
        val opts = ArrayList<TrackOption>()
        groups.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val kind = if (g.type == C.TRACK_TYPE_AUDIO) "音轨" else "字幕"
                val name = listOfNotNull(
                    f.label?.takeIf { it.isNotBlank() },
                    f.language?.takeIf { it.isNotBlank() && it != "und" },
                ).joinToString(" · ").ifEmpty { "轨道 ${i + 1}" }
                val mark = if (g.isTrackSelected(i)) "（当前）" else ""
                opts.add(TrackOption("$kind · $name$mark", g, i))
            }
        }
        if (opts.isEmpty()) {
            toast("这个片源只有一条音轨、也没有字幕")
            return
        }
        if (groups.any { it.type == C.TRACK_TYPE_TEXT }) {
            opts.add(TrackOption("字幕 · 关闭", null, -1, disableText = true))
        }
        AlertDialog.Builder(this)
            .setTitle("音轨 / 字幕")
            .setItems(opts.map { it.label }.toTypedArray()) { _, which ->
                val o = opts[which]
                val b = p.trackSelectionParameters.buildUpon()
                if (o.disableText) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val g = o.group ?: return@setItems
                    if (g.type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.trackIndex))
                }
                p.trackSelectionParameters = b.build()
                toast("已切换：" + o.label.substringBefore("（当前）"))
                showActions()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ------------------------------------------------------------ 选集 */

    /** "海贼王 · 第 1135/1180 集" → "海贼王" */
    private fun parseSeriesName(label: String): String =
        label.substringBefore(" · 第").substringBefore("·第").trim()

    /** "海贼王 · 第 1135/1180 集" → 1135；解析不出来返回 -1 */
    private fun parseEpisodeNumber(label: String): Int =
        Regex("第\\s*(\\d+)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1

    /** 回查整季列表时的重入锁，避免连点「选集」发起多次搜索 */
    private var resolvingEpisodes = false

    /**
     * 已经有回查在跑时，后来者的回调先挂这里。
     *
     * 场景：起播 4 秒的静默回查还没回来，用户就点了「选集」——
     * 以前 `resolveEpisodes` 直接 return，`onDone` 永远不调，「正在获取…」的面板就卡死了。
     */
    private val pendingEpisodeCallbacks = ArrayList<(Boolean) -> Unit>()

    /**
     * 静默回查已经试过几次。
     *
     * 上限 2：起播后一次、片尾兜底一次。不能无限试 —— 每试一次都是
     * 「1 次搜索 + 最多 8 次详情」，在慢网下很贵，而且失败基本就是没有。
     */
    private var autoResolveAttempts = 0

    /**
     * 起播后自动回查整季（用户没点「选集」也要查）。
     *
     * 为什么必须有这个：从观看历史 / 单集直链进来时 `urls` 只有一条，
     * 而 `checkOutroSkip()` 靠 `currentIndex + 1 in urls.indices` 判断有没有下一集 ——
     * 只有一条时就认定「这是最后一集」，片尾永远不跳。
     * 表现就是：海贼王看到第 1033 集，片尾曲照放；点一下「选集」识别出 1180 集之后，
     * 片尾才开始自动跳。
     *
     * 只走「精确命中」：拿到的分集列表里必须包含当前播放地址，否则什么都不做，
     * 也绝不弹「要不要换源」那种窗 —— 用户没点选集，不该被打断。
     */
    private fun silentResolveEpisodes() {
        if (urls.size > 1) return
        if (resolvingEpisodes) return
        if (autoResolveAttempts >= 2) return
        val name = seriesName.ifEmpty { parseSeriesName(singleLabel) }
        val epNo = parseEpisodeNumber(singleLabel)
        // 电影 / 单集：没有集号也没有 vodId，查了也白查，别浪费一次搜索
        if (vodId.isEmpty() && (name.isEmpty() || epNo <= 0)) return
        autoResolveAttempts++
        resolveEpisodes(onDone = {}, silent = true)
    }

    /**
     * 只有一个播放地址时，回查整季列表。
     *
     * **核心原则：必须精确命中当前播放地址，才允许替换整季列表。**
     * （上一版只看"同名"就取搜索结果的第一个源，结果海贼王 1180 集被换成了某个
     *   只有 218 集的源，还从第 1 集重播 —— 这是不能接受的。）
     *
     * 判定顺序：
     *   1. 已知 vodId + 采集站 → 先查这个（新写入的历史记录走这条，最快也最准）；
     *   2. 按剧名搜同名结果，逐个查详情，只看**分集地址里有没有当前正在播的那个 URL**；
     *   3. 一个都对不上 → 不动播放，提示"没找到同一片源的分集列表"。
     * 另有一个明确询问的兜底：找到同名的另一个源、且集数装得下当前集号时，
     * 弹窗问"要不要换到那个源"，用户点了才换，绝不静默切换。
     */
    /** 一个候选采集站：用它的 vodId + 接口地址去查整季 */
    private class EpisodeCandidate(val vodId: String, val conf: SourceConfig, val label: String)

    /** 查到的一份分集列表：current = 当前播放地址在里面的下标 */
    private class EpisodeList(val episodes: List<String>, val current: Int, val cand: EpisodeCandidate)

    /** 详情：能直连就直连，拿不到再回退服务端 —— 选集回查也走这条，比绕服务端快 */
    private fun detailOf(id: String, conf: SourceConfig, api: Api): Detail {
        if (DirectCms.shouldTryDirect(conf)) {
            val d = runCatching { DirectCms.detail(conf, id) }.getOrNull()
            if (d != null && d.episodes.isNotEmpty()) return d
        }
        return api.detail(id, conf)
    }

    private fun resolveEpisodes(onDone: (Boolean) -> Unit, silent: Boolean = false) {
        if (resolvingEpisodes) {
            // 已有回查在跑：把它跑完的结果一并通知，不要让调用方干等
            pendingEpisodeCallbacks.add(onDone)
            return
        }
        val name = seriesName.ifEmpty { parseSeriesName(singleLabel) }
        val epNo = parseEpisodeNumber(singleLabel)
        if (vodId.isEmpty() && name.isEmpty()) {
            if (!silent) toast("这个片源只有一集，没有剧集列表")
            onDone(false)
            return
        }
        val playing = currentUrl()

        // ---- 本地缓存：整季列表直接出，不再等网络 ----
        // 上千集的长剧，第二次进来点「选集」应该秒开；后台再默默核对有没有更新。
        val server = prefs.serverUrl.ifEmpty { BuildConfig.DEFAULT_URL }
        val cache = EpisodeCache(this)
        if (vodId.isNotEmpty() && sourceUrl.isNotEmpty() && player != null) {
            val cached = cache.get(server, sourceUrl, vodId)
            val idx = cached?.urls?.indexOf(playing) ?: -1
            if (cached != null && idx >= 0) {
                val conf = prefs.sources().firstOrNull { it.url == sourceUrl }
                    ?: SourceConfig(sourceName.ifEmpty { "已知源" }, sourceUrl)
                adoptSeriesList(
                    cached.urls, idx, vodId, conf,
                    keepPosition = true, inPlace = true, quiet = silent,
                )
                onDone(true)
                refreshEpisodesInBackground(server, conf, vodId, playing, cached)
                return
            }
        }

        resolvingEpisodes = true
        Thread {
            val api = Api(prefs.serverUrl.ifEmpty { BuildConfig.DEFAULT_URL })
                .apply { restoreCookie(prefs.cookie) }
            val sources = prefs.sources()
            val enabled = sources.filter { it.enabled }
            fun confOf(url: String, fallbackName: String): SourceConfig =
                sources.firstOrNull { it.url == url } ?: SourceConfig(fallbackName, url)

            // 第一步：**先单独查已知源**。
            //
            // 从历史记录 / 详情页进来时都带着 vodId + sourceUrl，直接查这一个源的详情就能
            // 精确命中，一次请求、一两秒。以前是先 `api.search()` 把所有采集站打一遍再逐个查
            // 详情（最多 8 个），光搜索就要好几秒 —— 这就是「点选集要等半天」的主因。
            var hit: EpisodeList? = null        // 地址精确命中：可以原地换成整季
            var near: EpisodeList? = null       // 只是同名且集数够：换源要问用户
            if (vodId.isNotEmpty() && sourceUrl.isNotEmpty()) {
                val conf = confOf(sourceUrl, sourceName.ifEmpty { "已知源" })
                val eps = runCatching { detailOf(vodId, conf, api).episodes }.getOrDefault(emptyList())
                if (eps.size > 1) cache.put(server, conf.url, vodId, eps)
                val i = eps.indexOfFirst { it == playing }
                if (i >= 0) {
                    runOnUiThread {
                        resolvingEpisodes = false
                        adoptSeriesList(eps, i, vodId, conf, keepPosition = true, inPlace = silent)
                        onDone(true)
                        if (pendingEpisodeCallbacks.isNotEmpty()) {
                            val waiting = pendingEpisodeCallbacks.toList()
                            pendingEpisodeCallbacks.clear()
                            waiting.forEach { it(true) }
                        }
                    }
                    return@Thread
                }
                if (eps.isNotEmpty() && epNo > 0 && eps.size >= epNo) {
                    near = EpisodeList(eps, epNo - 1, EpisodeCandidate(vodId, conf, "已知源"))
                }
            }

            // 第二步：已知源没命中（没带 vodId，或者地址变了）才去搜同行。
            // 候选：已知源优先，其次是搜索里片名一致的（去重，最多试 5 个，别把采集站打爆）
            val candidates = ArrayList<EpisodeCandidate>()
            if (vodId.isNotEmpty() && sourceUrl.isNotEmpty()) {
                candidates.add(EpisodeCandidate(vodId, confOf(sourceUrl, sourceName.ifEmpty { "已知源" }), "已知源"))
            }
            if (name.isNotEmpty() && enabled.isNotEmpty()) {
                val hits = runCatching { api.search(name, enabled) }.getOrNull()?.items.orEmpty()
                hits.filter { it.name.trim() == name }
                    .distinctBy { it.sourceUrl }
                    .take(5)
                    .forEach { h ->
                        if (candidates.none { it.conf.url == h.sourceUrl }) {
                            candidates.add(
                                EpisodeCandidate(h.vodId, confOf(h.sourceUrl, h.sourceName), h.sourceName),
                            )
                        }
                    }
            }

            for (c in candidates) {
                val eps = runCatching { detailOf(c.vodId, c.conf, api).episodes }.getOrDefault(emptyList())
                if (eps.isEmpty()) continue
                if (eps.size > 1) cache.put(server, c.conf.url, c.vodId, eps)
                val i = eps.indexOfFirst { it == playing }
                if (i >= 0) {
                    hit = EpisodeList(eps, i, c)
                    break                       // 精确命中，立刻停手
                }
                // 没命中：只有"集数装得下当前集号"的源，才可能是同一部剧
                if (near == null && epNo > 0 && eps.size >= epNo) {
                    near = EpisodeList(eps, epNo - 1, c)
                }
            }

            runOnUiThread {
                resolvingEpisodes = false
                val exact = hit
                val loose = near
                var ok = false
                when {
                    exact != null -> {
                        adoptSeriesList(
                            exact.episodes, exact.current, exact.cand.vodId, exact.cand.conf,
                            keepPosition = true,
                            inPlace = silent,
                        )
                        ok = true
                    }
                    loose != null -> {
                        // 地址一条都对不上，说明这不是同一个源。换源会改变播放内容，
                        // 必须用户自己点，不能替他决定（否则就会出现"1180 集变 218 集"）。
                        // 静默回查时不问 —— 用户没点选集，跳个「要不要换源」的窗太突兀，
                        // 下次他点「选集」时这条兜底还在。
                        if (!silent) {
                            AlertDialog.Builder(this)
                                .setTitle("当前源查不到分集列表")
                                .setMessage(
                                    "这个播放地址所属的源没有整季列表。\n\n" +
                                        "同名剧在「${loose.cand.label}」有 ${loose.episodes.size} 集，" +
                                        "可以从第 ${loose.current + 1} 集接着看。\n\n" +
                                        "切过去会重新加载这一集，当前播放位置不保留。",
                                )
                                .setPositiveButton("换到该源") { _, _ ->
                                    adoptSeriesList(
                                        loose.episodes, loose.current, loose.cand.vodId, loose.cand.conf,
                                        keepPosition = false,
                                    )
                                    showEpisodePicker()
                                }
                                .setNegativeButton("继续看当前这集", null)
                                .show()
                        }
                        onDone(false)
                    }
                    else -> {
                        if (!silent) {
                            toast(
                                if (epNo > 0) "没找到含第 $epNo 集的同一片源，只能播当前这一集"
                                else "没找到这部剧的分集列表，只能播当前这一集",
                            )
                        }
                        onDone(false)
                    }
                }
                // 回查期间点了选集、排队等着的那几个调用方，一起通知（否则面板会一直转）
                if (pendingEpisodeCallbacks.isNotEmpty()) {
                    val waiting = pendingEpisodeCallbacks.toList()
                    pendingEpisodeCallbacks.clear()
                    waiting.forEach { it(ok) }
                }
            }
        }.start()
    }

    /**
     * 缓存已经用上了，后台再悄悄核对一次：
     *   · 当前这集还在 → 更新缓存；如果只是尾部多了几集，直接把新的追加进播放列表
     *     （不 setMediaItems、不重载，画面不闪）
     *   · 当前这集没了 / 片源换了 → 什么都不动，绝不把正在播的内容切走
     */
    private fun refreshEpisodesInBackground(
        server: String,
        conf: SourceConfig,
        id: String,
        playing: String,
        cached: EpisodeCache.Entry,
    ) {
        Thread {
            val api = Api(server).apply { restoreCookie(prefs.cookie) }
            val eps = runCatching { detailOf(id, conf, api).episodes }.getOrDefault(emptyList())
            if (eps.size <= 1) return@Thread
            if (!eps.contains(playing)) return@Thread
            EpisodeCache(this).put(server, conf.url, id, eps)
            if (eps == cached.urls) return@Thread
            runOnUiThread {
                val p = player ?: return@runOnUiThread
                if (isFinishing || isDestroyed) return@runOnUiThread
                val cur = urls
                // 老列表必须整体是新列表的前缀：否则集号可能错位，宁可不追加
                if (eps.size > cur.size && eps.take(cur.size) == cur) {
                    val tail = eps.subList(cur.size, eps.size).map { mediaItemOf(it) }
                    urls = eps
                    if (tail.isNotEmpty()) p.addMediaItems(cur.size, tail)
                }
            }
        }.start()
    }

    /**
     * 把当前的单集播放替换成整季列表。
     * @param keepPosition 只有"同一个播放地址"才能保留播放位置，否则从该集开头播
     * @param inPlace 静默回查专用：不重新 setMediaItems，只把「前面 / 后面」两段插进播放列表，
     *   正在播的那条媒体项原地不动 —— 画面不闪、不重新缓冲，用户察觉不到列表已经变成整季
     */
    private fun adoptSeriesList(
        list: List<String>,
        index: Int,
        newVodId: String,
        conf: SourceConfig,
        keepPosition: Boolean,
        inPlace: Boolean = false,
        quiet: Boolean = false,
    ) {
        val p = player ?: return
        val pos = if (keepPosition) p.currentPosition.coerceAtLeast(0L) else 0L
        val idx = index.coerceIn(0, list.size - 1)
        // 只有「当前正在播的那条就是新列表里的第 idx 条」时才允许拼接，
        // 否则换的是别的源，播放内容都不一样了，必须老老实实重建
        val splice = inPlace && keepPosition && urls.size == 1 &&
            list.getOrNull(idx) == urls.firstOrNull() && p.mediaItemCount == 1
        urls = list
        currentIndex = idx
        if (newVodId.isNotEmpty()) vodId = newVodId
        if (conf.url.isNotEmpty()) {
            sourceUrl = conf.url
            sourceName = conf.name
        }
        if (seriesName.isEmpty()) seriesName = parseSeriesName(singleLabel)
        if (seriesTitle == singleLabel) seriesTitle = seriesName.ifEmpty { singleLabel }
        // 先把剧集信息写进历史，下一次续播就不用再回查
        persistProgress()

        if (splice) {
            // 先补后面再补前面：插到当前项之前会让 currentMediaItemIndex 自动前移，
            // 顺序反了的话两次插入的偏移会叠在一起，容易算错
            val after = list.subList(idx + 1, list.size).map { mediaItemOf(it) }
            val before = list.subList(0, idx).map { mediaItemOf(it) }
            if (after.isNotEmpty()) p.addMediaItems(p.mediaItemCount, after)
            if (before.isNotEmpty()) p.addMediaItems(0, before)
            // ExoPlayer 会自动调整当前下标，这里只做一次防御性核对
            if (p.currentMediaItemIndex != idx) p.seekTo(idx, pos)
            hasError = false
            resetAutoNextState()
            baseText = labelFor(idx)
            titleText.text = baseText
            titleText.setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            if (!quiet) toast("已识别整季：共 ${list.size} 集，播到片尾会自动接下一集")
            return
        }

        p.setMediaItems(buildMediaItems(), idx, pos)
        p.prepare()
        p.playWhenReady = true
        hasError = false
        resetAutoNextState()
        baseText = labelFor(idx)
        titleText.text = baseText
        titleText.setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
        if (!quiet) toast("已恢复整季：共 ${list.size} 集（${conf.name}）")
    }

    /**
     * 「正在获取整季」的过渡面板。
     *
     * 点「选集」到拿到列表之间有个网络往返（快则半秒，慢则几秒）。这段空白期如果
     * 屏幕上什么都不出现，用户会以为没点上、反复点。所以先把面板开出来，写清楚在查
     * 哪部剧、当前第几集；查到之后 [showEpisodePicker] 会把它换成完整网格。
     */
    private fun showEpisodesLoadingPanel() {
        episodeDialog?.dismiss()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(26), dp(30), dp(22))
        }
        box.addView(TextView(this).apply {
            text = "选集"
            setTextColor(0xFFE5E7EB.toInt())
            setTextSize(17f)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = "正在获取《${skipKey().ifEmpty { "这部剧" }}》的剧集列表…"
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
        })
        val epNow = parseEpisodeNumber(singleLabel).takeIf { it > 0 } ?: (currentIndex + 1)
        box.addView(TextView(this).apply {
            text = "当前第 $epNow 集 · 拿到列表就能直接跳集"
            setTextColor(0xFF64748B.toInt())
            setTextSize(13f)
            gravity = Gravity.CENTER
        })
        box.addView(
            pagerButton("取消") { episodeDialog?.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) },
        )

        val dialog = Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(box)
            setCanceledOnTouchOutside(true)
        }
        dialog.window?.apply {
            setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(0xF210141C.toInt())
                    cornerRadius = dp(14).toFloat()
                },
            )
            setLayout(
                (resources.displayMetrics.widthPixels * 0.62f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        episodeDialog = dialog
        dialog.show()

        resolveEpisodes(onDone = { ok ->
            if (ok) {
                showEpisodePicker()
            } else {
                dialog.dismiss()
                if (episodeDialog === dialog) episodeDialog = null
            }
        })
    }

    /**
     * 选集面板。
     *
     * 用自绘的「多行 + 可聚焦 TextView」而不是 GridView：GridView 的 selector 画在子项
     * 下面，子项一旦有背景就完全看不见焦点框，电视上等于没有焦点提示。自绘每一格可以
     * 同时表达两件事 —— 「当前正在播的是哪一集」（浅绿底）和「遥控器停在哪」（白框）。
     */
    private fun showEpisodePicker() {
        if (urls.isEmpty()) return
        if (urls.size <= 1) {
            // 从观看历史 / 单集直链进来时只有一个地址，得先回查整季。
            // 以前是「toast 一句正在获取 → 干等网络 → 才弹面板」，慢网下十几秒没反应，
            // 看着就像点了没生效。现在先把面板开出来、明确写着在查什么，查到了就地换成完整列表。
            showEpisodesLoadingPanel()
            return
        }
        episodeDialog?.dismiss()

        val columns = when {
            resources.configuration.screenWidthDp >= 960 -> 12
            resources.configuration.screenWidthDp >= 720 -> 9
            resources.configuration.screenWidthDp >= 480 -> 6
            else -> 5
        }
        val cellHeight = dp(46)
        val gap = dp(8)
        val rowHeight = cellHeight + gap

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(18), dp(26), dp(18))
        }
        content.addView(TextView(this).apply {
            text = "选集 · 共 ${urls.size} 集（当前第 ${currentIndex + 1} 集）"
            setTextColor(0xFFE5E7EB.toInt())
            setTextSize(17f)
            setPadding(0, 0, 0, dp(10))
        })

        epColumns = columns
        epRowHeight = rowHeight
        epPageCount = (urls.size + epPageSize - 1) / epPageSize
        // 打开面板时自动翻到当前集所在的那一页，不用手动跳到第 800 集
        epPage = (currentIndex / epPageSize).coerceIn(0, epPageCount - 1)
        epCurrentCell = null
        epContent = content

        val pagerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        epPrevBtn = pagerButton("‹ 上一页") {
            if (epPage > 0) {
                epPage--
                renderEpisodePage()
                focusEpisodePage()
            }
        }
        epNextBtn = pagerButton("下一页 ›") {
            if (epPage < epPageCount - 1) {
                epPage++
                renderEpisodePage()
                focusEpisodePage()
            }
        }
        epPageLabel = TextView(this).apply {
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(13f)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        pagerRow.addView(
            epPrevBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(8) },
        )
        pagerRow.addView(epPageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pagerRow.addView(
            pagerButton("跳到…") { jumpToEpisode() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(8) },
        )
        pagerRow.addView(
            epNextBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        // 只有一页时不必占用视线
        pagerRow.visibility = if (epPageCount > 1) View.VISIBLE else View.GONE
        content.addView(pagerRow)

        val gridBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        epGridBox = gridBox
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(gridBox)
        }
        epScroll = scroll
        content.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        renderEpisodePage()

        val dialog = Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(content)
            setCanceledOnTouchOutside(true)
        }
        dialog.window?.apply {
            setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(0xF210141C.toInt())
                    cornerRadius = dp(14).toFloat()
                },
            )
            setLayout(
                (resources.displayMetrics.widthPixels * 0.86f).toInt(),
                (resources.displayMetrics.heightPixels * 0.82f).toInt(),
            )
            setDimAmount(0.65f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.CENTER)
        }
        dialog.setOnDismissListener {
            episodeDialog = null
            updateChrome()
        }
        episodeDialog = dialog
        dialog.show()
        dialog.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN &&
                (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_M)
            ) {
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        // 焦点落在当前集上，并把它滚进可视区（长篇剧集动辄上百集）
        focusEpisodePage()
    }

    /** 翻页条上的小按钮：同样用描边表示焦点，天然比自绘 selector 可靠 */
    private fun pagerButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(14f)
            setTextColor(0xFFE5E7EB.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF20212B.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).background = GradientDrawable().apply {
                    setColor(0xFF20212B.toInt())
                    cornerRadius = dp(8).toFloat()
                    if (hasFocus) setStroke(dp(3), 0xFFFFFFFF.toInt())
                }
            }
        }

    /** 只重建当前页的网格 —— 这是分页的全部意义：不碰另外 1000 集 */
    private fun renderEpisodePage() {
        val box = epGridBox ?: return
        box.removeAllViews()
        epCurrentCell = null
        val start = epPage * epPageSize
        val end = minOf(start + epPageSize, urls.size)
        val marked = prefs.resumeThresholdSeconds * 1000L
        (start until end).chunked(epColumns).forEach { rowIndices ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowIndices.forEach { idx ->
                val isCurrent = idx == currentIndex
                // 「看过」= 这一集有超过续播阈值的进度记录，在千集长剧里非常实用
                val watched = !isCurrent && prefs.resumeEnabled &&
                    prefs.progressOf(urls[idx]) > marked
                val cell = TextView(this).apply {
                    text = if (watched) "${idx + 1}·" else "${idx + 1}"
                    tag = idx
                    gravity = Gravity.CENTER
                    setTextSize(15f)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        rightMargin = dp(8)
                    }
                    setOnClickListener {
                        switchEpisode(idx)
                        episodeDialog?.dismiss()
                    }
                    styleEpisodeCell(this, isCurrent, watched, false)
                    setOnFocusChangeListener { v, hasFocus ->
                        styleEpisodeCell(v as TextView, isCurrent, watched, hasFocus)
                    }
                }
                if (isCurrent) epCurrentCell = cell
                row.addView(cell)
            }
            // 补齐空位，保证每格等宽
            repeat(epColumns - rowIndices.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(46), 1f))
            }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, epRowHeight))
        }
        epPageLabel?.text = "第 ${epPage + 1}/${epPageCount} 页 · 第 ${start + 1}–${end} 集"
        val multi = epPageCount > 1
        epPrevBtn?.apply {
            alpha = if (multi && epPage > 0) 1f else 0.35f
            isEnabled = multi
        }
        epNextBtn?.apply {
            alpha = if (multi && epPage < epPageCount - 1) 1f else 0.35f
            isEnabled = multi
        }
    }

    /** 把焦点放到「当前集」；若当前集不在本页，则放到本页第一格 */
    private fun focusEpisodePage() {
        val box = epGridBox ?: return
        epContent?.post {
            val target = epCurrentCell
                ?: ((box.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? TextView)
            target?.requestFocus()
            val idx = (target?.tag as? Int) ?: (epPage * epPageSize)
            val rowInPage = ((idx - epPage * epPageSize) / epColumns).coerceAtLeast(0)
            epScroll?.scrollTo(0, maxOf(0, rowInPage * epRowHeight - epRowHeight * 2))
        }
    }

    /** 千集长剧：直接输入集数跳过去，不用一页页翻 */
    private fun jumpToEpisode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – ${urls.size}"
            setText("${currentIndex + 1}")
            setSelectAllOnFocus(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(TextView(this@PlayerActivity).apply {
                text = "跳到第几集？（共 ${urls.size} 集）"
                setTextColor(0xFFE5E7EB.toInt())
                setTextSize(15f)
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("跳到指定集")
            .setView(box)
            .setPositiveButton("跳过去") { _, _ ->
                val n = input.text.toString().trim().toIntOrNull()
                if (n == null || n < 1 || n > urls.size) {
                    toast("请输入 1 – ${urls.size} 之间的集数")
                    return@setPositiveButton
                }
                epPage = (n - 1) / epPageSize
                renderEpisodePage()
                val offset = n - 1 - epPage * epPageSize
                val targetRow = (offset / epColumns)
                val targetCol = offset % epColumns
                epContent?.post {
                    val row = epGridBox?.getChildAt(targetRow) as? ViewGroup
                    (row?.getChildAt(targetCol) as? TextView)?.requestFocus()
                    epScroll?.scrollTo(0, maxOf(0, targetRow * epRowHeight - epRowHeight * 2))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 一格选集按钮的三态样式：当前集浅绿底、「看过」暗绿底、焦点白框 + 轻微放大 */
    private fun styleEpisodeCell(cell: TextView, isCurrent: Boolean, watched: Boolean, focused: Boolean) {
        cell.background = GradientDrawable().apply {
            setColor(
                when {
                    isCurrent -> 0xFFD9F5A9.toInt()
                    watched -> 0xFF1F2E24.toInt()
                    else -> 0xFF20212B.toInt()
                },
            )
            cornerRadius = dp(8).toFloat()
            if (focused) setStroke(dp(4), 0xFFFFFFFF.toInt())
            if (watched) setStroke(dp(2), 0xFF2F6B45.toInt())
        }
        cell.setTextColor(
            when {
                isCurrent -> 0xFF17321B.toInt()
                watched -> 0xFF8FD3A8.toInt()
                else -> 0xFFE5E7EB.toInt()
            },
        )
        val s = if (focused) 1.06f else 1f
        cell.scaleX = s
        cell.scaleY = s
    }

    /** 切到指定集：先存当前进度，再按该集自己的续播点起播。 */
    private fun switchEpisode(index: Int) {
        val p = player ?: return
        if (index !in urls.indices) return
        persistProgress()
        val target = urls[index]
        val resume = resumePositionOf(target, index)
        val threshold = prefs.resumeThresholdSeconds * 1000L
        val startAt = if (resume > threshold) resume else 0L

        currentIndex = index
        hasError = false
        resetAutoNextState()
        baseText = labelFor(index)
        titleText.text = baseText
        titleText.setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
        bufferingText.visibility = View.VISIBLE
        p.seekTo(index, startAt)
        p.play()
        updateChrome()
        showProgress()
        toast(
            if (startAt > 0) "第 ${index + 1} 集 · 从上次位置继续"
            else "第 ${index + 1} 集",
        )
    }


    /** 解码方式：0 自动 / 1 强制软解 / 2 强制硬解 / 3 硬解优先（默认） */
    private fun buildRenderers(): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        // 全局设置优先；此外「这部剧以前硬解失败过」也走软解（只影响这一部）。
        // 0 和 3 都属于「会自动挑硬解」的档位，所以要一并让本剧软解标记生效，
        // 否则默认档改成 3 之后，历史标记会被忽略、每次进这一集都重新撞一次硬解失败。
        var mode = prefs.decodeMode
        if ((mode == 0 || mode == 3) && prefs.softDecodeFor(skipKey())) mode = 1
        if (mode != 0) {
            factory.setMediaCodecSelector { mime, secure, tunneling ->
                val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                when (mode) {
                    1 -> all.filterNot { it.hardwareAccelerated }
                    2 -> all.filter { it.hardwareAccelerated }.ifEmpty { all }
                    // 3 硬解优先：只是排序，软解仍留在候选里。
                    // 硬解初始化失败时 setEnableDecoderFallback 会顺着列表换到下一个解码器，
                    // 也就是软解，全程在播放器内部完成 —— 不报错、不闪屏、不重建 Activity。
                    else -> all.sortedByDescending { it.hardwareAccelerated }
                }
            }
        }
        return factory
    }

    /* ------------------------------------------------------------ 进度与退出 */

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0)
        val base = if (pendingSeek >= 0) pendingSeek else p.currentPosition
        val raw = base + deltaMs
        val target = if (dur > 0) raw.coerceIn(0, dur) else raw.coerceAtLeast(0)
        pendingSeek = target
        p.seekTo(target)
        handler.removeCallbacks(clearPendingSeek)
        handler.postDelayed(clearPendingSeek, 1_500)
        showProgress()
    }

    private fun currentUrl(): String = urls.getOrNull(currentIndex).orEmpty()

    private fun labelFor(index: Int): String {
        // 只有一个地址：显示传进来的原始标题（历史记录里那句"海贼王 · 第 1135/1180 集"）
        if (urls.size <= 1) return singleLabel.ifEmpty { seriesTitle }.ifEmpty { "正在播放" }
        return "$seriesTitle · 第 ${index + 1}/${urls.size} 集"
    }

    private fun persistProgress() {
        val p = player ?: return
        lastPersistAt = SystemClock.uptimeMillis()
        if (p.isPlaying || p.playbackState == Player.STATE_READY) {
            val url = currentUrl()
            if (url.isEmpty()) return
            prefs.saveProgress(url, p.currentPosition)
            // 再按「剧名 + 集号」记一份：换源之后地址变了，靠这份接上进度
            prefs.saveEpisodeProgress(skipKey(), currentIndex, p.currentPosition)
            if (p.currentPosition > 5_000L) {
                prefs.upsertHistory(
                    HistoryEntry(
                        title = labelFor(currentIndex),
                        playUrl = url,
                        positionMs = p.currentPosition,
                        durationMs = p.duration.coerceAtLeast(0L),
                        updatedAt = System.currentTimeMillis(),
                        cover = coverUrl,
                        seriesTitle = seriesTitle,
                        vodId = vodId,
                        sourceUrl = sourceUrl,
                        sourceName = sourceName,
                        episodeIndex = currentIndex,
                    ),
                )
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 手机上只有左侧锁图标能解锁；电视没有触屏，保留返回键解锁。
        if (locked) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (isTvDevice) unlockScreen() else lockHint()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                -> return super.onKeyDown(keyCode, event)
                else -> {
                    lockHint()
                    return true
                }
            }
        }

        // 全屏纯净模式：按键先把控制条/操作栏唤出来，本次按键不执行动作（避免全屏时误操作）。
        // 返回键直接退出全屏，而不是退出播放页 —— 全屏状态下按返回多半是想"退出来"。
        if (pureMode && !isActionBarVisible()) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    exitPureFullscreen()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                -> return super.onKeyDown(keyCode, event)
                else -> {
                    showProgress()
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                        keyCode == KeyEvent.KEYCODE_MENU ||
                        keyCode == KeyEvent.KEYCODE_M
                    ) {
                        showActions()
                    }
                    return true
                }
            }
        }

        val p = player ?: return super.onKeyDown(keyCode, event)

        // 片尾倒计时浮着时，返回键只取消连播，不退出播放页（否则想「先停一下」会直接退出去）
        if (keyCode == KeyEvent.KEYCODE_BACK && nextHint?.visibility == View.VISIBLE) {
            cancelAutoNext()
            return true
        }

        // 后台播放开启时，返回键 = 退到后台继续放（不销毁 Activity，音频不中断）。
        // 操作栏里的「退出」才是真正的停止 + 释放。
        if (keyCode == KeyEvent.KEYCODE_BACK && prefs.backgroundPlay && !isActionBarVisible()) {
            persistProgress()
            toast("后台播放中，操作栏「退出」可停止")
            moveTaskToBack(true)
            return true
        }

        // 操作栏打开时：按键全部交给菜单（按钮可聚焦、可点击）
        if (isActionBarVisible()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                handler.removeCallbacks(hideActions)
                handler.postDelayed(hideActions, HIDE_ACTIONS_DELAY_MS)
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_M,
                    -> {
                        hideActions.run()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // 已经在操作栏上，再按下键就收起
                        hideActions.run()
                        return true
                    }
                }
            }
            return false
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE,
            -> {
                if (p.isPlaying) p.pause() else p.play()
                showProgress()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                showSpeedMenu()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showActions()
                true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M -> {
                showActions()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(if (event.repeatCount > 0) 15_000L else 10_000L)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(if (event.repeatCount > 0) -15_000L else -10_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(30_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-30_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    showProgress()
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (p.hasPreviousMediaItem()) {
                    p.seekToPreviousMediaItem()
                    showProgress()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onBackPressed() {
        if (locked) {
            if (isTvDevice) unlockScreen() else lockHint()
            return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        live = this
        hidePlaybackNotification()
        handler.post(nextTick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(nextTick)
        hideNextHint()
        persistProgress()
        // 后台播放开着就不暂停：用户按 Home 或返回后声音继续；
        // 关着则立刻暂停，配合退出时的 release 把播放器内存还给系统。
        if (!prefs.backgroundPlay) {
            player?.pause()
        } else if (player?.isPlaying == true) {
            // 退到后台还在放：给一条常驻通知，能暂停/停止，也免得用户以为声音关不掉
            showPlaybackNotification()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            backGestureCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backGestureCallback = null
        }
        hidePlaybackNotification()
        if (live === this) live = null
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(uiTick)
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(speedTick)
        handler.removeCallbacks(hideActions)
        handler.removeCallbacks(clearPendingSeek)
        handler.removeCallbacks(nextTick)
        episodeDialog?.dismiss()
        episodeDialog = null
        player?.release()
        player = null
        PlaybackCache.playerClosed()
        super.onDestroy()
    }
}
