package com.lshai.cloudscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.ClipData
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 首页 RecyclerView 的两种 item：整块头部、海报格子 */
private const val TYPE_HEADER = 0
private const val TYPE_CELL = 1

/** 追剧自动检查更新的最小间隔（6 小时） */
private const val FAVORITE_CHECK_INTERVAL_MS = 6 * 3_600_000L

/** 搜索结果复用的时间窗：3 分钟内的同一关键词不再重复请求 */
private const val SEARCH_CACHE_MS = 3 * 60_000L

/** 详情页复用的时间窗：5 分钟内同一部剧（同源）不再重复请求 */
private const val DETAIL_CACHE_MS = 5 * 60_000L

/**
 * 原生客户端主界面。刻意不用 XML 布局、不引 AppCompat/Leanback：
 * 只用框架控件，D-pad 焦点是系统原生支持的，老设备上行为最可预测。
 *
 * 屏幕：设置 → 片源 → 首页（搜索 + 结果）→ 详情（选集）→ 播放器（独立 Activity）
 */
class MainActivity : Activity() {

    private val isTvDevice: Boolean by lazy {
        resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }
    private val compact: Boolean get() = !isTvDevice && resources.configuration.screenWidthDp < 600
    private fun pagePad(tvDp: Int): Int = dp(if (compact) 16 else tvDp)

    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var imageLoader: ImageLoader
    private lateinit var root: FrameLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateBadgeBar: LinearLayout? = null
    private var updateBadge: TextView? = null
    private var manualUpdateCheckInProgress = false
    @Volatile private var updateDownloadCancelled = false
    @Volatile private var updateDownloadConnection: HttpURLConnection? = null
    @Volatile private var updateDownloadThread: Thread? = null
    private var updateProgressDialog: AlertDialog? = null
    private var updateProgressBar: ProgressBar? = null
    private var updateProgressText: TextView? = null
    private var refreshHomeAfterPlayback = false

    /**
     * 按用途分槽的请求登记表。
     *
     * 以前是一个全局 `job`：任何新请求都会 cancel 掉上一个，于是「首页悄悄补推荐」
     * 会把「正在进行的搜索」顶掉、用户什么都没点却显示失败。现在按 key 隔离 ——
     * 只有**同类**的新请求才取消上一次（重复点搜索只认最后一次），跨类互不干扰。
     */
    private val jobs = mutableMapOf<String, Job>()

    /** 某个槽位对应的任务；没有则返回 null */
    private fun jobOf(key: String): Job? = jobs[key]

    private fun cancelJob(key: String) {
        jobs[key]?.cancel()
        jobs.remove(key)
    }

    private var lastResults: List<SearchItem> = emptyList()
    private var recommendItems: List<DoubanItem> = emptyList()
    private var liveChannels: List<Channel> = emptyList()
    private var liveChannelSource: String = ""
    private var mediaType = "tv"
    private var recommendPage = 0
    private var recommendRequested = false
    private val coverLookupAttempted = mutableSetOf<String>()
    private var homeMaintenanceJob: Job? = null
    private var favoriteCheckJob: Job? = null
    private var hotSearchJob: Job? = null
    private val homeHistoryCoverViews = mutableMapOf<String, Triple<ImageView, Int, Int>>()
    private var recommendTag = "最新"
    private var isLoadingMore = false
    private var noMoreRecommend = false
    private var suppressAutoLoad = false
    private var pendingGridSelection = -1
    private var lastVisibleIndex = 0
    private var liveGroupFilter = ""
    private var sourceFilter = ""
    /** 搜索结果「只看可用源」：隐藏上次检测明确失败的采集站 */
    private var onlyHealthySource = false
    private var homeHistoryFirst: View? = null
    private var homeGrid: GridView? = null
    /** 首页整页的滚动容器与滚动位置（头部 + 网格一起滚） */
    private var homeScroll: ScrollView? = null
    private var homeScrollY = 0
    /** 手机端首页的 RecyclerView（头部 item + 海报网格） */
    private var homeRecycler: RecyclerView? = null
    /** 手机端记住「第一个可见项」，从播放页返回时按它还原 */
    private var homeRvPosition = -1
    /** 追剧自动检查的节流：本次进程内最近一次检查时间 */
    private var lastFavoriteCheckAt = 0L
    /** 搜索结果短时缓存：key = 关键词 + 源集合，value = (写入时间, 结果) */
    private val searchCache = HashMap<String, Pair<Long, List<SearchItem>>>()
    private var lastSearchKeyword = ""
    private var lastSearchWasPartial = false
    /** 详情页短时缓存：key = vodId + 源地址，value = (写入时间, 详情) */
    private val detailCache = HashMap<String, Pair<Long, Detail>>()
    /** 片源管理列表：测速完只刷行内容，不重建整页 */
    private var sourcesListView: ListView? = null
    /** 顶部加载胶囊：非模态，所以用 BACK 键取消当前槽位的请求 */
    private var busyBar: LinearLayout? = null
    private var busyLabel: TextView? = null
    private var busyKey: String? = null
    private var busyCancellable = true
    /** 启动阶段的非模态提示：null = 已就绪；否则在首页顶部显示一行小字（不打断操作）。 */
    private var bootNotice: String? = null
    private var bootFailed = false
    /** 当前界面名，用于实现遥控器 BACK 的层级回退（否则任何界面按返回都会直接退出应用）。 */
    private var currentScreen = "setup"
    /** 设置页是否展开「服务器连接」区块。已连接时默认收起（低频操作不占首屏） */
    private var setupShowFields = false
    private val REQ_PICK_JSON = 1001
    private val REQ_STORAGE = 1002

    /** 顶部「返回主页」按钮的标记：自动聚焦时跳过它，避免一进页面焦点就落在返回上 */
    private val SKIP_FIRST_FOCUS = "skip-first-focus"

    /* ---------------------------------------------------------- 焦点视觉 */

    // 电视上最致命的问题就是「看不出焦点在哪」。统一用高对比度方案：
    // 聚焦 = 亮蓝填充 + 深色文字 + 4dp 亮色描边 + 轻微放大。
    private val ACCENT = 0xFF0F766E.toInt()
    private val BRIGHT = 0xFF0F766E.toInt()
    private val INK = 0xFF173B35.toInt()
    private val TEXT_COLOR = 0xFF173B35.toInt()
    private val PANEL = 0xFFFFFFFF.toInt()
    private val BRIGHTER_TEXT = 0xFF617A70.toInt()
    private val SOFT = 0xFFF5F7F1.toInt()
    private val BORDER = 0xFFDFE8E2.toInt()

    /**
     * 给任意可聚焦控件装上焦点视觉。
     *
     * 关键点（踩过坑）：海报格子整块被 ImageView 占满，如果只改 background，
     * 焦点色会被图片盖住 —— 真机上看起来就是「焦点还是黑的」。
     * 所以额外用 **foreground** 画一圈亮色描边，它画在内容之上，
     * 图片加载成功、失败、纯色占位都能看见焦点。
     *
     * 另外用 InsetDrawable 内缩 3dp，让相邻按钮/行之间天然有缝，
     * 不会因为 LinearLayout 没设 margin 而连成一片。
     *
     * @param textView 需要跟着变色的文字（控件本身就是文字时传它自己）
     * @param focusedBg 聚焦时的填充色（主按钮本身就是蓝色，需要更亮一档才看得出变化）
     */
    private fun applyFocusStyle(
        view: View,
        normalBg: Int,
        textView: TextView? = null,
        normalText: Int = TEXT_COLOR,
        focusedBg: Int = ACCENT,
        onFocusChanged: ((Boolean) -> Unit)? = null,
        focusScale: Float = 1.04f,
    ) {
        val normal = InsetDrawable(
            GradientDrawable().apply {
                setColor(normalBg)
                cornerRadius = dp(14).toFloat()
            },
            dp(3),
        )
        // 焦点态 = 同样的填充 + 亮色描边。描边放进背景而不是 foreground：
        // 实测 Button 上 foreground 不渲染（放大截图里 5 个按钮完全一样），
        // 画进背景则一定能画出来，卡片和按钮也就能共用同一套样式。
        val focused = InsetDrawable(
            GradientDrawable().apply {
                setColor(normalBg)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(3), BRIGHT)
            },
            dp(3),
        )
        val ring = InsetDrawable(
            GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(3), BRIGHT)
            },
            dp(2),
        )

        // View.setBackground() 会用背景 drawable 的 padding 覆盖控件自身的 padding！
        // InsetDrawable 会报告 3dp 的 padding，于是我们设的 16dp 内边距被无声吃掉，
        // 文字直接顶到边框（真机上表现为「左边距没了、开头几个字符被裁」）。
        // 所以先把原始 padding 存下来，每次换背景后立刻还回去。
        val padLeft = view.paddingLeft
        val padTop = view.paddingTop
        val padRight = view.paddingRight
        val padBottom = view.paddingBottom

        fun render(focusedNow: Boolean) {
            // 焦点只用亮色外框表示：不改填充、不反转文字颜色。
            // （早期用蓝底+深色字，深色主题下反而显得脏；白框在深色背景上已经足够醒目）
            view.background = if (focusedNow) focused else normal
            view.setPadding(padLeft, padTop, padRight, padBottom)
            val s = if (focusedNow) focusScale else 1f
            view.scaleX = s
            view.scaleY = s
        }

        // 立刻按当前状态渲染一次：Adapter 复用时控件可能本来就是聚焦的，
        // 只挂监听不会触发，焦点框就会丢
        render(view.isFocused)
        view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            render(hasFocus)
            // 额外回调（例如输入框聚焦时弹软键盘）必须走这里，
            // 否则会被这里 setOnFocusChangeListener 覆盖掉
            onFocusChanged?.invoke(hasFocus)
        }
    }

    /** 横向工具栏放进可滚动容器：按钮再多也不会被挤出屏幕 */
    private fun hScroll(row: View): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // 焦点描边是画在控件边界上的，容器一旦裁剪就会少一条边
            clipChildren = false
            clipToPadding = false
            // 关键：容器自己不能抢焦点，否则焦点会停在滚动容器上，
            // 里面一排按钮一个都不高亮（真机表现为「焦点是灰的、看不出选中哪个」）
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(row)
        }

    /**
     * 进界面时若没有任何元素获得焦点，主动聚焦第一个可聚焦控件。
     *
     * 注意：每个子页顶部都有「返回主页」按钮，它天然是第一个可聚焦控件 ——
     * 若直接取首个，每次进页面焦点都停在返回按钮上，电视上表现为「一进来就选中返回」。
     * 所以先按 `skipMarked = true` 跳过带标记的控件；整页只有它时才退回去选它。
     */
    private fun focusFirstAvailable(container: View) {
        container.post {
            if (root.findFocus() != null) return@post
            (firstFocusable(container) ?: firstFocusable(container, skipMarked = false))
                ?.requestFocus()
        }
    }

    private fun firstFocusable(v: View, skipMarked: Boolean = true): View? {
        if (v.visibility != View.VISIBLE) return null
        if (skipMarked && v.tag == SKIP_FIRST_FOCUS) return null
        if (v.isFocusable && v !is ViewGroup) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                firstFocusable(v.getChildAt(i), skipMarked)?.let { return it }
            }
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        prefs = Prefs(this)
        // Discard update metadata left by older builds that followed the upstream project.
        if (prefs.latestTag.isNotEmpty() &&
            !prefs.latestUrl.contains("yhxi/ShiguangCinema-Android-TV", ignoreCase = true)) {
            prefs.latestTag = ""
            prefs.latestNotes = ""
            prefs.latestUrl = ""
            prefs.latestReleaseUrl = ""
            prefs.lastUpdateCheck = 0L
        }
        api = Api(prefs.serverUrl.ifEmpty { BuildConfig.DEFAULT_URL })
        api.restoreCookie(prefs.cookie)
        imageLoader = ImageLoader(this, api)
        requestedOrientation = if (isTvDevice) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        root = FrameLayout(this)
        root.setBackgroundColor(SOFT)
        setContentView(root)
        hideSystemUi()
        applySafeArea()

        if (prefs.serverUrl.isEmpty()) showSetup() else showBoot()
        maybeCheckUpdate()
    }

    /**
     * 先用上次缓存的海报墙把首页画出来，再等网络刷新。
     * 冷启动、断网、服务端慢的时候，用户第一眼就有东西可点，而不是一片空 + 转圈。
     */
    private fun primeRecommendFromCache() {
        if (recommendItems.isNotEmpty()) return
        val cached = prefs.loadRecommendCache() ?: return
        mediaType = cached.first
        recommendItems = cached.second
    }

    override fun onDestroy() {
        cancelUpdateDownload(showMessage = false)
        homeMaintenanceJob?.cancel()
        favoriteCheckJob?.cancel()
        hotSearchJob?.cancel()
        qrServer?.stop()
        scope.cancel()
        imageLoader.close()
        super.onDestroy()
    }

    override fun onPause() {
        homeMaintenanceJob?.cancel()
        favoriteCheckJob?.cancel()
        hotSearchJob?.cancel()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        resumePendingUpdateInstall()
        // 只在从播放器返回时重建首页；首次 onResume 和从系统界面返回无需白画一遍。
        if (refreshHomeAfterPlayback && ::root.isInitialized &&
            currentScreen == "recommend" && recommendItems.isNotEmpty()) {
            showRecommend()
        }
        refreshHomeAfterPlayback = false
    }

    /** 子页面（如网络诊断）点「← 返回主页」时带着 nav_home 回来，直接落到首页 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("nav_home", false)) {
            setIntent(intent)
            showRecommend()
        }
    }

    private fun hideSystemUi() {
        if (!isTvDevice) return
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

    /**
     * 手机端安全区。
     *
     * targetSdk 35 起 Android 强制 edge-to-edge：窗口不再自动避开状态栏，
     * 界面会直接画到状态栏底下（真机就是「顶部标题被时间和信号图标压住」）。
     * TV 走 [hideSystemUi] 全屏沉浸，这段只在手机上生效。
     *
     * 做法：把系统栏高度换成 root 的内边距 —— 所有子页面都挂在 root 下，
     * 一处生效全部生效，不用每个页面各自算一遍。
     * 老系统（API < 35）装饰视图已经替我们避开了，这里拿到的 inset 是 0，不会叠加。
     */
    private fun applySafeArea() {
        if (isTvDevice) return
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                top = i.top
                bottom = i.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            if (v.paddingTop != top || v.paddingBottom != bottom) {
                v.setPadding(v.paddingLeft, top, v.paddingRight, bottom)
            }
            insets
        }
        root.requestApplyInsets()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSetup()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * BACK 的层级回退：详情/片源/设置/直播 都退回上一层，
     * 只有最外层（推荐首页）才真的退出。以前任何界面按返回都直接杀掉应用。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 正在加载时 BACK 先用来取消：加载提示已经不是模态弹窗了，不能再靠"关弹窗"取消，
        // 否则用户按返回会直接翻页走人，请求还在后台跑、回来时突然跳出结果页。
        busyKey?.let { key ->
            if (busyCancellable) {
                cancelJob(key)
                dismissProgress()
                toast("已取消")
            } else {
                toast("正在处理，稍等一下…")
            }
            return
        }
        when (currentScreen) {
            "files" -> showSources()
            "qradd" -> { qrServer?.stop(); qrServer = null; showSources() }
            "results" -> showRecommend()
            "serversettings" -> showSetup()
            "detail", "live", "setup", "history" -> showRecommend()
            "sources" -> if (sourcesOrigin == "setup") showSetup() else showRecommend()
            "vodsources" -> showSources()
            "home", "recommend" -> super.onBackPressed()
            // 导入流程内的页面：逐级退回上一级，而不是退出 App
            "input" -> showAddSource()
            "manualadd" -> showAddSource()
            "addsource" -> showSources()
            "import" -> showSources()
            "homebar", "playsettings" -> showSetup()
            "boot" -> super.onBackPressed()
            else -> showRecommend()
        }
    }

    /**
     * 全局按键：
     *
     * 1. 首页顶部按钮栏的左右键拦截（系统在 View 层就完成了焦点搜索，等到 onKeyDown 已经太晚）。
     * 2. 遥控器 CH+ / CH-（以及 PageUp / PageDown）在列表里翻屏。
     *    电视台遥控器上最顺手的就是这两个键，而 TV 盒子遥控器常把它们映射成翻页键；
     *    以前完全不响应，用户只能一格一格按上下。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (pageScroll(false)) return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (pageScroll(true)) return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val focused = currentFocus
            val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) View.FOCUS_RIGHT else View.FOCUS_LEFT
            val next = focused?.focusSearch(dir)
            if (focused != null && (homeBarRows.any { focused.parent === it } || focused.parent === homeBar)) {
                if (next == null || homeBarRows.none { next.parent === it }) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 列表翻屏：当前焦点所在的可滚动列表按"可见行数"跳一屏。
     * 网格按整行对齐（一次跳 2 行，太少感觉不出来）。
     * @return 是否处理了该按键（没有可滚动列表时返回 false，让按键正常派发）
     */
    private fun pageScroll(down: Boolean): Boolean {
        val list = currentFocus as? android.widget.AbsListView ?: return false
        val count = list.count
        if (count <= 0) return false
        val cols = (list as? GridView)?.numColumns ?: 1
        val visible = (list.lastVisiblePosition - list.firstVisiblePosition + 1).coerceAtLeast(cols)
        val step = if (list is GridView) cols * (visible / cols).coerceAtLeast(2) else visible
        val from = list.selectedItemPosition.takeIf { it >= 0 } ?: list.firstVisiblePosition
        val target = (if (down) from + step else from - step).coerceIn(0, count - 1)
        if (target == from) return true
        list.setSelection(target)
        return true
    }
    /* ------------------------------------------------------------ 通用小工具 */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * 换页时给新内容一个 120ms 淡入。
     *
     * 只在**页面类型变了**的时候淡入：同一个界面因为数据刷新而重建（加载更多、
     * 历史封面回填、连接成功补推荐）不淡入，否则滚动时会满屏闪。
     */
    private var lastRenderedScreen: String? = null

    private fun setScreen(view: View, focusFirst: Boolean = true) {
        if (currentScreen != "recommend") {
            homeMaintenanceJob?.cancel()
            homeHistoryCoverViews.clear()
        }
        if (currentScreen != "recommend" && currentScreen != "favorites") favoriteCheckJob?.cancel()
        if (currentScreen != "home") hotSearchJob?.cancel()
        root.removeAllViews()
        // 以前首页铺过一条绿色渐变当"装饰"，结果半透明的入口瓦片叠上去整页发绿，
        // 文字对比度也塌了，看着像渲染坏了。现在全站统一同一个深色底，装饰靠卡片和留白做。
        root.background = android.graphics.drawable.ColorDrawable(SOFT)
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 加载胶囊挂在 root 上，removeAllViews 会把它一起摘掉，这里重新挂回最上层
        if (busyBar?.visibility == View.VISIBLE) attachBusyBar()
        if (lastRenderedScreen != null && lastRenderedScreen != currentScreen) {
            // 用 withLayer() 让动画走硬件层，老盒子上比逐帧重绘稳
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120L).withLayer().start()
        }
        lastRenderedScreen = currentScreen
        if (focusFirst) focusFirstAvailable(view)
    }

    /* ------------------------------------------------------------ 品牌标识 */

    /**
     * 时光影院字标。刻意用文字 + 形状拼出来（不引图片资源）：
     * 任意分辨率都清晰，也不给 APK 增加体积。
     */
    private fun brandRow(showTagline: Boolean = true): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        // 方块标记：播放三角
        row.addView(
            TextView(this).apply {
                setText("▶")
                setTextSize(17f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(ACCENT)
                    cornerRadius = dp(10).toFloat()
                }
            },
        )

        // 应用名称字标
        row.addView(
            TextView(this).apply {
                setText("时光影院")
                setTextColor(TEXT_COLOR)
                setTypeface(null, Typeface.BOLD)
                setTextSize(23f)
                setPadding(dp(10), 0, dp(12), 0)
                gravity = Gravity.CENTER_VERTICAL
            },
        )

        return row
    }

    /**
     * 所有子页统一的顶部条：左边品牌字标，右边「⌂ 返回主页」。
     *
     * 起因：进到详情/直播/搜索/设置这些页面后，除了遥控器 BACK 键没有任何**看得见**的
     * 回首页入口，老人小孩拿遥控器根本找不到路。现在每个子页右上角都固定一个。
     * 用 tag 标记跳过自动聚焦（见 firstFocusable），否则一进页面焦点就停在返回上。
     */
    private fun topBar(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        row.addView(
            brandRow(false),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        // 弹性占位：把返回按钮顶到最右侧，页面上永远在同一个位置
        row.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        row.addView(button("← 返回主页") { showRecommend() }.apply {
            tag = SKIP_FIRST_FOCUS
        })
        return row
    }

    private fun titleText(text: String, size: Float = 26f): TextView =
        TextView(this).apply {
            setText(text)
            setTextSize(size)
            setTextColor(TEXT_COLOR)
        }

    private fun hintText(text: String): TextView =
        TextView(this).apply {
            setText(text)
            setTextSize(15f)
            setTextColor(BRIGHTER_TEXT)
            setPadding(0, dp(6), 0, dp(10))
        }

    /**
     * 小号标签（热门搜索、快捷筛选用）。
     *
     * 以前热门搜索也用主按钮样式（16sp / 46dp 高 / 最小 76dp 宽），一排十个叠下来像一面按钮墙，
     * 和页面里的「搜索」主按钮抢注意力。这里降一档：13sp、深色卡片底、只有聚焦才有描边。
     */
    private fun chip(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            setText(label)
            setTextSize(13f)
            setTextColor(BRIGHTER_TEXT)
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { onClick() }
            applyFocusStyle(this, PANEL, this, focusScale = 1f)
        }

    /**
     * 列表形状的自绘可聚焦行（搜索历史的每一行用它）。
     *
     * 不用 ListView 塞两个可聚焦子项：ListView 一旦把焦点交给子项，
     * 方向键就会在「回收来的行」之间乱跳。这里直接拼 LinearLayout，
     * 左右键在「关键词」和「✕」之间移动，行为完全可预测。
     */
    private fun focusRow(text: String, tint: Int = TEXT_COLOR, onClick: () -> Unit): TextView =
        TextView(this).apply {
            setText(text)
            setTextSize(16f)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(tint)
            isSingleLine = true
            setPadding(dp(16), 0, dp(16), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
            val normal = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadius = dp(8).toFloat()
            }
            val focused = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(3), BRIGHT)
            }
            background = normal
            onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) focused else normal
            }
        }

    private fun field(hint: String, value: String = "", password: Boolean = false): EditText =
        NoScrollEditText(this).apply {
            // 我们的焦点框会替换掉系统输入框背景，连带丢掉它自带的内边距 ——
            // 不显式给高度和内边距的话，输入框会塌成一条细线（真机已复现）
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            minHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            setHint(hint)
            setTextColor(TEXT_COLOR)
            setHintTextColor(BRIGHTER_TEXT)
            setTextSize(18f)
            setSingleLine(true)
            inputType = if (password)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            // 时序坑（真机与模拟器都复现过）：首次布局前控件宽度是 0，此时 setText 会让
            // TextView 按「文字比容器宽」算出横向滚动偏移；布局撑开宽度后这个偏移不归零，
            // 表现为开头 1~2 个字符被裁掉。post{} 无效（它在布局之前执行），
            // 所以改成**等第一次布局真正完成后再写入文字**。
            if (value.isNotEmpty()) {
                addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                    ) {
                        v.removeOnLayoutChangeListener(this)
                        (v as EditText).setText(value)
                        v.setSelection(value.length)
                        v.scrollTo(0, 0)
                    }
                })
            }
            // 输入框不放大：放大后它会比父容器宽出 46px，实测会导致聚焦状态首字符渲染异常
            applyFocusStyle(
                view = this,
                normalBg = PANEL,
                focusedBg = 0xFFE3F1EB.toInt(),
                focusScale = 1f,
                onFocusChanged = { hasFocus ->
                    if (hasFocus) {
                        postDelayed({
                            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                                ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        }, 120)
                    }
                },
            )
        }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            setText(label)
            isAllCaps = false
            setTextSize(16f)
            isSingleLine = true
            setTextColor(if (primary) Color.WHITE else TEXT_COLOR)
            // 按内容自适应宽度：固定 dp 宽度在低分辨率电视上会被挤出屏幕
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
            minWidth = dp(76)
            minimumWidth = dp(76)
            // 替换背景后按钮自带的内边距会丢，显式补上，避免文字贴边
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { onClick() }
            applyFocusStyle(
                this,
                if (primary) ACCENT else PANEL,
                this,
                if (primary) Color.WHITE else TEXT_COLOR,
                if (primary) 0xFF0A544F.toInt() else ACCENT,
            )
        }

    /**
     * 加载提示：顶部一颗小胶囊（转圈 + 一句话），**不再是模态对话框**。
     *
     * 原来是 AlertDialog：屏幕上盖一层灰、底下什么都点不了，遥控器上看起来像卡死，
     * 而且每次搜索/进详情都要弹一下再关一下，非常吵。现在改成浮在顶部的小条，
     * 不挡内容、不抢焦点；取消改由 BACK 键负责（只取消同一个槽位的请求）。
     *
     * @param cancellable 能否用返回键取消。清理缓存这类"已经在删文件"的动作不能取消 ——
     *   中途取消只会让界面显示"已取消"，而磁盘其实还在删，反而更让人困惑。
     */
    private fun progress(text: String = "加载中…", key: String = "default", cancellable: Boolean = true) {
        dismissProgress()
        busyKey = key
        busyCancellable = cancellable
        val bar = busyOverlay()
        busyLabel?.text = text
        if (bar.parent == null) attachBusyBar()
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.animate().alpha(1f).setDuration(120L).start()
    }

    private fun dismissProgress() {
        busyKey = null
        busyBar?.visibility = View.GONE
    }

    /** 顶部加载胶囊。惰性创建，整个 Activity 只有一个实例。 */
    private fun busyOverlay(): LinearLayout {
        busyBar?.let { return it }
        val label = TextView(this).apply {
            setTextColor(TEXT_COLOR)
            setTextSize(14f)
            setPadding(dp(12), 0, dp(4), 0)
            maxLines = 1
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(20), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), BORDER)
            }
            visibility = View.GONE
            addView(
                ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleSmall).apply {
                    indeterminateDrawable?.setTint(ACCENT)
                },
                LinearLayout.LayoutParams(dp(20), dp(20)),
            )
            addView(label)
        }
        busyBar = bar
        busyLabel = label
        return bar
    }

    /** 挂到 root 最上层。setScreen 会 removeAllViews，所以每次换页都要重新挂。 */
    private fun attachBusyBar() {
        val bar = busyBar ?: return
        (bar.parent as? ViewGroup)?.removeView(bar)
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            },
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 电视列表/网格的统一工厂。两者策略不同：
     *
     * · 网格（海报）：让**子项**获得焦点（FOCUS_AFTER_DESCENDANTS），
     *   这样每张海报能画出描边 + 标题变色的焦点样式。
     * · 列表（结果/集数/频道）：让**列表本身**持有焦点，用 AdapterView 的 selector
     *   画选中高亮，配合 onItemClickListener 响应 OK 键。
     *   若让子行抢焦点，会出现「焦点停在列表上、看不出选中哪行」或「ENTER 无反应」。
     *
     * 背景都用深蓝填充 + 亮色描边：浅色文字在上面依然清晰，深色填充也不会瞎眼。
     */
    /**
     * 列表选中高亮用 **状态选择器**：只有列表自己处于聚焦状态时才画描边。
     * 之前靠「失焦时把 selector 换成透明」不生效（AdapterView 会继续沿用旧的 drawable），
     * 导致「焦点在按钮上，集数行还亮着」两个框同时出现。
     */
    private fun listSelector(): android.graphics.drawable.Drawable {
        val ring = InsetDrawable(
            GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(3), BRIGHT)
            },
            dp(3),
        )
        val empty = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(intArrayOf(), empty)
        }
    }

    private fun newList(): ListView = ListView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        divider = android.graphics.drawable.ColorDrawable(SOFT)
        dividerHeight = dp(7)
        setBackgroundColor(Color.TRANSPARENT)
        setSelector(listSelector())
        // AdapterView 会把选中高亮一直留着，焦点移走后就变成「永远是聚焦状态」。
        // 失焦时把 selector 换成透明，重新聚焦再装回来。
        setOnFocusChangeListener { v, hasFocus ->
            (v as ListView).setSelector(
                if (hasFocus) listSelector()
                else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
            )
            v.invalidate()
        }
    }

    private fun bindList(listView: ListView, items: List<String>, onItem: (Int) -> Unit) {
        listView.adapter = simpleAdapter(items)
        listView.setOnItemClickListener { _, _, position, _ -> onItem(position) }
        // 行不可聚焦（见 simpleAdapter 注释），OK 键靠「列表选中行」触发。
        // 刚建好的列表有时还没有选中行（selectedItemPosition = -1），先补一个，否则第一次按 OK 没反应
        listView.post {
            if (listView.selectedItemPosition < 0 && listView.count > 0) listView.setSelection(0)
        }
        // 有些遥控器/模拟器的 OK 键不走 onItemClick，这里显式兜底，保证按下去一定有反应
        listView.setOnKeyListener { v, keyCode, event ->
            val isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (event.action == KeyEvent.ACTION_DOWN && isOk) {
                val pos = (v as ListView).selectedItemPosition
                if (pos >= 0) {
                    onItem(pos)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    /**
     * 网格自己持有焦点（和 ListView 一致）。
     * 之前让子项获得焦点，结果方向键走的是系统焦点搜索，而屏幕外的行在 GridView 里
     * 根本还没有相应的 View，焦点找不到下一行 —— 表现为「遥控器无法向下滚动」。
     * 交给 GridView 自己处理，它知道总共有多少项，才能正常换行和滚动。
     */
    private fun newGrid(): GridView = GridView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        setSelector(listSelector())
    }

    /**
     * 统一切到后台线程调用 API，异常在 UI 线程上提示。
     *
     * @param key 请求槽位。同一个 key 的新请求会取消上一次（重复点搜索只认最后一次），
     *   不同 key 互不干扰 —— 首页补推荐不会再把正在进行的搜索顶掉。
     * @param quiet 静默模式：失败不弹对话框，只回调 [onError]。启动自动连接、首页骨架
     *   都用这个模式 —— 用户还没进界面就被弹窗拦住，是最烦人的一种体验。
     * @param onError 静默模式下的失败回调（在 UI 线程执行），由调用方决定怎么呈现。
     */
    private fun <T> runApi(
        loading: String? = "加载中…",
        block: () -> T,
        then: (T) -> Unit,
        quiet: Boolean = false,
        onError: ((Exception) -> Unit)? = null,
        key: String = "default",
    ) {
        cancelJob(key)
        if (loading != null) progress(loading, key)
        jobs[key] = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                dismissProgress()
                then(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消（切换页面 / 被同槽位的新请求顶掉）不是网络错误。
                // 以前这里被 catch(Exception) 一并接住，界面上就会冒出
                // 「网络失败：StandaloneCoroutine was cancelled」这种莫名其妙的报错。
                // 这里必须重新抛出，交给协程框架按「正常取消」处理。
                throw e
            } catch (e: Exception) {
                dismissProgress()
                if (quiet) {
                    onError?.invoke(e)
                    return@launch
                }
                val msg = describe(e)
                // 以前失败只弹一个一秒就消失的 toast，网络抖动时用户会以为「点了没反应」
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("请求失败")
                    .setMessage(msg)
                    .setPositiveButton("重试") { _, _ ->
                        runApi(loading, block, then, quiet, onError, key)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    /** 带槽位的便捷写法：runApiK("search", "正在搜索…", { … }, { … }) */
    private fun <T> runApiK(
        key: String,
        loading: String? = "加载中…",
        block: () -> T,
        then: (T) -> Unit,
        quiet: Boolean = false,
        onError: ((Exception) -> Unit)? = null,
    ) = runApi(loading, block, then, quiet, onError, key)

    private fun describe(e: Exception): String = when (e) {
        is HttpError -> {
            // 服务端的 error 文案比 "HTTP 403" 有用得多（例如防 SSRF 的说明）
            val serverMsg = try {
                org.json.JSONObject(e.body).optString("error", "")
            } catch (_: Exception) {
                ""
            }
            when {
                serverMsg.isNotEmpty() -> "$serverMsg（HTTP ${e.status}）"
                e.status == 401 -> "密码错误"
                e.status == 429 -> "尝试次数过多，请等 10 分钟"
                else -> "请求失败 HTTP ${e.status}"
            }
        }
        else -> "网络失败：${e.message ?: e.javaClass.simpleName}"
    }

    /* --------------------------------------------------------- 启动与服务器配置 */

    /**
     * 启动：**先把首页骨架画出来**，后台静默连服务器，连上之后再自动刷新。
     *
     * 以前的做法是先停在一个「正在连接服务器…」的页面 + 模态弹窗上，用户每次打开
     * 都要盯着它；网络慢或者服务器没醒的时候尤其难受。现在改成骨架先行：
     * 用户立刻看到熟悉的首页，能直接点搜索/设置，连上后海报自动补上。
     */
    private fun showBoot() {
        bootFailed = false
        // 连上之后再拉推荐，避免还没连上就先发一轮注定失败的请求
        recommendRequested = true
        primeRecommendFromCache()   // 有缓存就先把海报墙画出来
        bootNotice = if (recommendItems.isEmpty()) "正在加载…" else null
        showRecommend()
        autoConnect(silent = true)
    }

    /**
     * 连接服务器（顺带拉服务端下发的片源配置）。
     *
     * @param silent 静默连接：不弹"正在连接"对话框，失败也不弹错误框，
     *   只把原因写到首页顶部的那行小字上。启动时走这条路。
     */
    private fun autoConnect(silent: Boolean = false) {
        if (silent) {
            val oldNotice = bootNotice
            val wasFailed = bootFailed
            bootFailed = false
            bootNotice = if (recommendItems.isEmpty()) "正在加载…" else null
            if (currentScreen == "recommend" && (oldNotice != bootNotice || wasFailed)) showRecommend()
        }
        runApiK(
            key = "status",
            loading = if (silent) null else "加载中…",
            block = {
                val first = api.status()
                val st = if (first.passwordRequired && !first.verified) {
                    api.login(prefs.password)
                } else {
                    first
                }
                if (st.passwordRequired && !st.verified) {
                    throw IllegalStateException("LibreTV 登录失败，请到服务器设置检查密码")
                }
                // 服务端还可能下发 DEFAULT_SUBSCRIPTIONS（订阅链接），需要再解析一次；
                // 解析失败（例如内网地址被防 SSRF 拦掉）不影响其它通道
                val subSources = if (prefs.sources().isEmpty() && st.defaultSources.isEmpty()) {
                    st.defaultSubscriptions.flatMap { url ->
                        runCatching { api.importSourceList(url) }.getOrDefault(emptyList())
                    }
                } else {
                    emptyList()
                }
                st to subSources
            },
            then = { (st, subSources) ->
                api.currentCookie()?.let { prefs.cookie = it }
                prefs.rememberServer(prefs.serverUrl, prefs.password, prefs.cookie)
                seedLiveFromServer(st)
                seedDefaultSourcesAsync(st, subSources)
                bootNotice = null
                bootFailed = false
                recommendRequested = false
                // 用户可能在连接期间已经点进设置/搜索，别把他拽回首页
                if (currentScreen == "recommend") {
                    val hadCache = recommendItems.isNotEmpty()
                    showRecommend()
                    // 用缓存垫的首页非空，showRecommend 不会自己再拉一次；这里主动刷新
                    if (hadCache) loadRecommend()
                }
            },
            quiet = silent,
            onError = { e ->
                // 不弹窗、不反复提示：只在首页顶部留一行可重试的说明
                bootFailed = true
                bootNotice = "未连接：${describe(e)}"
                recommendRequested = true
                if (currentScreen == "recommend") showRecommend()
            },
        )
    }

    /**
     * 用服务端下发的配置填充本地：这就是「NAS 上配好了片源，客户端不用再配」的通道。
     * 只在本地为空时填充，不覆盖用户自己加过的源。
     */
    private fun seedFromServer(
        st: AppStatus,
        builtInSources: List<SourceConfig> = emptyList(),
        extraSources: List<SourceConfig> = emptyList(),
    ) {
        val initialSources = builtInSources.ifEmpty { st.defaultSources + extraSources }
        if (prefs.sources().isEmpty() && initialSources.isNotEmpty()) {
            prefs.saveSources(initialSources)
            toast(if (builtInSources.isNotEmpty()) "已添加 ${initialSources.size} 个内置片源" else "已从服务器同步 ${initialSources.size} 个片源")
        }
        seedLiveFromServer(st)
    }

    private fun seedLiveFromServer(st: AppStatus) {
        ensureBuiltInLiveSources(st.defaultLiveSources)
    }

    private fun ensureBuiltInLiveSources(serverDefaults: List<String> = emptyList()) {
        val existing = prefs.liveUrls()
        val combined = (BuiltInLiveSources.ALL + serverDefaults + existing).distinct()
        if (combined != existing) prefs.saveLiveUrls(combined)
        if (prefs.activeLiveUrl !in combined) prefs.activeLiveUrl = combined.firstOrNull().orEmpty()
    }

    /** 首次拉内置片源在后台进行，不阻塞服务端登录和首页展示。 */
    private fun seedDefaultSourcesAsync(st: AppStatus, extraSources: List<SourceConfig> = emptyList()) {
        if (prefs.sources().isNotEmpty()) return
        val expectedServer = prefs.serverUrl
        scope.launch(Dispatchers.IO) {
            val builtIn = loadBuiltInDefaultSources()
            withContext(Dispatchers.Main) {
                if (prefs.serverUrl == expectedServer && prefs.sources().isEmpty()) {
                    seedFromServer(st, builtIn, extraSources)
                }
            }
        }
    }

    /**
     * 内置默认片源：直接用 [BuiltInSources] 里固化的探活结果（2026-10-23 实测）。
     * 不再去拉 qist 的 TVBox 配置 —— 那样慢，还会带入一堆没验证过的死源。
     */
    private fun loadBuiltInDefaultSources(): List<SourceConfig> = BuiltInSources.ALL

    /* --------------------------------------------------------- 源列表的导入 */

    /**
     * 所有导入入口最终都走这里：一份文本 → 片源（含网页版导出格式）→ 直播源。
     * 导入是「按 URL 去重追加」，重复导入不会产生重复项。
     */
    private fun importText(text: String) {
        val sources = api.parseSourceList(text)
        val lives = api.parseLiveFromExport(text)
        if (sources.isEmpty() && lives.isEmpty()) {
            toast("没解析出片源。支持：网页版导出的 LibreTV-Settings_*.json、SourceList JSON、TVBOX 配置")
            return
        }
        val added = prefs.addSources(sources)
        var liveAdded = 0
        if (lives.isNotEmpty()) {
            val current = prefs.liveUrls()
            for (u in lives) {
                if (!current.contains(u)) {
                    current.add(u)
                    liveAdded++
                }
            }
            prefs.saveLiveUrls(current)
            if (prefs.activeLiveUrl.isEmpty()) prefs.activeLiveUrl = current.first()
        }
        val liveNote = if (liveAdded > 0) "，直播源新增 $liveAdded" else ""
        toast("片源 ${sources.size} 个（新增 $added）$liveNote")
        showSources()
    }

    /** 用系统文件选择器（SAF）读 JSON：不需要任何存储权限，U 盘/下载目录都能选。 */
    private fun pickJsonFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK_JSON)
        } catch (e: Exception) {
            // 电视/投影仪常把 DocumentsUI 精简掉 —— 退回内置文件浏览器
            toast("系统没有文件选择器，改用内置浏览器")
            showFileBrowser()
        }
    }

    /* ------------------------------------------------- 内置文件浏览器（无 SAF 时） */

    private var browsePath = "/storage"

    /** 请求读存储权限（API 23+）。已授权则直接返回 true。 */
    private fun ensureReadPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) return true // 33+ 由 SAF 负责，这里不折腾
        val granted = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
        }
        return granted
    }

    @Deprecated("权限回调仍走 onRequestPermissionsResult")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showFileBrowser()
            } else {
                toast("没有存储权限，读不到 U 盘文件")
            }
        }
    }

    /**
     * 简易文件浏览器：只列目录与 .json，纯 D-pad 操作。
     * 起点 /storage（U 盘一般挂在 /storage/xxxx-xxxx 或 /mnt/media_rw）。
     */
    /** 判断是不是可导入的文本类文件 */
    private fun isImportableFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".json") || n.endsWith(".txt") ||
            n.endsWith(".m3u") || n.endsWith(".m3u8")
    }

    /**
     * Android 11+ 的分区存储下，直接 listFiles() 常常返回空。
     * 这里依次试几个常见位置，返回第一个「存在且能列出内容」的目录，
     * 让内置浏览器至少有个能用的起点（U 盘 / 下载目录 / 内置存储根）。
     */
    private fun findReadableStorageRoot(): String? {
        val candidates = mutableListOf(
            "/sdcard/Download",
            "/storage/emulated/0/Download",
            "/sdcard",
            "/storage/emulated/0",
            "/mnt/media_rw",
            "/storage",
        )
        runCatching {
            java.io.File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory) candidates.add(f.absolutePath)
            }
        }
        for (p in candidates) {
            val dir = java.io.File(p)
            if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) return p
        }
        return null
    }
    private fun showFileBrowser(path: String = browsePath) {
        currentScreen = "files"
        if (!ensureReadPermission()) return
        val dir = java.io.File(path)
        browsePath = dir.absolutePath

        val entries = ArrayList<Pair<String, java.io.File>>()
        dir.parentFile?.let { entries.add("⬆  上级目录" to it) }
        val children = dir.listFiles()?.sortedWith(
            compareBy({ !it.isDirectory }, { it.name.lowercase() }),
        ).orEmpty()
        children.forEach { f ->
            val name = if (f.isDirectory) "📁  " + f.name else "📄  " + f.name
            entries.add(name to f)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText("选择 sources.json · 当前：" + browsePath, 15f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回片源管理") { showSources() })
        bar.addView(button("/sdcard") { showFileBrowser("/sdcard") })
        bar.addView(button("/storage") { showFileBrowser("/storage") })
        bar.addView(button("自动查找") {
            val found = findReadableStorageRoot()
            if (found != null) {
                showFileBrowser(found)
            } else {
                toast("系统限制访问存储，请改用「粘贴文本」或「从网址下载」")
            }
        })
        col.addView(hScroll(bar))
        // 顶部栏是横向滚动容器：左右键若找不到可见邻居，就吞掉按键，避免焦点跑到屏幕外
        for (bi in 0 until bar.childCount) {
            val bv = bar.getChildAt(bi)
            bv.setOnKeyListener { view, code, ev ->
                if (ev.action != android.view.KeyEvent.ACTION_DOWN) {
                    false
                } else if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                    code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    val dir = if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                        View.FOCUS_LEFT
                    } else {
                        View.FOCUS_RIGHT
                    }
                    val next = view.focusSearch(dir)
                    // 没有邻居，或邻居不可见（在滚动区域外）时，消费掉按键
                    // 只允许在按钮栏内部移动：邻居跑到栏外（比如海报格子）就吞掉按键
                    next == null || next.parent !== bar
                } else {
                    false
                }
            }
        }

        if (entries.isEmpty()) {
            col.addView(hintText("这个目录为空或无权访问。试试 /storage 或 /sdcard。"))
        } else {
            val listView = newList()
            bindList(listView, entries.map { it.first }, onItem = { idx ->
                val f = entries[idx].second
                if (f.isDirectory) {
                    showFileBrowser(f.absolutePath)
                } else if (!isImportableFile(f.name)) {
                    toast("只支持 .json / .txt / .m3u 文件")
                } else {
                    runApi("读取文件…", { f.readText() }, { text -> importText(text) })
                }
            })
            col.addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setScreen(col)
    }

    @Deprecated("系统文件选择器仍走 startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_JSON || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runApi("读取文件…", {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }, { text -> importText(text) })
    }

    /* ------------------------------------------------------------- 设置界面 */

    /* ------------------------------------------------------------- 设置界面 */

    /** 设置页入口：已连接时只保留高频操作，连接设置收进「修改服务器设置」 */
    /**
     * 设置页入口。
     * @param expand 明确要求展开「服务器连接」区块（用户点了「修改服务器设置」）。
     * 其余情况（从菜单、返回键回来）自动收起 —— 之前这个标志一旦置真就永不复位，
     * 导致改过一次服务器之后，再进设置永远是展开态、页脚也被挤掉。
     */
    private fun showSetup() {
        currentScreen = "setup"
        val hasServer = prefs.serverUrl.isNotEmpty()
        if (prefs.serverUrl.isNotEmpty()) showSetupCompact() else showServerSettings()
    }

    /** 设置页只显示版本；许可与来源信息保留在项目 LICENSE / THIRD-PARTY.md。 */
    private fun githubFooter(): TextView = TextView(this).apply {
        text = "时光影院 v${BuildConfig.VERSION_NAME}" +
            if (BuildConfig.TEST_BUILD) "（测试版）" else ""
        setTextColor(BRIGHTER_TEXT)
        setTextSize(13f)
        gravity = Gravity.CENTER
        setPadding(0, dp(22), 0, dp(14))
    }
    /**
     * 更新检测：每次启动最多检查一次，且 6 小时内不重复请求。
     * 请求只发一次（8 秒超时）；拿不到就放弃——失败也算作"已检查"，不会反复重试，
     * 同时保留已知的新版本标记，不因一次断网就把提示抹掉。
     */
    private fun maybeCheckUpdate() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < 6 * 3600_000L) return
        UpdateChecker.check { result ->
            prefs.lastUpdateCheck = now
            when (result) {
                is UpdateChecker.CheckResult.Success -> {
                    val info = result.release
                    if (UpdateChecker.isNewer(info.tag, BuildConfig.VERSION_NAME)) {
                        val firstSeen = prefs.latestTag != info.tag
                        prefs.latestTag = info.tag
                        prefs.latestNotes = info.notes
                        prefs.latestUrl = info.apkUrl
                        prefs.latestReleaseUrl = info.releaseUrl
                        if (firstSeen) toast("发现新版本 ${info.tag}，首页右上角可查看")
                    } else {
                        prefs.latestTag = ""
                        prefs.latestNotes = ""
                        prefs.latestUrl = ""
                        prefs.latestReleaseUrl = ""
                    }
                    refreshUpdateBadge()
                }
                is UpdateChecker.CheckResult.Failure -> Unit
            }
        }
    }

    /** 设置页手动检查，不受 6 小时自动检查间隔限制。 */
    private fun checkForUpdatesManually() {
        if (manualUpdateCheckInProgress) {
            toast("正在检查更新…")
            return
        }
        manualUpdateCheckInProgress = true
        toast("正在检查更新…")
        UpdateChecker.check { result ->
            manualUpdateCheckInProgress = false
            prefs.lastUpdateCheck = System.currentTimeMillis()
            when (result) {
                is UpdateChecker.CheckResult.Success -> {
                    val info = result.release
                    if (UpdateChecker.isNewer(info.tag, BuildConfig.VERSION_NAME)) {
                        prefs.latestTag = info.tag
                        prefs.latestNotes = info.notes
                        prefs.latestUrl = info.apkUrl
                        prefs.latestReleaseUrl = info.releaseUrl
                        prefs.updateBannerSeen = ""
                        toast("发现新版本 ${info.tag}")
                        showUpdateDetail(returnToRecommend = false)
                    } else {
                        prefs.latestTag = ""
                        prefs.latestNotes = ""
                        prefs.latestUrl = ""
                        prefs.latestReleaseUrl = ""
                        toast("已是最新版本 v${BuildConfig.VERSION_NAME}")
                        refreshUpdateBadge()
                    }
                }
                is UpdateChecker.CheckResult.Failure -> toast(when (result.reason) {
                    UpdateChecker.FailureReason.NETWORK -> "网络连接失败，请检查网络后重试"
                    UpdateChecker.FailureReason.RATE_LIMITED -> "GitHub 检查次数达到限制，请稍后再试"
                    UpdateChecker.FailureReason.HTTP_ERROR -> "GitHub 暂时无法提供更新信息（HTTP ${result.httpCode ?: "?"}）"
                    UpdateChecker.FailureReason.INVALID_RESPONSE -> "GitHub 返回的版本信息无法读取"
                })
            }
        }
    }

    private fun refreshUpdateBadge() {
        if (currentScreen != "recommend") return
        val bar = updateBadgeBar ?: return
        updateBadge?.let { bar.removeView(it) }
        updateBadge = updateCapsule()
        updateBadge?.let { bar.addView(it) }
    }
    /* --------------------------------------------------------- 更新提示 */

    /** 首页右上角的更新胶囊：有新版本且开关打开时才出现 */
    private fun updateCapsule(): TextView? {
        if (!prefs.updateBannerEnabled) return null
        val tag = prefs.latestTag
        if (!UpdateChecker.isNewer(tag, BuildConfig.VERSION_NAME)) return null
        val unseen = prefs.updateBannerSeen != tag
        return button(if (unseen) "● 新版本 $tag" else "新版本 $tag") {
            prefs.updateBannerSeen = tag
            showUpdateDetail()
        }.apply {
            if (!unseen) setTextColor(0xFF94A3B8.toInt())
        }
    }

    private fun showUpdateDetail(returnToRecommend: Boolean = true) {
        val notes = prefs.latestNotes.ifEmpty { "（这个版本没有提供更新说明）" }
        val releasePage = prefs.latestReleaseUrl.ifEmpty { "https://github.com/yhxi/ShiguangCinema-Android-TV/releases" }
        val url = prefs.latestUrl.ifEmpty { releasePage }

        // 左栏：更新说明，内容长时可滚动
        val notesView = TextView(this).apply {
            setText(notes)
            setTextColor(Color.parseColor("#cbd5e1"))
            setTextSize(14f)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(0, 0, dp(20), 0)
        }
        val notesScroll = android.widget.ScrollView(this).apply {
            addView(notesView)
            layoutParams = LinearLayout.LayoutParams(0, dp(300), 1f)
        }

        // 右栏：白色圆角卡片包住二维码，下面跟一行提示
        val qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
        }
        qrBitmap(url, dp(168))?.let { bmp ->
            qrCard.addView(ImageView(this).apply { setImageBitmap(bmp) })
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(qrCard, LinearLayout.LayoutParams(dp(196), dp(196)))
            addView(TextView(this@MainActivity).apply {
                setText("扫码下载")
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(13f)
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                setText(releasePage)
                setTextColor(0xFF38BDF8.toInt())
                setTextSize(11f)
                setPadding(0, dp(6), 0, 0)
            })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(4))
            addView(notesScroll)
            addView(right)
        }

        AlertDialog.Builder(this)
            .setTitle("新版本 " + prefs.latestTag + "（当前 " + BuildConfig.VERSION_NAME + "）")
            .setView(row)
            .setNegativeButton("下载更新") { _, _ ->
                if (prefs.latestUrl.isNotBlank()) startUpdateDownload(prefs.latestUrl, prefs.latestTag)
                else openReleasePage(releasePage)
            }
            .setPositiveButton("知道了") { _, _ -> if (returnToRecommend) showRecommend() }
            .show()
    }

    private fun openReleasePage(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("无法打开 GitHub 发布页") }
    }

    private fun startUpdateDownload(url: String, tag: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host != "github.com") {
            toast("更新下载地址无效，请打开 GitHub 发布页手动下载")
            openReleasePage(prefs.latestReleaseUrl.ifEmpty { "https://github.com/yhxi/ShiguangCinema-Android-TV/releases" })
            return
        }
        if (updateDownloadThread?.isAlive == true) {
            toast("更新正在下载")
            return
        }
        val savedFile = prefs.updateApkPath.takeIf { it.isNotBlank() }?.let(::File)
        if (savedFile?.isFile == true && validateUpdateApk(savedFile) == null) {
            showUpdateReady(savedFile, tag)
            return
        }
        prefs.updateApkPath = ""
        updateDownloadCancelled = false
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val progressText = TextView(this).apply {
            text = "正在连接 GitHub…"
            setTextColor(BRIGHTER_TEXT)
            setTextSize(14f)
            setPadding(0, 0, 0, dp(12))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
            addView(progressText)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        }
        updateProgressBar = progress
        updateProgressText = progressText
        val dialog = AlertDialog.Builder(this)
            .setTitle("下载更新 $tag")
            .setView(content)
            .setNegativeButton("取消", null)
            .create()
        updateProgressDialog = dialog
        dialog.setOnCancelListener { cancelUpdateDownload() }
        dialog.setOnCancelListener { cancelUpdateDownload() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                cancelUpdateDownload()
                dialog.dismiss()
            }
        }
        dialog.show()

        val fileName = "ShiguangCinema-${tag.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"
        val folder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
        val destination = File(folder, fileName)
        val partial = File(folder, "$fileName.part")
        val worker = Thread {
            try {
                val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "ShiguangCinema-Android-TV")
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                }
                updateDownloadConnection = connection
                connection.connect()
                if (connection.url.protocol != "https") throw IOException("下载地址未使用 HTTPS")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("GitHub 返回 HTTP ${connection.responseCode}")
                }
                val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                var downloaded = 0L
                var lastUiUpdate = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                        while (true) {
                            if (updateDownloadCancelled || Thread.currentThread().isInterrupted) throw InterruptedException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 150 || (total > 0 && downloaded >= total)) {
                                lastUiUpdate = now
                                val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1
                                runOnUiThread { updateDownloadProgress(downloaded, total, percent) }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (total > 0 && downloaded != total) throw IOException("文件未下载完整（$downloaded / $total 字节）")
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                val invalid = validateUpdateApk(destination)
                if (invalid != null) throw IOException(invalid)
                prefs.updateApkPath = destination.absolutePath
                runOnUiThread { showUpdateReady(destination, tag) }
            } catch (_: InterruptedException) {
                partial.delete()
                destination.delete()
            } catch (error: Exception) {
                partial.delete()
                destination.delete()
                if (!updateDownloadCancelled) {
                    runOnUiThread {
                        updateProgressDialog?.dismiss()
                        updateProgressDialog = null
                        updateDownloadThread = null
                        toast("更新下载失败：${error.message ?: "请检查网络后重试"}")
                    }
                }
            } finally {
                updateDownloadConnection?.disconnect()
                updateDownloadConnection = null
                if (Thread.currentThread().isInterrupted || updateDownloadCancelled) {
                    runOnUiThread {
                        updateProgressDialog?.dismiss()
                        updateProgressDialog = null
                        updateDownloadThread = null
                    }
                }
            }
        }.apply { name = "ShiguangUpdateDownload" }
        updateDownloadThread = worker
        worker.start()
    }

    private fun updateDownloadProgress(downloaded: Long, total: Long, percent: Int) {
        if (updateProgressDialog?.isShowing != true) return
        updateProgressBar?.isIndeterminate = total <= 0
        if (percent >= 0) updateProgressBar?.progress = percent
        updateProgressText?.text = if (total > 0) {
            "${formatFileSize(downloaded)} / ${formatFileSize(total)}（$percent%）"
        } else {
            "已下载 ${formatFileSize(downloaded)}"
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun cancelUpdateDownload(showMessage: Boolean = true) {
        updateDownloadCancelled = true
        updateDownloadConnection?.disconnect()
        updateDownloadThread?.interrupt()
        if (showMessage) toast("已取消更新下载")
    }

    private fun validateUpdateApk(file: File): String? {
        val info = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return "安装包无效，请重新下载"
        if (info.packageName != packageName) return "安装包不是时光影院"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (versionCode <= BuildConfig.VERSION_CODE) return "安装包版本不高于当前版本"
        return null
    }

    private fun showUpdateReady(file: File, tag: String) {
        updateDownloadThread = null
        updateProgressDialog?.dismiss()
        updateProgressDialog = null
        updateProgressBar = null
        updateProgressText = null
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("更新包已下载")
            .setMessage("时光影院 $tag 已下载完成（${formatFileSize(file.length())}）。现在交给系统安装器完成覆盖更新。")
            .setNegativeButton("稍后安装", null)
            .setPositiveButton("立即安装") { _, _ -> installDownloadedUpdate(file) }
            .show()
    }

    private fun installDownloadedUpdate(file: File) {
        val invalid = validateUpdateApk(file)
        if (invalid != null) {
            prefs.updateApkPath = ""
            file.delete()
            toast(invalid)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            prefs.updateApkPath = file.absolutePath
            prefs.updateInstallPending = true
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }.onFailure {
                prefs.updateInstallPending = false
                toast("请在系统设置中允许此应用安装未知应用")
            }
            return
        }
        val apkUri = runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }.getOrElse {
            toast("无法安全打开安装包：${it.message ?: "文件共享配置错误"}")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "时光影院更新包", apkUri)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast("无法打开系统安装器：${it.message ?: "请检查安装权限"}") }
    }

    private fun resumePendingUpdateInstall() {
        if (!prefs.updateInstallPending) return
        prefs.updateInstallPending = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast("允许此应用安装未知应用后，再点“检查更新”即可继续安装")
            return
        }
        val file = prefs.updateApkPath.takeIf { it.isNotBlank() }?.let(::File)
        if (file?.isFile == true) installDownloadedUpdate(file)
        else toast("找不到已下载的更新包，请重新下载")
    }

    /** 把任意文本渲染成二维码位图（复用 ZXing） */
    private fun qrBitmap(text: String, sizePx: Int): android.graphics.Bitmap? = runCatching {
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx)
        android.graphics.Bitmap
            .createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            .also { b ->
                for (x in 0 until sizePx) {
                    for (y in 0 until sizePx) {
                        b.setPixel(
                            x, y,
                            if (matrix.get(x, y)) 0xFF0B101A.toInt() else 0xFFFFFFFF.toInt(),
                        )
                    }
                }
            }
    }.getOrNull()
    /* --------------------------------------------------------- 首页按钮排序 */

    /** 首页顶部按钮排序：上移 / 下移 / 恢复默认，改完立刻写回并重绘首页 */
    private fun showHomeBarSettings() {
        currentScreen = "homebar"
        val order = prefs.homeBarOrder().filter { HomeBar.DEFAULT.contains(it) }.toMutableList()
        for (id in HomeBar.DEFAULT) if (!order.contains(id)) order.add(id)
        val hidden = prefs.homeBarHidden().toMutableSet()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(24), pagePad(48), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText("首页按钮排序", 20f))
        col.addView(hintText("选中一条按 OK：上移 / 下移 / 隐藏 / 恢复默认"))

        val listView = newList()
        fun refresh() {
            prefs.saveHomeBarOrder(order)
            prefs.saveHomeBarHidden(hidden)
            bindList(listView, order.mapIndexed { i, id ->
                val mark = if (hidden.contains(id)) "   （已隐藏）" else ""
                "${i + 1}.  ${HomeBar.label(id)}$mark"
            }, onItem = { idx ->
                val id = order[idx]
                val isHidden = hidden.contains(id)
                AlertDialog.Builder(this)
                    .setTitle(HomeBar.label(id) + if (isHidden) "（已隐藏）" else "")
                    .setItems(
                        arrayOf("上移", "下移", if (isHidden) "显示" else "隐藏", "恢复默认顺序"),
                    ) { _, which ->
                        when (which) {
                            0 -> if (idx > 0) {
                                val tmp = order[idx]; order[idx] = order[idx - 1]; order[idx - 1] = tmp
                            } else {
                                toast("已经在最前")
                            }
                            1 -> if (idx < order.size - 1) {
                                val tmp = order[idx]; order[idx] = order[idx + 1]; order[idx + 1] = tmp
                            } else {
                                toast("已经在最后")
                            }
                            2 -> if (isHidden) {
                                hidden.remove(id)
                            } else if (order.size - hidden.size <= 1) {
                                toast("至少要保留一个按钮")
                            } else {
                                hidden.add(id)
                            }
                            else -> {
                                order.clear()
                                order.addAll(HomeBar.DEFAULT)
                                hidden.clear()
                            }
                        }
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })
        }
        refresh()
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回设置") { showSetup() })
        col.addView(hScroll(bar))
        setScreen(col)
    }
    /** 播放缓存（磁盘）：显示占用并清空 */
    private fun showClearPlaybackCache() {
        val bytes = PlaybackCache.sizeBytes(this)
        val mb = bytes / 1024.0 / 1024.0
        val text = String.format(
            java.util.Locale.US,
            "当前占用 %.1f MB\n上限 %d MB，超出后自动淘汰最久未用的片段。",
            mb,
            prefs.cacheLimitMb,
        ) + if (PlaybackCache.isInUse()) "\n\n正在播放，退出播放后再清。" else ""
        AlertDialog.Builder(this)
            .setTitle("播放缓存")
            .setMessage(text)
            .setPositiveButton("清空") { _, _ ->
                if (PlaybackCache.clear(this)) {
                    toast("已清空播放缓存")
                } else {
                    // 有播放器活着时 release 分片缓存会让它下次读片直接崩，这里只提示不强清
                    toast("正在播放，先退出播放再清")
                }
                showSetup()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    /** Android 13+ 发通知要用户同意；不同意也不影响后台播放，只是没有那条控制条 */
    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(perm), 3001)
    }

    /** 崩溃日志：能看、能复制，方便直接发给我 */
    private fun showCrashLog() {
        val text = CrashLog.read(this)
        if (text.isEmpty()) {
            toast("没有崩溃记录，挺好")
            return
        }
        val view = TextView(this).apply {
            setText(text)
            setTextSize(11f)
            setTextColor(0xFFCBD5E1.toInt())
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this)
            .setTitle("崩溃日志（最近 ${text.split("====").size / 2} 次）")
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("cloudscreen-crash", text),
                )
                toast("已复制，粘贴发给开发者")
            }
            .setNeutralButton("清空") { _, _ ->
                CrashLog.clear(this)
                toast("已清空")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 片源管理是从哪进来的：设置页或首页，返回时回到原处 */
    private var sourcesOrigin = "home"

    /** 首页顶部按钮栏；用来拦截左右键，避免焦点跑到栏外 */
    private var homeBar: LinearLayout? = null
    /** 首页顶部入口栏的每一行（TV 只有一行；手机 3×2 时有两行），用于焦点接管。 */
    private val homeBarRows = mutableListOf<LinearLayout>()

    /** 正在测试中的源（列表右侧显示「…」） */
    private val testingUrls = mutableSetOf<String>()

    /* ------------------------------------------------------------- 清理缓存 */

    private fun showClearCache() {
        fun mb(bytes: Long) = String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        val imgs = if (::imageLoader.isInitialized) imageLoader.cacheSize() else 0
        val posterDisk = if (::imageLoader.isInitialized) imageLoader.diskSizeBytes() else 0L
        val hist = prefs.history().size
        val search = prefs.searchHistory().size
        val progressCount = prefs.progressCount()   // 注意别叫 progress：会遮住同名的方法
        val mediaDisk = PlaybackCache.sizeBytes(this)
        // 以前这里只显示占用、点「清理」却不删它 —— 数字显示 800MB，清完还是 800MB
        val msg = buildString {
            append("海报：内存 ").append(imgs).append(" 张 · 磁盘 ").append(mb(posterDisk)).append('\n')
            append("播放缓存（磁盘）：").append(mb(mediaDisk))
            append(" / 上限 ").append(prefs.cacheLimitMb).append(" MB\n")
            append("观看历史：").append(hist).append(" 条\n")
            append("搜索记录：").append(search).append(" 条\n")
            append("播放进度：").append(progressCount).append(" 条\n\n")
            append("片源配置、直播源和服务器设置不会被清除。")
            if (PlaybackCache.isInUse()) append("\n\n正在播放，播放缓存这次会跳过（退出播放后再清）。")
        }
        AlertDialog.Builder(this)
            .setTitle("清理缓存")
            .setMessage(msg)
            .setPositiveButton("清理") { _, _ ->
                // 删几 GB 分片在老盒子上要好几秒，放后台做，界面给个"正在清理"
                progress("正在清理…", "clear", cancellable = false)
                scope.launch {
                    val n = withContext(Dispatchers.IO) { imageLoader.clearCache() }
                    val mediaCleared = withContext(Dispatchers.IO) { PlaybackCache.clear(this@MainActivity) }
                    prefs.clearHistory()
                    prefs.clearSearchHistory()
                    prefs.clearAllProgress()
                    dismissProgress()
                    toast(
                        "已清理：海报 " + n + " 张 + " + mb(posterDisk) +
                            "、历史 " + hist + " 条、搜索 " + search + " 条、进度 " + progressCount + " 条" +
                            if (mediaCleared) "、播放缓存 " + mb(mediaDisk) else "（播放缓存占用中，未清）",
                    )
                    showSetup()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    /* ------------------------------------------------------------- 播放设置 */

    /** 单选弹窗（用全局对话框主题，和片源操作菜单同一套样式） */
    private fun chooseOption(title: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    /** 播放设置：每项点开菜单选择，和全站弹窗风格一致 */
    private fun showPlaySettings() {
        currentScreen = "playsettings"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(24), pagePad(48), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText("播放设置", 20f))
        col.addView(hintText("手机直接点一行；遥控器选中后按 OK，在弹出的菜单里选择"))
        col.addView(hintText("片头/片尾的具体位置在播放页「跳过」里标一次，整部剧通用"))

        fun rows(): List<String> = listOf(
            "快进 / 快退步长    ${prefs.seekStepSeconds} 秒",
            "自动连播下一集    ${if (prefs.autoNext) "开" else "关"}",
            "记住播放进度      ${if (prefs.resumeEnabled) "开" else "关"}",
            "续播起始位置      超过 ${prefs.resumeThresholdSeconds} 秒",
            "解码方式          " + when (prefs.decodeMode) { 1 -> "强制软解"; 2 -> "强制硬解"; 3 -> "硬解优先"; else -> "自动" },
            "播放缓存上限      " + when (prefs.cacheLimitMb) {
                512 -> "512 MB"
                1024 -> "1 GB"
                4096 -> "4 GB"
                else -> "2 GB"
            },
            "后台播放          ${if (prefs.backgroundPlay) "开（退出后继续播）" else "关（退出即停止）"}",
            "起播缓冲          " + when (prefs.bufferMode) {
                0 -> "快速（秒开）"
                2 -> "稳定（抗卡顿）"
                else -> "均衡"
            },
            "自动跳片头        ${if (prefs.skipIntroEnabled) "开" else "关"}",
            "自动跳片尾        ${if (prefs.skipOutroEnabled) "开" else "关"}",
        )

        val listView = newList()
        fun refresh() {
            // 选完一项后重建适配器，选中行会掉回第一行；记住当前行再还回去，
            // 否则「改完起播缓冲，焦点跑回最上面」要重新往下找
            val keep = listView.selectedItemPosition
            bindList(listView, rows(), onItem = { idx ->
                when (idx) {
                    0 -> chooseOption("快进 / 快退步长", listOf("10 秒", "15 秒", "30 秒"),
                        listOf(10, 15, 30).indexOf(prefs.seekStepSeconds).coerceAtLeast(0)
                    ) { i -> prefs.seekStepSeconds = listOf(10, 15, 30)[i]; refresh() }
                    1 -> chooseOption("自动连播下一集", listOf("开", "关"),
                        if (prefs.autoNext) 0 else 1
                    ) { i -> prefs.autoNext = (i == 0); refresh() }
                    2 -> chooseOption("记住播放进度", listOf("开", "关"),
                        if (prefs.resumeEnabled) 0 else 1
                    ) { i -> prefs.resumeEnabled = (i == 0); refresh() }
                    3 -> chooseOption("续播起始位置", listOf("超过 5 秒", "超过 30 秒", "超过 60 秒"),
                        listOf(5, 30, 60).indexOf(prefs.resumeThresholdSeconds).coerceAtLeast(0)
                    ) { i -> prefs.resumeThresholdSeconds = listOf(5, 30, 60)[i]; refresh() }
                    4 -> {
                        // 索引与 decodeMode 取值靠 decodeValues 映射，UI 顺序可以随便排
                        val decodeNames = listOf("硬解优先（推荐）", "自动", "强制软解", "强制硬解")
                        val decodeValues = listOf(3, 0, 1, 2)
                        chooseOption(
                            "解码方式（下次播放生效）",
                            decodeNames,
                            decodeValues.indexOf(prefs.decodeMode.coerceIn(0, 3)).coerceAtLeast(0),
                        ) { i -> prefs.decodeMode = decodeValues[i]; refresh() }
                    }
                    5 -> chooseOption(
                        "播放缓存上限",
                        listOf("512 MB", "1 GB", "2 GB", "4 GB"),
                        listOf(512, 1024, 2048, 4096).indexOf(prefs.cacheLimitMb).coerceAtLeast(2),
                    ) { i ->
                        prefs.cacheLimitMb = listOf(512, 1024, 2048, 4096)[i]
                        PlaybackCache.recreate(this)
                        toast("已设为 ${listOf("512 MB", "1 GB", "2 GB", "4 GB")[i]}，下次播放生效")
                        refresh()
                    }
                    6 -> chooseOption("后台播放", listOf("开：退出后继续播放", "关：退出即停止并释放"),
                        if (prefs.backgroundPlay) 0 else 1
                    ) { i ->
                        prefs.backgroundPlay = (i == 0)
                        if (i == 0) ensureNotificationPermission()
                        toast(if (i == 0) "已开启：按返回键退出播放页，声音继续" else "已关闭：退出播放页即停止")
                        refresh()
                    }
                    7 -> chooseOption(
                        "起播缓冲（下次播放生效）",
                        listOf("快速：最快起播，弱网易卡", "均衡：默认", "稳定：多缓冲一点，抗卡顿"),
                        prefs.bufferMode,
                    ) { i ->
                        prefs.bufferMode = i
                        toast("已设为「${listOf("快速", "均衡", "稳定")[i]}」，下次播放生效")
                        refresh()
                    }
                    8 -> chooseOption("自动跳片头", listOf("开", "关"),
                        if (prefs.skipIntroEnabled) 0 else 1
                    ) { i ->
                        prefs.skipIntroEnabled = (i == 0)
                        toast(
                            if (i == 0) "已开启：每集自动跳过片头（片头位置在播放页「跳过」里标）"
                            else "已关闭自动跳片头",
                        )
                        refresh()
                    }
                    9 -> chooseOption("自动跳片尾", listOf("开", "关"),
                        if (prefs.skipOutroEnabled) 0 else 1
                    ) { i ->
                        prefs.skipOutroEnabled = (i == 0)
                        toast(
                            if (i == 0) "已开启：到片尾开始点自动下一集（片尾位置在播放页「跳过」里标）"
                            else "已关闭自动跳片尾",
                        )
                        refresh()
                    }
                }
            })
            if (keep > 0) listView.post { listView.setSelection(keep) }
            listView.requestFocus()
        }
        refresh()
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回设置") { showSetup() })
        col.addView(hScroll(bar))
        setScreen(col)
    }
    private fun showSetupCompact() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(20), pagePad(40), dp(16))
        }
        col.addView(topBar())
        col.addView(titleText("设置", if (isTvDevice) 30f else 27f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(18), 0, 0)
        })
        col.addView(hintText("让观影体验保持顺手、稳定。"))

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(17), dp(20), dp(17))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0F766E.toInt(), 0xFF174E48.toInt()),
            ).apply { cornerRadius = dp(20).toFloat() }
            isFocusable = true
            isClickable = true
            setOnClickListener { showServerSettings() }
        }
        status.addView(TextView(this).apply {
            text = "●  服务端已连接"
            setTextColor(0xFFD7FFF0.toInt())
            setTextSize(13f)
            setTypeface(null, Typeface.BOLD)
        })
        status.addView(TextView(this).apply {
            // 匹配到预设服务端时只显示名字（如 tvoracle），不暴露网址
            text = ServerPresets.ALL
                .firstOrNull { it.url.trimEnd('/') == prefs.serverUrl.trimEnd('/') }
                ?.name
                ?: prefs.serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
            setTextColor(Color.WHITE)
            setTextSize(if (isTvDevice) 21f else 17f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(7), 0, dp(4))
        })
        status.addView(TextView(this).apply {
            text = "${prefs.sources().size} 个片源  ·  点击切换服务端"
            setTextColor(0xFFC6E5DD.toInt())
            setTextSize(13f)
        })
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        fun section(title: String) {
            col.addView(titleText(title, if (isTvDevice) 19f else 16f).apply {
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(26), 0, dp(10))
            })
        }
        fun row(icon: String, title: String, detail: String, onClick: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(PANEL)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), BORDER)
                }
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
                setOnFocusChangeListener { v, focused ->
                    v.background = GradientDrawable().apply {
                        setColor(PANEL)
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(if (focused) 3 else 1), if (focused) ACCENT else BORDER)
                    }
                }
            }
            item.addView(FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(0xFFE5F3ED.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                addView(HomeIconView(this@MainActivity, icon).apply { tintColor = ACCENT },
                    FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val words = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(4), 0)
            }
            words.addView(TextView(this).apply {
                text = title
                setTextColor(INK)
                setTextSize(if (isTvDevice) 19f else 16f)
                setTypeface(null, Typeface.BOLD)
            })
            words.addView(TextView(this).apply {
                text = detail
                setTextColor(BRIGHTER_TEXT)
                setTextSize(if (isTvDevice) 14f else 12f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            item.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            item.addView(TextView(this).apply {
                text = "›"
                setTextColor(ACCENT)
                setTextSize(27f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(24), -2))
            col.addView(item, LinearLayout.LayoutParams(-1, dp(if (isTvDevice) 78 else 72)).apply {
                bottomMargin = dp(9)
            })
        }
        section("观影设置")
        row("sources", "片源管理", "点播源 ${prefs.sources().size} 个 · 直播源 ${prefs.liveUrls().size} 个 · 管理") { showSources() }
        row("tv", "播放设置", "续播、画质、缓冲与跳过片头片尾") { showPlaySettings() }
        row("setup", "切换服务端", "选择预设服务端，输入密码连接") { showServerSettings() }
        section("维护工具")
        row("history", "清理缓存", "管理本地缓存占用") { showClearCache() }
        row("search", "网络诊断", "检测连接和片源响应") {
            startActivity(Intent(this, NetDiagActivity::class.java))
        }
        row("history", "检查更新", "立即检查 GitHub 上的最新版本") { checkForUpdatesManually() }
        val hasCrash = CrashLog.read(this).isNotEmpty()
        row("history", "崩溃日志", if (hasCrash) "有记录，可复制排查" else "暂无记录") { showCrashLog() }
        col.addView(githubFooter())
        setScreen(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
        })
    }

    /** 服务器设置：只负责地址与密码，其他入口都在设置摘要页 */
    private fun showServerSettings() {
        currentScreen = "serversettings"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(56), dp(24), pagePad(56), dp(24))
        }
        col.addView(topBar())
        col.addView(titleText("服务器设置", 20f))
        col.addView(hintText("点预设卡片选择服务器，再在下方输入密码连接"))

        // 输入框先创建、稍后再挂到页面上 —— 预设卡片要显示在最上面，
        // 但点卡片时需要能直接改这两个框的内容。
        val urlField = field("请输入网址", prefs.serverUrl)
        val currentServer = prefs.serverUrl.trimEnd('/')
        val currentProfile = prefs.savedServers().firstOrNull {
            it.url.trimEnd('/').equals(currentServer, ignoreCase = true)
        }
        val pwdField = field(
            "请输入密码",
            currentProfile?.password?.takeIf { it.isNotEmpty() } ?: prefs.password,
            password = true,
        )

        // 登录逻辑在下面定义（要用到输入框），先占个位让卡片能调用
        var doLogin: () -> Unit = {}

        col.addView(titleText("预设服务端", if (isTvDevice) 19f else 16f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(10))
        })
        ServerPresets.ALL.forEach { preset ->
            val current = prefs.serverUrl.trimEnd('/') == preset.url.trimEnd('/')
            fun cardBg(focused: Boolean) = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(if (focused) 3 else 1), if (focused) ACCENT else BORDER)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(13), dp(16), dp(13))
                background = cardBg(false)
                isFocusable = true
                isClickable = true
                isFocusableInTouchMode = true
                setOnFocusChangeListener { v, focused -> v.background = cardBg(focused) }
                setOnClickListener {
                    urlField.setText(preset.url)
                    val savedPassword = prefs.savedServers().firstOrNull {
                        it.url.trimEnd('/').equals(preset.url.trimEnd('/'), ignoreCase = true)
                    }?.password.orEmpty()
                    val password = preset.password.ifEmpty { savedPassword }
                    pwdField.setText(password)
                    if (password.isNotEmpty()) doLogin()
                    else {
                        pwdField.requestFocus()
                        toast("请输入服务端密码后连接")
                    }
                }
            }
            val words = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(10), 0)
            }
            words.addView(TextView(this).apply {
                text = preset.name + if (current) "（当前）" else ""
                setTextColor(INK)
                setTextSize(if (isTvDevice) 19f else 17f)
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(this).apply {
                val hasSavedPassword = prefs.savedServers().any {
                    it.url.trimEnd('/').equals(preset.url.trimEnd('/'), ignoreCase = true) && it.password.isNotEmpty()
                }
                text = if (current) "已连接" else if (preset.password.isEmpty() && !hasSavedPassword) "填写密码 ›" else "一键登录 ›"
                setTextColor(ACCENT)
                setTextSize(14f)
            })
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }

        col.addView(titleText("手动填写", if (isTvDevice) 19f else 16f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(8))
        })
        col.addView(urlField)
        col.addView(pwdField)
        fun submitLogin() {
            val url = urlField.text.toString().trim()
            val pwd = pwdField.text.toString()
            if (url.isEmpty()) {
                toast("请填服务器地址")
                return
            }
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                toast("地址须以 https:// 或 http:// 开头")
                return
            }
            val candidate = Api(url)
            val normalizedUrl = url.trimEnd('/')
            val profile = prefs.savedServers().firstOrNull {
                it.url.trimEnd('/').equals(normalizedUrl, ignoreCase = true)
            }
            val effectivePassword = pwd.ifEmpty {
                profile?.password.orEmpty().ifEmpty {
                    if (normalizedUrl.equals(prefs.serverUrl.trimEnd('/'), ignoreCase = true)) prefs.password else ""
                }
            }
            val storedCookie = if (normalizedUrl.equals(prefs.serverUrl.trimEnd('/'), ignoreCase = true)) prefs.cookie
                else profile?.cookie.orEmpty()
            candidate.restoreCookie(storedCookie)
            runApiK("connect", "正在连接…", {
                val st = candidate.status()
                val verified = if (st.passwordRequired && !st.verified) {
                    candidate.login(effectivePassword).verified
                } else {
                    st.verified
                }
                Triple(st, verified, effectivePassword)
            }, { (st, verified, pw) ->
                if (st.passwordRequired && !verified) {
                    toast("密码未通过验证，请检查后重试")
                    return@runApiK
                }
                api = candidate
                // 只换 Api 绑定，保留已下载的海报缓存（重建 loader 会白下一遍图）
                imageLoader.updateApi(candidate)
                prefs.serverUrl = url
                val savedSecurely = prefs.rememberServer(url, pw, candidate.currentCookie().orEmpty())
                if (!savedSecurely) toast("已连接，但设备无法安全保存密码，下次连接需重新输入")
                toast(if (verified) "已连接 v${st.version}" else "已连接，但登录未生效")
                seedLiveFromServer(st)
                seedDefaultSourcesAsync(st)
                showSetup()
            })
        }
        // 预设卡片点击后走这里（submitLogin 在上方已定义）
        doLogin = { submitLogin() }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("连接并登录", primary = true) { submitLogin() })
        bar.addView(button("返回") {
            showSetup()
        })
        col.addView(hScroll(bar))

        setScreen(ScrollView(this).apply { addView(col) })
        urlField.requestFocus()
    }

    /* ------------------------------------------------------------- 片源管理 */

    /* ------------------------------------------------------------- 片源管理 */

    /**
     * 片源健康度看板里的小圆点 + 数字。传 onClick 就能点（例如「上次失败」直接跳去停用）。
     * 不可点的（可用 / 已停用）保持纯展示，避免焦点在只读元素上空转。
     */
    private fun healthChip(label: String, color: Int, onClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(16), dp(4))
            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(7) })
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(color)
                setTextSize(14f)
            })
            if (onClick != null) {
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { onClick() }
                val normal = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(8).toFloat()
                }
                val focused = GradientDrawable().apply {
                    setColor(0xFF1B2431.toInt())
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(3), BRIGHT)
                }
                background = normal
                onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    v.background = if (hasFocus) focused else normal
                }
            }
        }

    /** 片源入口：点播与直播分开显示，避免把两类来源混在同一份长列表里。 */
    private fun showSources() {
        if (currentScreen !in setOf("sources", "vodsources", "addsource", "import", "qradd")) {
            sourcesOrigin = if (currentScreen == "setup") "setup" else "home"
        }
        currentScreen = "sources"
        ensureBuiltInLiveSources()
        val vodSources = prefs.sources()
        val liveSources = prefs.liveUrls()
        val enabledVodCount = vodSources.count { it.enabled }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(20), pagePad(40), dp(16))
        }
        col.addView(topBar())
        col.addView(titleText("片源管理", 20f))
        col.addView(hintText("点播源和直播源分开管理"))
        fun category(title: String, detail: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), 0xFFE0EAE4.toInt())
                }
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { action() }
                onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                    (view.background as? GradientDrawable)?.setStroke(dp(if (focused) 3 else 1), if (focused) ACCENT else 0xFFE0EAE4.toInt())
                }
            }
            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textColumn.addView(TextView(this).apply {
                text = "▸  $title"
                setTextColor(ACCENT)
                setTextSize(if (isTvDevice) 19f else 16f)
                setTypeface(null, Typeface.BOLD)
            })
            textColumn.addView(TextView(this).apply {
                text = detail
                setTextColor(BRIGHTER_TEXT)
                setTextSize(if (isTvDevice) 14f else 12f)
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(TextView(this).apply {
                text = "›"
                setTextColor(ACCENT)
                setTextSize(27f)
            })
            col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isTvDevice) 80 else 72)).apply { bottomMargin = dp(10) })
        }
        category("点播源 ${vodSources.size} 个 · 管理", "已启用 $enabledVodCount 个 · 影视采集站", ::showVodSources)
        category("直播源 ${liveSources.size} 个 · 管理", "IPTV 频道 · 默认使用第一个直播源", ::showLive)
        col.addView(button("返回") { if (sourcesOrigin == "setup") showSetup() else showRecommend() })
        setScreen(col)
    }

    /** 点播源的详细列表与检测状态。 */
    private fun showVodSources() {
        currentScreen = "vodsources"
        val sources = prefs.sources()
        val enabledCount = sources.count { it.enabled }
        // 复选框多选：勾选后可批量启用/停用（直播源上线后沿用同一套交互）
        val checked = HashSet<String>()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(20), pagePad(40), dp(16))
        }
        col.addView(topBar())
        col.addView(titleText("点播源 · ${sources.size} 个（启用 $enabledCount）", 18f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回片源分类") { showSources() })
        bar.addView(button("添加片源", primary = true) { showAddSource() })
        bar.addView(button("全部测试") { runAllSourceTests() })
        bar.addView(button("停用失败源") { disableFailedSources() })
        // 复选框批量操作：全选后一键启停，比逐个长按快
        bar.addView(button("全选 / 清空") {
            if (checked.size >= sources.size) {
                checked.clear()
                toast("已清空选择")
            } else {
                checked.addAll(sources.map { it.url })
                toast("已选 ${checked.size} 个")
            }
            showVodSources()
        })
        bar.addView(button("批量启用") {
            applyBatchEnable(checked, true)
        })
        bar.addView(button("批量停用") {
            applyBatchEnable(checked, false)
        })
        // 一键清空：换订阅、或者被脏源拖慢搜索时，逐条删太费事
        bar.addView(button("删除全部源") {
            if (sources.isEmpty()) {
                toast("当前没有片源")
                return@button
            }
            AlertDialog.Builder(this)
                .setTitle("删除全部片源？")
                .setMessage("将清空 ${sources.size} 个点播源，直播源不受影响。删除后需要重新添加或导入。")
                .setPositiveButton("全部删除") { _, _ ->
                    prefs.saveSources(emptyList())
                    toast("已删除全部片源")
                    showVodSources()
                }
                .setNegativeButton("取消", null)
                .show()
        })
        col.addView(hScroll(bar))

        // 健康度看板：以前要一行行翻才能知道「到底还有几个源是活的」。
        // 这里把最近一次检测的结果汇总成四个数字，点一下就跳到对应的操作。
        run {
            var ok = 0
            var bad = 0
            var unknown = 0
            var lastAt = 0L
            sources.forEach { s ->
                val r = prefs.sourceTest(s.url)
                when {
                    r == null -> unknown++
                    r.first -> ok++
                    else -> bad++
                }
                val at = prefs.sourceTestAt(s.url)
                if (at > lastAt) lastAt = at
            }
            val board = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFF141A26.toInt())
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            chips.addView(healthChip("可用 $ok", 0xFF4ADE80.toInt()))
            chips.addView(
                healthChip("上次失败 $bad", 0xFFF87171.toInt()) { if (bad > 0) disableFailedSources() },
            )
            chips.addView(healthChip("未测试 $unknown", 0xFF94A3B8.toInt()) { runAllSourceTests() })
            chips.addView(healthChip("已停用 ${sources.size - enabledCount}", 0xFF64748B.toInt()))
            val lastText = if (lastAt == 0L) {
                "还没有测过：点「全部测试」探一遍"
            } else {
                "最近检测：" + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(lastAt))
            }
            chips.addView(
                TextView(this).apply {
                    text = "   $lastText"
                    setTextColor(Color.parseColor("#64748b"))
                    setTextSize(12f)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            board.addView(chips)
            col.addView(board, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4); bottomMargin = dp(6) })
        }

        val rows = sources
        val listView = newList()
        sourcesListView = listView
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size

            override fun getItem(position: Int): Any = position

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(14), dp(8), dp(14))
                }
                row.removeAllViews()
                val s = rows[position]
                val cb = android.widget.CheckBox(this@MainActivity).apply {
                    isChecked = s.url in checked
                    setOnCheckedChangeListener { _, now ->
                        if (now) checked.add(s.url) else checked.remove(s.url)
                    }
                }
                row.addView(cb, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(4) })
                val dot = View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (s.enabled) ACCENT else Color.parseColor("#64748b"))
                    }
                }
                val name = TextView(this@MainActivity).apply {
                    text = s.name
                    setTextColor(TEXT_COLOR)
                    setTextSize(16f)
                    isSingleLine = true
                    maxWidth = dp(360)
                }
                val domain = TextView(this@MainActivity).apply {
                    text = s.url
                    setTextColor(Color.parseColor("#64748b"))
                    setTextSize(13f)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(dp(20), 0, dp(12), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val status = TextView(this@MainActivity).apply {
                    val r = prefs.sourceTest(s.url)
                    text = when {
                        testingUrls.contains(s.url) -> "…"
                        !s.enabled -> "已停用"
                        r == null -> "未测试"
                        // r.second 是服务端返回的「结果条数/状态码」，不是耗时 —— 以前拼成 "12ms" 会误导
                        r.first -> "可用 · ${r.second} 条"
                        else -> "上次失败"
                    }
                    setTextColor(
                        when {
                            !s.enabled -> Color.parseColor("#64748b")
                            r == null -> Color.parseColor("#94a3b8")
                            r.first -> Color.parseColor("#4ade80")
                            else -> Color.parseColor("#f87171")
                        },
                    )
                    setTextSize(13f)
                }
                row.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(14) })
                row.addView(name)
                row.addView(domain)
                row.addView(status)
                return row
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            sourceActions(position)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val list = prefs.sources()
            if (position in list.indices) {
                list[position] = list[position].copy(enabled = !list[position].enabled)
                prefs.saveSources(list)
                toast(if (list[position].enabled) "已启用「${list[position].name}」" else "已停用「${list[position].name}」")
                showVodSources()
            }
            true
        }
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setScreen(col)
    }

    /**
     * 一键停用失败源：把「上次测试失败」且仍在启用的源关掉。
     *
     * 采集站死一批是常态，留着它们只会让每次搜索都等满超时（每个源 8 秒）。
     * 这里只动本地 enabled 标记，随时可以再启用，不删任何配置。
     */
    private fun disableFailedSources() {
        val list = prefs.sources()
        val failed = list.filter { it.enabled && prefs.sourceTest(it.url)?.first == false }
        if (failed.isEmpty()) {
            toast("没有检测失败的片源（可先点「全部测试」）")
            return
        }
        val preview = failed.take(8).joinToString("\n") { "· " + it.name } +
            if (failed.size > 8) "\n… 共 ${failed.size} 个" else ""
        AlertDialog.Builder(this)
            .setTitle("停用 ${failed.size} 个失败源？")
            .setMessage(preview + "\n\n只做本地停用，随时可以重新启用。")
            .setPositiveButton("全部停用") { _, _ ->
                val urls = failed.map { it.url }.toHashSet()
                prefs.saveSources(list.map { if (it.url in urls) it.copy(enabled = false) else it })
                toast("已停用 ${failed.size} 个失败源")
                showVodSources()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 复选框批量启用/停用：勾选若干源后一键切换本地 enabled 标记。
     * 只动标记不删配置，随时可反向操作；直播源上线后沿用同一交互。
     */
    private fun applyBatchEnable(checked: HashSet<String>, enable: Boolean) {
        if (checked.isEmpty()) {
            toast("请先勾选要${if (enable) "启用" else "停用"}的片源")
            return
        }
        val urls = checked.toHashSet()
        val n = urls.size
        val act = if (enable) "启用" else "停用"
        AlertDialog.Builder(this)
            .setTitle("$act $n 个片源？")
            .setMessage("将批量$act 勾选的 $n 个片源。只改本地启用状态，随时可以再改回来。")
            .setPositiveButton("全部$act") { _, _ ->
                prefs.saveSources(
                    prefs.sources().map { if (it.url in urls) it.copy(enabled = enable) else it },
                )
                toast("已$act $n 个片源")
                showVodSources()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 全部测试：并发探测启用中的源，结果写回 Prefs 供列表展示 */
    private fun runAllSourceTests() {
        val list = prefs.sources().filter { it.enabled }
        if (list.isEmpty()) {
            toast("没有启用的片源")
            return
        }
        toast("正在测试 ${list.size} 个片源…")
        testingUrls.clear()
        list.forEach { testingUrls.add(it.url) }
        showVodSources()
        scope.launch {
            var failed = 0
            // 并发测、但限 4 路：串行时 20 个源按超时算要一两分钟；并发太多又会把公共采集站打挂
            val results = ArrayList<Pair<Boolean, Int>?>()
            for (chunk in list.chunked(4)) {
                results.addAll(
                    chunk.map { s ->
                        async(Dispatchers.IO) { runCatching { api.testSource(s.url) }.getOrNull() }
                    }.awaitAll(),
                )
            }
            results.forEachIndexed { i, r ->
                val s = list[i]
                if (r != null) {
                    prefs.saveSourceTest(s.url, r.first, r.second.toLong())
                    if (!r.first) failed++
                }
                testingUrls.remove(s.url)
            }
        if (currentScreen == "vodsources") {
                // 只刷新行内容，不重建整页：否则每测完一个源，列表都重画一次、焦点乱跳
                val lv = sourcesListView
                if (lv != null) (lv.adapter as? BaseAdapter)?.notifyDataSetChanged() else showVodSources()
                toast(if (failed > 0) "测试完成：$failed 个不可用" else "测试完成：全部可用")
            }
        }
    }

    private fun sourceActions(index: Int) {
        val s = prefs.sources().getOrNull(index) ?: return
        val actions = arrayOf(
            if (s.enabled) "停用（搜索时跳过）" else "启用",
            "编辑名称",
            "编辑地址",
            "上移",
            "下移",
            "测试连通性",
            "删除",
        )
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(actions) { _, which ->
                val cur = prefs.sources()
                if (index !in cur.indices) return@setItems
                when (which) {
                    0 -> {
                        cur[index] = cur[index].copy(enabled = !cur[index].enabled)
                        prefs.saveSources(cur)
                        showVodSources()
                    }
                    1 -> editSourceField(index, name = true)
                    2 -> editSourceField(index, name = false)
                    3 -> swapSources(index, index - 1)
                    4 -> swapSources(index, index + 1)
                    5 -> testSource(cur[index])
                    6 -> {
                        prefs.saveSources(cur.filterIndexed { i, _ -> i != index })
                        toast("已删除")
                        showVodSources()
                    }
                }
            }
            .show()
    }

    private fun swapSources(a: Int, b: Int) {
        val list = prefs.sources()
        if (a !in list.indices || b !in list.indices) {
            toast("已经到边了")
            return
        }
        val tmp = list[a]
        list[a] = list[b]
        list[b] = tmp
        prefs.saveSources(list)
        showVodSources()
    }

    private fun editSourceField(index: Int, name: Boolean) {
        val item = prefs.sources().getOrNull(index) ?: return
        val input = field(
            if (name) "名称" else "https://…/api.php/provide/vod",
            if (name) item.name else item.url,
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(if (name) "片源名称" else "采集站地址")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    val list = prefs.sources()
                    if (index in list.indices) {
                        list[index] =
                            if (name) list[index].copy(name = v) else list[index].copy(url = v)
                        prefs.saveSources(list)
                    }
                    showVodSources()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testSource(s: SourceConfig) {
        runApiK("test", "正在测试「${s.name}」…", { api.testSource(s.url) }, { r ->
            toast(
                if (r.first) "「${s.name}」可用，返回 ${r.second} 个结果"
                else "「${s.name}」不可用（HTTP ${r.second}）",
            )
        })
    }
    /* --------------------------------------------------------------- 添加片源 */

    /* --------------------------------------------------------------- 添加片源 */

    /** 统一样式的输入页：替代 AlertDialog，风格与全站一致（白框 + 按钮） */
    private fun inputPage(
        title: String,
        hint: String,
        value: String = "",
        multiline: Boolean = false,
        backTo: () -> Unit = { showAddSource() },
        onOk: (String) -> Unit,
    ) {
        currentScreen = "input"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(24), pagePad(48), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText(title, 20f))
        val input = field(hint, value)
        if (multiline) {
            input.setSingleLine(false)
            input.minLines = 8
            input.maxLines = 14
            input.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300))
        }
        col.addView(input)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("确定", primary = true) { onOk(input.text.toString().trim()) })
        bar.addView(button("返回") { backTo() })
        col.addView(hScroll(bar))
        setScreen(col)
        input.requestFocus()
    }

    /** 添加片源：应用内列表（不是弹窗） */
    private fun showAddSource() {
        currentScreen = "addsource"

        // 按「最省事 → 最麻烦」排序，每项给一句用途说明
        val options = listOf(
            Triple("内置默认片源", "16 个实测点播接口，https 源可设备直连", {
                runApi("导入中…", { loadBuiltInDefaultSources() }, { sources ->
                    if (sources.isEmpty()) toast("内置地址暂不可用，请手动添加片源")
                    else {
                        val added = prefs.addSources(sources)
                        toast("已添加 $added 个兼容片源")
                        showSources()
                    }
                })
            }),
            Triple("扫码添加", "手机扫码后在网页里选文件或粘贴", { showQrAdd() }),
            Triple("粘贴文本", "从电脑复制 sources.json 内容，粘到这里", {
                inputPage("粘贴 sources.json", "把 sources.json 全文粘到这里", multiline = true) { v ->
                    if (v.isEmpty()) toast("内容为空") else importText(v)
                }
            }),
            Triple("从设备文件选择", "读取 U 盘 / 下载目录里的 .json / .m3u", { pickJsonFile() }),
            Triple("手动添加采集站", "只添加一个 CMS 接口地址", { showManualAdd() }),
            Triple("从网址导入", "填 sources.json 或订阅链接", {
                inputPage("从网址导入", "https://example.com/sources.json") { u ->
                    if (u.isEmpty()) {
                        toast("请填地址")
                    } else {
                        runApi("下载中…", { api.fetchText(u) }, { text -> importText(text) })
                    }
                }
            }),
            Triple("服务器配置导入", "从 LibreTV 的 DEFAULT_SOURCES 拉取", { importServerConfig() }),
        )

        // 行模型：String = 分区标题（不可点），Triple = 可点选项。
        val firstOptionIndex = 1
        val rows: List<Any> = listOf(
            "点播源 · 影视采集站（电影 / 剧集）",
        ) + options + listOf(
            "直播源 · IPTV / M3U（已内置 ${BuiltInLiveSources.ALL.size} 个）",
            Triple("管理直播源", "第一个直播源默认启用，也可切换备用源", { showLive() }),
            Triple("添加其他直播源", "输入公开可访问的 M3U / M3U8 地址", { addLiveDialog() }),
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(24), pagePad(48), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText("添加片源", 20f))
        col.addView(hintText("点播源和直播源分开管理"))

        val listView = newList()
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size

            override fun getItem(position: Int): Any = rows[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getViewTypeCount(): Int = 2

            override fun getItemViewType(position: Int): Int =
                if (rows[position] is String) 0 else 1

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = rows[position]
                if (row is String) {
                    return TextView(this@MainActivity).apply {
                        text = row
                        setTextColor(Color.parseColor("#94a3b8"))
                        setTextSize(14f)
                        setTypeface(null, Typeface.BOLD)
                        setPadding(dp(10), dp(16), dp(10), dp(4))
                    }
                }
                val (title, desc, _) = row as Triple<String, String, () -> Unit>
                val box = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(14), dp(10), dp(14))
                }
                box.removeAllViews()
                box.addView(TextView(this@MainActivity).apply {
                    text = if (position == firstOptionIndex) "$title   推荐" else title
                    setTextColor(if (position == firstOptionIndex) ACCENT else TEXT_COLOR)
                    setTextSize(17f)
                })
                box.addView(TextView(this@MainActivity).apply {
                    text = desc
                    setTextColor(Color.parseColor("#64748b"))
                    setTextSize(13f)
                    setPadding(0, dp(3), 0, 0)
                })
                return box
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = rows[position]
            if (row is Triple<*, *, *>) {
                @Suppress("UNCHECKED_CAST")
                (row as Triple<String, String, () -> Unit>).third()
            }
        }
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回片源管理") { showSources() })
        col.addView(hScroll(bar))
        setScreen(col)
    }

    /** 手动添加：两个输入框的应用内页面 */
    private fun showManualAdd() {
        currentScreen = "manualadd"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(24), pagePad(48), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText("手动添加采集站", 20f))
        col.addView(hintText("地址形如 https://xxx/api.php/provide/vod（只支持 Apple CMS JSON 接口）"))
        val nameField = field("名称（可留空，自动取域名）")
        val urlField = field("https://xxx/api.php/provide/vod")
        col.addView(nameField)
        col.addView(urlField)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("保存", primary = true) {
            val url = urlField.text.toString().trim()
            if (url.isEmpty()) {
                toast("地址不能为空")
            } else {
                val name = nameField.text.toString().trim().ifEmpty {
                    url.substringAfter("//").substringBefore('/')
                }
                val added = prefs.addSources(listOf(SourceConfig(name = name, url = url)))
                toast(if (added > 0) "已添加「$name」" else "该地址已存在")
                showSources()
            }
        })
        bar.addView(button("返回") { showAddSource() })
        col.addView(hScroll(bar))
        setScreen(col)
        nameField.requestFocus()
    }


    /* --------------------------------------------------------------- 扫码添加 */

    private var qrServer: QrAddServer? = null

    /** 扫码添加：App 起一个局域网小网页，二维码里就是这个地址，手机扫码后粘贴 sources.json */
    private fun showQrAdd() {
        currentScreen = "qradd"
        qrServer?.stop()
        // 一次性口令：写进二维码，只有扫这个码的手机能提交
        val qrToken = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val server = QrAddServer(this, 8765, qrToken) { text ->
            val list = api.parseSourceList(text)
            if (list.isEmpty()) throw IllegalArgumentException("no sources")
            prefs.addSources(list)
            val live = api.parseLiveFromExport(text)
            if (live.isNotEmpty()) {
                val cur = prefs.liveUrls()
                live.forEach { if (!cur.contains(it)) cur.add(it) }
                prefs.saveLiveUrls(cur)
                if (prefs.activeLiveUrl.isEmpty()) prefs.activeLiveUrl = cur.first()
            }
            list.size
        }
        qrServer = if (server.start()) server else null
        val url = server.url

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(24), pagePad(48), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText("扫码添加片源", 20f))
        col.addView(hintText(
            if (qrServer == null) {
                "无法在 8765 端口启动接收服务（可能被占用）"
            } else {
                "手机连着同一个 WiFi，扫码后把 sources.json 粘贴进网页，点提交即可导入。"
            },
        ))

        if (qrServer != null) {
            val sizePx = dp(260)
            val qr = ImageView(this).apply {
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            val bmp = runCatching {
                val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                    url, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx,
                )
                android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                    for (x in 0 until sizePx) {
                        for (y in 0 until sizePx) {
                            b.setPixel(x, y, if (matrix.get(x, y)) 0xFF04121B.toInt() else 0xFFFFFFFF.toInt())
                        }
                    }
                }
            }.getOrNull()
            if (bmp != null) qr.setImageBitmap(bmp)
            col.addView(qr, LinearLayout.LayoutParams(sizePx + dp(20), sizePx + dp(20)))
            col.addView(TextView(this).apply {
                setText(url)
                setTextColor(BRIGHTER_TEXT)
                setTextSize(16f)
                setPadding(0, dp(14), 0, 0)
            })
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("完成，回片源管理") { qrServer?.stop(); qrServer = null; showSources() })
        bar.addView(button("取消") { qrServer?.stop(); qrServer = null; showSources() })
        col.addView(hScroll(bar))

        setScreen(col)
    }
    private fun sourceJsonMenu() {
        AlertDialog.Builder(this)
            .setTitle("sources.json 从哪里来")
            .setItems(
                arrayOf("粘贴 JSON 文本", "从设备文件选择（U 盘 / 下载目录）", "从网址下载（可用内网地址）"),
            ) { _, which ->
                when (which) {
                    0 -> pasteJsonDialog()
                    1 -> pickJsonFile()
                    2 -> urlImportDialog()
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun dialogWithInput(title: String, hint: String, value: String, multiline: Boolean, onOk: (String) -> Unit) {
        val input = field(hint, value)
        if (multiline) {
            input.setSingleLine(false)
            input.minLines = 6
            input.maxLines = 10
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pasteJsonDialog() {
        dialogWithInput("粘贴 sources.json", "把 sources.json 全文粘到这里", "", true) { v ->
            if (v.isEmpty()) toast("内容为空") else importText(v)
        }
    }

    private fun urlImportDialog() {
        dialogWithInput("从网址导入", "http://<你的NAS>:5000/sources.json", "", false) { u ->
            if (u.isEmpty()) {
                toast("请填地址")
            } else {
                runApi("下载中…", { api.fetchText(u) }, { text -> importText(text) })
            }
        }
    }

    private fun subscriptionDialog() {
        dialogWithInput("从订阅地址导入", "https://…/sources.json（必须公网）", "", false) { u ->
            if (u.isEmpty()) {
                toast("请填地址")
            } else {
                runApi("导入中…", { api.importSourceList(u) }, { list ->
                    if (list.isEmpty()) {
                        toast("没有导入到点播源（订阅里可能只有直播源）")
                    } else {
                        val added = prefs.addSources(list)
                        toast("订阅共 ${list.size} 个，新增 $added")
                        showSources()
                    }
                })
            }
        }
    }

    private fun importServerConfig() {
        runApi("读取服务器配置…", {
            val st = api.status()
            val subs = st.defaultSubscriptions.flatMap { u ->
                runCatching { api.importSourceList(u) }.getOrDefault(emptyList())
            }
            st to subs
        }, { (st, subs) ->
            val all = st.defaultSources + subs
            if (all.isEmpty()) {
                toast("服务器没配 DEFAULT_SOURCES / DEFAULT_SUBSCRIPTIONS")
            } else {
                val added = prefs.addSources(all)
                toast("服务器共 ${all.size} 个，新增 $added")
                showSources()
            }
        })
    }
    private fun showImport() {
        currentScreen = "import"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), dp(32))
        }
        col.addView(topBar())
        col.addView(titleText("片源管理"))
        col.addView(hintText("订阅地址：一份 LibreTV-SourceList JSON 的公开链接（也可以填 TVBOX 配置地址）。"))

        val subField = field("https://…/sources.json")
        col.addView(subField)
        col.addView(button("从文件导入（U 盘 / 下载目录）") { pickJsonFile() })
        col.addView(button("浏览设备文件（无文件选择器时用）") { showFileBrowser() })

        // App 直连，不受服务端防 SSRF 限制 —— 源列表放 NAS 共享或任何内网静态服务都能直接拉
        val lanField = field("http://<你的NAS>:5000/sources.json")
        col.addView(lanField)
        col.addView(button("从该地址导入（App 直连）") {
            val u = lanField.text.toString().trim()
            if (u.isEmpty()) { toast("请填地址"); return@button }
            runApi("下载中…", { api.fetchText(u) }, { text -> importText(text) })
        })

        col.addView(button("从服务器配置导入", primary = true) {
            runApi("读取服务器配置…", {
                val st = api.status()
                val subs = st.defaultSubscriptions.flatMap { u ->
                    runCatching { api.importSourceList(u) }.getOrDefault(emptyList())
                }
                st to subs
            }, { (st, subs) ->
                val all = st.defaultSources + subs
                if (all.isEmpty()) {
                    toast("服务器没配 DEFAULT_SOURCES / DEFAULT_SUBSCRIPTIONS（网页版的片源存在浏览器本地存储里，客户端读不到）")
                } else {
                    val added = prefs.addSources(all)
                    if (prefs.liveUrls().isEmpty() && st.defaultLiveSources.isNotEmpty()) {
                        prefs.saveLiveUrls(st.defaultLiveSources)
                        prefs.activeLiveUrl = st.defaultLiveSources.first()
                    }
                    toast("服务器共 ${all.size} 个片源，新增 $added")
                    showSources()
                }
            })
        })
        col.addView(button("从订阅导入（必须公网地址）") {
            val sub = subField.text.toString().trim()
            if (sub.isEmpty()) { toast("请填订阅地址"); return@button }
            runApi("导入中…", { api.importSourceList(sub) }, { list ->
                if (list.isEmpty()) {
                    toast("没有导入到点播源（订阅里可能只有直播源或被跳过）")
                } else {
                    val added = prefs.addSources(list)
                    toast("导入 ${list.size} 个，新增 $added")
                    showSources()
                }
            })
        })
        col.addView(button("手动添加采集站") { addSourceDialog() })

        // 订阅地址会被服务端做防 SSRF 校验，内网地址一律 403；局域网里的 sources.json
        // 只能靠下面这条「本地解析」路径（不过服务器，直接在这里解析）。
        val jsonField = field("或粘贴 sources.json 全文（本地解析，不进服务器）")
        jsonField.setSingleLine(false)
        jsonField.minLines = 2
        jsonField.maxLines = 4
        col.addView(jsonField)
        col.addView(button("从文本导入（本地解析）") {
            val text = jsonField.text.toString().trim()
            if (text.isEmpty()) { toast("请先粘贴 JSON 内容"); return@button }
            importText(text)
        })
        col.addView(button("删除全部片源") {
            prefs.saveSources(emptyList())
            toast("已清空")
            showSources()
        })
        col.addView(button("返回片源管理") { showSources() })

        val listView = newList()
        val sources = prefs.sources()
        bindList(listView,
            sources.map { s -> "${s.name}\n${s.url}" },
            onItem = { idx ->
                AlertDialog.Builder(this)
                    .setTitle(sources[idx].name)
                    .setItems(arrayOf("删除该片源")) { _, _ ->
                        val left = prefs.sources().filterIndexed { i, _ -> i != idx }
                        prefs.saveSources(left)
                        showSources()
                    }
                    .show()
            },
        )
        // 控件已经多到会超出 1080p 屏幕，所以控件区和片源列表各占一半、各自可滚动
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(
            ScrollView(this).apply { addView(col) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.6f),
        )
        wrapper.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f),
        )
        setScreen(wrapper)
    }

    private fun addSourceDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val nameField = field("名称（可留空）")
        val urlField = field("https://xxx/api.php/provide/vod")
        box.addView(nameField)
        box.addView(urlField)
        AlertDialog.Builder(this)
            .setTitle("添加采集站")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val url = urlField.text.toString().trim()
                if (url.isEmpty()) { toast("地址不能为空"); return@setPositiveButton }
                val added = prefs.addSources(listOf(SourceConfig(
                    name = nameField.text.toString().trim().ifEmpty {
                        url.substringAfter("//").substringBefore('/')
                    },
                    url = url,
                )))
                toast(if (added > 0) "已添加" else "该地址已存在")
                showSources()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ------------------------------------------------- 搜索结果（卡片网格） */

    private var resultGroups: List<Pair<String, List<SearchItem>>> = emptyList()

    /**
     * 搜索结果按网页版的样子展示：封面 + 标题 + 类型/年份/备注 + 来源数。
     * 同一片名收成一张卡片（多来源在详情页「换源」里切）。
     */
    private fun showResults() {
        currentScreen = "results"
        val visible = if (sourceFilter.isEmpty()) {
            lastResults
        } else {
            lastResults.filter { it.sourceName == sourceFilter }
        }
        // 按"第一个源是否已知不可用"排序：点进去默认用的是第一个源，
        // 上次测过失败的就往后排，让能看的先露头
        resultGroups = visible.groupBy { it.name }
            .map { (title, items) -> title to items }
            .sortedBy { (_, items) -> if (prefs.sourceTest(items.first().sourceUrl)?.first == false) 1 else 0 }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(20), pagePad(40), dp(16))
        }
        col.addView(topBar())

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(titleText("${resultGroups.size} 部 · ${visible.size} 条结果", 18f))
        bar.addView(button("来源筛选") {
            val names = listOf("全部来源") + lastResults.map { it.sourceName }.distinct().sorted()
            AlertDialog.Builder(this)
                .setTitle("只看某个采集站的结果")
                .setItems(names.toTypedArray()) { _, which ->
                    sourceFilter = if (which == 0) "" else names[which]
                    showResults()
                }
                .show()
        })
        bar.addView(button("返回推荐") { showRecommend() })
        bar.addView(button("重新搜索") { showHome() })
        col.addView(hScroll(bar))
        if (lastSearchWasPartial && lastSearchKeyword.isNotBlank()) {
            col.addView(button("用全部片源再搜一次") {
                doSearch(lastSearchKeyword, allSources = true)
            })
        }

        if (sourceFilter.isNotEmpty()) {
            col.addView(hintText("当前只看：$sourceFilter（$(${resultGroups.size}) 部）"))
        }

        val grid = newGrid().apply {
            numColumns = if (compact) 1 else 3
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            setPadding(0, dp(8), 0, 0)
        }
        grid.adapter = resultsAdapter()
        grid.setOnItemClickListener { _, _, pos, _ ->
            resultGroups.getOrNull(pos)?.let { openDetail(it.second.first()) }
        }
        // 长按（或按菜单键）挑源：默认点进去的是第一个源，它挂了的话这里能直接改
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val group = resultGroups.getOrNull(pos) ?: return@setOnItemLongClickListener false
            val items = group.second.distinctBy { it.sourceUrl }
            if (items.size <= 1) {
                toast("《${group.first}》只找到一个源")
            } else {
                AlertDialog.Builder(this)
                    .setTitle("《${group.first}》· 选择采集站")
                    .setItems(items.map { it.sourceName.ifEmpty { it.sourceUrl } }.toTypedArray()) { _, w ->
                        openDetail(items[w])
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            true
        }
        col.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
    }

    private fun resultsAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount() = resultGroups.size
        override fun getItem(position: Int): Any = resultGroups[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(
                    ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.parseColor("#1a2233"))
                    },
                    LinearLayout.LayoutParams(dp(86), dp(120)),
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, 0)
                        addView(TextView(this@MainActivity).apply {
                            setTextSize(15f)
                            setTextColor(TEXT_COLOR)
                            maxLines = 2
                        })
                        addView(TextView(this@MainActivity).apply {
                            setTextSize(12f)
                            setTextColor(0xFF94A3B8.toInt())
                            maxLines = 2
                            setPadding(0, dp(4), 0, 0)
                        })
                        addView(TextView(this@MainActivity).apply {
                            setTextSize(11f)
                            setTextColor(0xFF64748B.toInt())
                            maxLines = 2
                            setPadding(0, dp(4), 0, 0)
                        })
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }

            val (title, items) = resultGroups[position]
            val first = items.first()
            val poster = cell.getChildAt(0) as ImageView
            val info = cell.getChildAt(1) as LinearLayout
            (info.getChildAt(0) as TextView).text = title
            (info.getChildAt(1) as TextView).text = listOfNotNull(
                first.typeName.takeIf { it.isNotEmpty() },
                first.year.takeIf { it.isNotEmpty() },
                first.remarks.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            val srcs = items.map { it.sourceName }.distinct()
            val bad = prefs.sourceTest(first.sourceUrl)?.first == false
            (info.getChildAt(2) as TextView).text = buildString {
                if (srcs.size > 1) append("${srcs.size} 个来源 · ${srcs.joinToString(" / ")}")
                else append(srcs.firstOrNull().orEmpty())
                if (bad) {
                    append("   ·⚠ 这个源上次检测失败，长按可换源")
                }
            }
            (info.getChildAt(2) as TextView).setTextColor(
                if (bad) 0xFFFCA5A5.toInt() else 0xFF64748B.toInt(),
            )

            poster.tag = first.pic
            poster.setImageBitmap(null)
            imageLoader.load(first.pic, dp(120), dp(170)) { bmp ->
                if (poster.tag == first.pic) poster.setImageBitmap(bmp)
            }
            cell.setOnClickListener { openDetail(first) }
            return cell
        }
    }

    /* --------------------------------------------------------------- 搜索页 */

    /* --------------------------------------------------------------- 热门搜索 */

    /** 搜索页热门搜索：按类型缓存，取首页排行榜前 10 条 */
    private val hotSearchTitles = mutableMapOf<String, List<String>>()

    private fun ensureHotSearch() {
        if (hotSearchTitles.size >= 3 || hotSearchJob?.isActive == true) return
        hotSearchJob = scope.launch {
            // 热门词只用于提示，不该在用户刚打开搜索页时与手动搜索抢网络。
            delay(1_200)
            if (currentScreen != "home") return@launch
            val specs = listOf("movie" to "热门", "tv" to "热门", "show" to "综艺")
            for ((type, tag) in specs) {
                if (currentScreen != "home") break
                if (hotSearchTitles.containsKey(type)) continue
                val items = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { api.douban(type, tag, 0, 10) }.getOrDefault(emptyList())
                }
                hotSearchTitles[type] = items.map { it.title }.filter { it.isNotBlank() }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (currentScreen == "home") showHome()
            }
        }
    }
    private fun showHome() {
        currentScreen = "home"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(20), pagePad(48), dp(20))
        }

        col.addView(topBar())
        val keywordField = field("输入影片名称…")
        keywordField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        // 输入框 + 搜索按钮固定同一行：输入框吃掉剩余宽度，按钮永远可见。
        // 旧写法是「输入框 580dp + 按钮并排」，在 600dp 左右的窄屏上按钮直接被挤出可视区，
        // 用户只看到输入框、以为没有搜索按钮。
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(
            keywordField,
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(10) },
        )
        inputRow.addView(button("搜索", primary = true) {
            doSearch(keywordField.text.toString())
        })
        col.addView(inputRow)

        // 热门搜索：电影 / 剧集 / 综艺 各取排行榜前 10，点一下直接搜
        ensureHotSearch()
        for ((type, label) in listOf(
            "movie" to "热门电影",
            "tv" to "热门剧集",
            "show" to "热门综艺",
        )) {
            val titles = hotSearchTitles[type].orEmpty()
            if (titles.isEmpty()) continue
            col.addView(hintText(label))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            titles.forEach { title -> row.addView(chip(title) { doSearch(title) }) }
            col.addView(hScroll(row))
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(
            TextView(this).apply {
                setText(
                    "片源 ${prefs.sources().size} 个 · 服务器 ${api.baseUrl}" +
                        if (sourceFilter.isEmpty()) "" else " · 只看：$sourceFilter",
                )
                setTextColor(Color.parseColor("#64748b"))
                setTextSize(13f)
                setPadding(0, dp(6), dp(12), dp(8))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (lastResults.isNotEmpty()) {
            statusRow.addView(
                button(if (sourceFilter.isEmpty()) "来源筛选" else "来源：" + sourceFilter.take(8)) {
                    val names = listOf("全部来源") + lastResults.map { it.sourceName }.distinct().sorted()
                    AlertDialog.Builder(this)
                        .setTitle("只看某个采集站的结果")
                        .setItems(names.toTypedArray()) { _, which ->
                            sourceFilter = if (which == 0) "" else names[which]
                            showHome()
                        }
                        .show()
                },
            )
            // 采集站常年挂一半，搜索结果里混着「点了就失败」的行。
            // 这个开关把「上次检测明确失败」的源整体隐藏，只留能点的。
            val badCount = lastResults.count { prefs.sourceTest(it.sourceUrl)?.first == false }
            statusRow.addView(
                button(if (onlyHealthySource) "已隐藏 ${badCount} 个坏源" else "只看可用源") {
                    onlyHealthySource = !onlyHealthySource
                    showHome()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(46),
                ).apply { leftMargin = dp(8) },
            )
        }
        col.addView(statusRow)

        // 没有结果时把「搜索历史」放在这里：电视遥控器打字太难，
        // 重搜一部之前看过的片子应该是一次点击的事。
        val history = prefs.searchHistory()
        val listView = newList()
        if (lastResults.isEmpty()) {
            if (history.isEmpty()) {
                col.addView(
                    hintText("（还没有搜索记录，输入片名开始）"),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            } else {
                // 逐条可删。以前只有一句「清空搜索历史」，手滑搜错的词会永远占着第一条；
                // 现在每行 = 关键词（OK 重搜）+ 右侧「✕」（OK 删除），左右键即可在两者间移动。
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                history.forEach { kw ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        clipChildren = false
                        clipToPadding = false
                    }
                    row.addView(
                        focusRow("最近搜索 · $kw") {
                            keywordField.setText(kw)
                            doSearch(kw)
                        },
                        LinearLayout.LayoutParams(0, dp(52), 1f),
                    )
                    row.addView(
                        focusRow("✕ 删除", tint = 0xFFFCA5A5.toInt()) {
                            AlertDialog.Builder(this)
                                .setTitle("删除这条搜索记录")
                                .setMessage(kw)
                                .setPositiveButton("删除") { _, _ ->
                                    prefs.removeSearchHistory(kw)
                                    showHome()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        },
                        LinearLayout.LayoutParams(dp(96), dp(52)).apply { leftMargin = dp(6) },
                    )
                    box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
                }
                box.addView(
                    button("清空全部搜索历史") {
                        AlertDialog.Builder(this)
                            .setTitle("清空搜索历史")
                            .setMessage("确定清空这 ${history.size} 条记录？")
                            .setPositiveButton("清空") { _, _ ->
                                prefs.clearSearchHistory()
                                showHome()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    },
                    LinearLayout.LayoutParams(dp(220), dp(52)).apply { topMargin = dp(10) },
                )
                val scroll = ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    addView(box)
                }
                col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        } else {
            // 同一部片在多个采集站都有结果，平铺会刷出一堆重复行（「流浪地球」7 条里 5 条是同一部片）。
            // 按片名收成一行，来源并列显示；点进去后详情页的「换源」可以切换。
            val visible = lastResults
                .filter { sourceFilter.isEmpty() || it.sourceName == sourceFilter }
                // 「只看可用源」：只隐藏「上次检测明确失败」的源，没测过的照常显示（宁多勿少）
                .filter { !onlyHealthySource || prefs.sourceTest(it.sourceUrl)?.first != false }
            val groups = visible.groupBy { it.name }
            val titles = groups.keys.toList()
            val rows = titles.map { title ->
                val group = groups.getValue(title)
                val srcs = group.map { it.sourceName }.distinct()
                val remark = group.firstOrNull { it.remarks.isNotEmpty() }?.remarks.orEmpty()
                buildString {
                    append(title)
                    append("    ")
                    append(srcs.joinToString(" / "))
                    if (remark.isNotEmpty()) {
                        append("  ·  ")
                        append(remark)
                    }
                }
            }
            if (rows.isEmpty()) {
                col.addView(
                    hintText("这个筛选条件下没有结果，换个来源或关掉「只看可用源」试试"),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            } else {
                bindList(listView, rows, onItem = { idx -> openDetail(groups.getValue(titles[idx]).first()) })
                col.addView(listView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }

        keywordField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(keywordField.text.toString())
                true
            } else false
        }

        setScreen(col)
        if (lastResults.isEmpty()) keywordField.requestFocus() else listView.requestFocus()
    }

    private fun doSearch(keyword: String, allSources: Boolean = false) {
        homeMaintenanceJob?.cancel()
        favoriteCheckJob?.cancel()
        hotSearchJob?.cancel()
        val kw = keyword.trim()
        if (kw.isEmpty()) { toast("请输入片名"); return }
        val enabledSources = prefs.sources().filter { it.enabled }
        if (enabledSources.isEmpty()) { toast("没有启用的片源，去「片源管理」开启或添加"); showSources(); return }
        // 先查用户排序靠前的三个源；没有结果时自动扩展到全部源，不牺牲查找完整度。
        val sources = if (allSources || enabledSources.size <= 3) enabledSources else enabledSources.take(3)
        val partial = sources.size < enabledSources.size
        lastSearchKeyword = kw
        lastSearchWasPartial = partial
        sourceFilter = ""
        // 搜一次要服务端把每个采集站都问一遍，十几秒很正常。
        // 刚搜过的关键词（3 分钟内、源的集合没变）直接复用结果 —— 看完一部点返回再搜同一部是常见动作。
        val cacheKey = prefs.serverUrl + "|" + kw + "|" + sources.joinToString(",") { it.url }
        searchCache[cacheKey]?.let { (at, items) ->
            if (System.currentTimeMillis() - at < SEARCH_CACHE_MS) {
                if (items.isEmpty() && partial) {
                    doSearch(kw, allSources = true)
                    return
                }
                prefs.addSearchHistory(kw)
                lastResults = items
                toast("用刚才的结果（${items.size} 个）")
                showResults()
                return
            }
        }
        runApiK("search", "正在搜索「$kw」（${sources.size}/${enabledSources.size} 个源）…", {
            val first = directThenServerSearch(kw, sources)
            // 兜底：豆瓣条目常带「（2023）」「：副标题」这类噪声，采集站匹配不到时再试一次干净片名
            if (first.items.isNotEmpty()) {
                first
            } else {
                val cleaned = cleanKeyword(kw)
                if (cleaned.isNotEmpty() && cleaned != kw) directThenServerSearch(cleaned, sources) else first
            }
        }, { result ->
            prefs.addSearchHistory(kw)
            searchCache[cacheKey] = System.currentTimeMillis() to result.items
            if (searchCache.size > 20) {
                // 只留最近 20 条，按时间清掉最旧的
                searchCache.entries.sortedBy { it.value.first }.take(searchCache.size - 20)
                    .forEach { searchCache.remove(it.key) }
            }
            lastResults = result.items
            val failedNote = if (result.failures > 0) "（${result.failures} 个源无响应）" else ""
            if (result.items.isEmpty()) {
                if (partial) {
                    doSearch(kw, allSources = true)
                    return@runApiK
                }
                toast("没有找到结果$failedNote，换个片名或别的源试试")
                showHome()
            } else {
                toast("找到 ${result.items.size} 个结果$failedNote")
                showResults()
            }
        })
    }

    /**
     * 先直连、再回退服务端。
     *
     * 能直连的源（https + 标准苹果CMS）在手机上并发问，省掉「设备 → Cloudflare → 采集站」
     * 那两跳；失败的源、以及本来就是 http 的源一起交给服务端，保证结果不丢。
     * 全部成功时完全不碰服务端。
     */
    private fun directThenServerSearch(kw: String, sources: List<SourceConfig>): SearchResult {
        val directable = sources.filter { DirectCms.shouldTryDirect(it) }
        if (directable.isEmpty()) return api.search(kw, sources)

        val items = ArrayList<SearchItem>()
        val failed = ArrayList<SourceConfig>()
        val serverOnly = sources.filter { it !in directable }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            directable.size.coerceAtMost(4) + if (serverOnly.isEmpty()) 0 else 1,
        )
        try {
            // HTTP / 非标准 CMS 源必须经服务端；与可直连源同时发起，避免两段网络耗时相加。
            val serverTask = if (serverOnly.isEmpty()) null else pool.submit(
                java.util.concurrent.Callable { api.search(kw, serverOnly) },
            )
            val tasks = directable.map { s ->
                s to pool.submit(java.util.concurrent.Callable { DirectCms.search(s, kw) })
            }
            for ((s, f) in tasks) {
                val got = try {
                    f.get(7, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    f.cancel(true)
                    null
                }
                if (got.isNullOrEmpty()) failed.add(s) else items.addAll(got)
            }
            // 直连失败仍经服务端补查，保持原来的结果完整性。
            val fallbackTask = if (failed.isEmpty()) null else pool.submit(
                java.util.concurrent.Callable { api.search(kw, failed) },
            )
            var failures = 0
            for (task in listOfNotNull(serverTask, fallbackTask)) {
                val result = task.get()
                items.addAll(result.items)
                failures += result.failures
            }
            return SearchResult(items.distinctBy { it.sourceUrl + "|" + it.vodId }, failures)
        } finally {
            pool.shutdownNow()
        }
    }

    /** 详情：能直连就直连，拿不到再回退服务端（行为与服务端 /api/detail 对齐） */
    private fun directDetailOrServer(vodId: String, source: SourceConfig): Detail {
        if (DirectCms.shouldTryDirect(source)) {
            val d = runCatching { DirectCms.detail(source, vodId) }.getOrNull()
            if (d != null && d.episodes.isNotEmpty()) return d
        }
        return api.detail(vodId, source)
    }

    /** 采集站的简介常带 <p>/<br>/&nbsp; 这类标记，直接显示会很脏。 */
    private fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]{0,40}>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** 去掉括号注记、年份与标点，得到更可能被采集站命中的片名。 */
    private fun cleanKeyword(keyword: String): String =
        keyword
            .replace(Regex("[（(\\[【][^）)\\]】]*[）)\\]】]"), "")
            .replace(Regex("[·:：!！?？,，.。\\-—_~、|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /* --------------------------------------------------------------- 详情页 */

    /**
     * 打开详情。
     *
     * 以前源挂了就只弹一个错误框，用户没有任何"换一个源试试"的入口 ——
     * 而搜索结果按片名合并成一张卡片，卡片上只记着第一个源，等于死路。
     * 现在失败时把同片名的其他源列出来让用户点。
     */
    private fun openDetail(item: SearchItem) {
        // 详情页返回列表、再点进同一部剧（或者来回换源比较）是常见动作，
        // 而每开一次都要一次跨境请求（1~2 秒）。5 分钟内的同一部剧直接复用。
        val cacheKey = item.vodId + "|" + item.sourceUrl
        detailCache[cacheKey]?.let { (at, cached) ->
            if (System.currentTimeMillis() - at < DETAIL_CACHE_MS) {
                if (cached.episodes.isEmpty()) {
                    toast("这个源没有可播放地址")
                    offerOtherSources(item, null)
                } else {
                    showDetail(item, cached)
                }
                return
            }
        }
        val source = prefs.sources().firstOrNull { it.url == item.sourceUrl }
            ?: SourceConfig(item.sourceName, item.sourceUrl)
        runApiK("detail", "正在获取详情…", { directDetailOrServer(item.vodId, source) }, { detail ->
            if (detail.episodes.isEmpty()) {
                toast("这个源没有可播放地址")
                offerOtherSources(item, null)
                return@runApiK
            }
            detailCache[cacheKey] = System.currentTimeMillis() to detail
            if (detailCache.size > 30) {
                detailCache.entries.sortedBy { it.value.first }.take(detailCache.size - 30)
                    .forEach { detailCache.remove(it.key) }
            }
            showDetail(item, detail)
        }, quiet = true, onError = { e -> offerOtherSources(item, e) })
    }

    /** 同片名的其他采集站：详情打不开时给用户第二条路 */
    private fun offerOtherSources(item: SearchItem, e: Exception?) {
        val alts = lastResults
            .filter { it.name.trim() == item.name.trim() && it.sourceUrl != item.sourceUrl }
            .distinctBy { it.sourceUrl }
        if (alts.isEmpty()) {
            toast(if (e == null) "这个源没有可播放地址，也没有其他源" else "打不开：${describe(e)}，且没有其他源")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("这个源打不开")
            .setMessage(
                (if (e != null) describe(e) + "\n\n" else "") +
                    "《${item.name}》在另外 ${alts.size} 个采集站也有，换一个试试：",
            )
            .setItems(alts.map { it.sourceName.ifEmpty { it.sourceUrl } }.toTypedArray()) { _, which ->
                openDetail(alts[which])
            }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun showDetail(item: SearchItem, detail: Detail) {
        currentScreen = "detail"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(56), dp(20), pagePad(56), dp(20))
        }
        // 详情页以前只有 BACK 键能回去，遥控器用户进来就「出不去」了
        col.addView(topBar())
        // 手机只在封面旁放标题和摘要；长文本放到下方，避免把选集列表挤出屏幕。
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1a2233"))
        }
        header.addView(cover, LinearLayout.LayoutParams(dp(if (compact) 108 else 150), dp(if (compact) 158 else 220)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compact) 14 else 24), 0, 0, 0)
        }
        info.addView(titleText(detail.info.title.ifEmpty { item.name }, if (compact) 21f else 24f).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(hintText(listOfNotNull(
            detail.info.year.takeIf { it.isNotEmpty() },
            detail.info.area.takeIf { it.isNotEmpty() },
            detail.info.remarks.takeIf { it.isNotEmpty() },
            item.sourceName,
            "共 ${detail.episodes.size} 集",
        ).joinToString(" · ")).apply {
            maxLines = if (compact) 3 else 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        fun addPreview(text: String, heading: String, lines: Int, target: LinearLayout) {
            target.addView(hintText(text).apply {
                maxLines = lines
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(heading)
                        .setMessage(text)
                        .setPositiveButton("关闭", null)
                        .show()
                }
            })
        }
        val extraInfo = if (compact) col else info
        if (detail.info.actor.isNotEmpty()) {
            // 采集站有时返回数百名演员，只显示两行；点开仍能看完整名单。
            if (!compact) addPreview("主演：${detail.info.actor}", "主演", 3, info)
        }
        if (detail.info.desc.isNotEmpty()) {
            if (!compact) addPreview(stripHtml(detail.info.desc), "简介", 5, info)
        }
        header.addView(info, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(header)
        if (compact) {
            if (detail.info.actor.isNotEmpty()) addPreview("主演：${detail.info.actor}", "主演", 2, extraInfo)
            if (detail.info.desc.isNotEmpty()) addPreview(stripHtml(detail.info.desc), "简介", 3, extraInfo)
        }

        val coverUrl = detail.info.cover.ifEmpty { item.pic }
        if (coverUrl.isNotEmpty()) {
            imageLoader.load(coverUrl, dp(300), dp(440)) { bmp -> bmp?.let { cover.setImageBitmap(it) } }
        }

        col.addView(hintText("选择集数（共 ${detail.episodes.size} 集）："))

        // 换源：同一个片名在其他采集站的结果，一键切换（按来源名去重，避免同一站出现两次）
        // 以前这里 take(6) 只显示前 6 个来源，7 个以上就再也切不过去了，现在全列出来（外面有横滑容器）
        val alternatives = lastResults
            .filter { it.name == item.name }
            .distinctBy { it.sourceUrl }
        if (alternatives.size > 1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(hintText("来源（${alternatives.size}）："), LinearLayout.LayoutParams(dp(96), dp(44)))
            alternatives.forEach { alt ->
                row.addView(
                    button((if (alt.sourceUrl == item.sourceUrl) "● " else "") + alt.sourceName.ifEmpty { "来源" }) { if (alt.sourceUrl != item.sourceUrl) openDetail(alt) },
                )
            }
            col.addView(hScroll(row))
        }

        fun startEpisode(index: Int) {
            playFromList(
                detail.episodes,
                index,
                detail.info.title.ifEmpty { item.name },
                detail.info.cover.ifEmpty { item.pic },
                seriesName = detail.info.title.ifEmpty { item.name },
                vodId = item.vodId,
                sourceUrl = item.sourceUrl,
                sourceName = item.sourceName,
            )
        }

        // 续播：全项目以前从来没读过 progressOf()，进详情页永远从第 1 集开始，
        // 1200 集的剧每次都要手动翻回去。这里一次性把「看过哪几集」算出来。
        val marked = prefs.resumeThresholdSeconds * 1000L
        // 两份记录都看：按地址的 + 按「剧名+集号」的（换源之后地址变了，后者才认得出来）
        val progressKey = detail.info.title.ifEmpty { item.name }
        val watched = prefs.watchedEpisodes(progressKey, detail.episodes, marked)
        val lastIdx = if (prefs.resumeEnabled) watched.indexOfLast { it } else -1
        if (lastIdx >= 0) {
            col.addView(
                button("▶ 继续看第 ${lastIdx + 1} 集", primary = true) { startEpisode(lastIdx) },
                LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8); bottomMargin = dp(8) },
            )
        }
        // 看剧最容易问的两件事：我上次看到哪了、这部还剩多少
        val watchedCount = watched.count { it }
        if (watchedCount > 0) {
            col.addView(hintText("已看 $watchedCount / ${detail.episodes.size} 集"))
        }

        // 追剧：只记「加入时这部剧共几集」，之后集数变多就说明更新了（纯本地比对）
        val faved = prefs.isFavorite(progressKey)
        col.addView(
            button(if (faved) "✓ 已追剧" else "＋ 加入追剧") {
                if (prefs.isFavorite(progressKey)) {
                    prefs.removeFavorite(progressKey)
                    toast("已取消追剧")
                } else {
                    prefs.addFavorite(
                        Favorite(
                            title = progressKey,
                            cover = detail.info.cover.ifEmpty { item.pic },
                            sourceName = item.sourceName,
                            vodId = item.vodId,
                            sourceUrl = item.sourceUrl,
                            episodeCount = detail.episodes.size,
                            checkedAt = System.currentTimeMillis(),
                        ),
                    )
                    toast("已加入追剧，有新集会提示")
                }
                showDetail(item, detail)
            },
            LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) },
        )

        val epGrid = newGrid().apply {
            numColumns = if (compact) 3 else 8
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
        }
        epGrid.adapter = object : BaseAdapter() {
            override fun getCount() = detail.episodes.size
            override fun getItem(position: Int): Any = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    gravity = Gravity.CENTER
                    setTextSize(14f)
                    setTextColor(TEXT_COLOR)
                    setPadding(dp(6), dp(14), dp(6), dp(14))
                }
                // CMS 的集数标题常常带一段哈希（第1集$4ad62e），这里只保留序号
                tv.text = when {
                    position == lastIdx -> "第 ${position + 1} 集 ▶"
                    watched[position] -> "第 ${position + 1} 集 ✓"
                    else -> "第 ${position + 1} 集"
                }
                tv.setTextColor(
                    when {
                        position == lastIdx -> 0xFFD9F5A9.toInt()
                        watched[position] -> 0xFF8FD3A8.toInt()
                        else -> TEXT_COLOR
                    },
                )
                return tv
            }
        }
        epGrid.setOnItemClickListener { _, _, position, _ -> startEpisode(position) }
        col.addView(epGrid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
        // 直接定位到上次看到的那一集附近，不用手动滑到第 800 格
        if (lastIdx > 0) {
            val target = (lastIdx - (if (compact) 3 else 8)).coerceAtLeast(0)
            epGrid.post { epGrid.setSelection(target) }
        }
    }

    /** 从 m3u8 路径里取一个可读标签，例如 .../HD国语版/index.m3u8 → HD国语版 */
    private fun labelOf(url: String): String {
        val parts = url.substringBefore('?').split('/')
        return when {
            parts.size >= 2 -> parts[parts.size - 2]
            else -> ""
        }
    }

    /** 单集播放（直播频道用这个） */
    private fun play(url: String, title: String) = playFromList(listOf(url), 0, title)

    /**
     * 从观看历史继续看。
     *
     * 以前的坑：历史里只存了**当前这一集**的地址，播放器拿到的就是 1 个 url，
     * 点「选集」直接提示"当前片源只有一集" —— 明明这是 1180 集的剧。
     * 现在把剧名 / 采集站 / vodId / 集号一起交给播放器，它自己就能回查整季列表
     * （老记录缺这些字段时，用标题里" · 第 x/y 集"前面的片名去搜）。
     */
    private fun playFromHistory(h: HistoryEntry) {
        val series = h.seriesTitle.ifEmpty { seriesNameOf(h.title) }
        playFromList(
            listOf(h.playUrl),
            0,
            h.title,
            h.cover,
            seriesName = series,
            vodId = h.vodId,
            sourceUrl = h.sourceUrl,
            sourceName = h.sourceName,
        )
    }

    /** "海贼王 · 第 1135/1180 集" → "海贼王" */
    private fun seriesNameOf(label: String): String =
        label.substringBefore(" · 第").substringBefore("·第").trim()

    /** 整季播放：把整个集数列表交给播放器，一集放完自动进下一集 */
    private fun playFromList(
        urls: List<String>,
        index: Int,
        title: String,
        cover: String = "",
        seriesName: String = "",
        vodId: String = "",
        sourceUrl: String = "",
        sourceName: String = "",
    ) {
        if (urls.isEmpty()) return
        refreshHomeAfterPlayback = true
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(urls))
            putExtra(PlayerActivity.EXTRA_INDEX, index.coerceIn(0, urls.size - 1))
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_COVER, cover)
            // 选集回查用：只有这几项齐了，播放器才能从 1 集恢复成整季
            putExtra(PlayerActivity.EXTRA_SERIES_NAME, seriesName)
            putExtra(PlayerActivity.EXTRA_VOD_ID, vodId)
            putExtra(PlayerActivity.EXTRA_SOURCE_URL, sourceUrl)
            putExtra(PlayerActivity.EXTRA_SOURCE_NAME, sourceName)
        })
    }

    /* ------------------------------------------------------------ 简易适配器 */

    /* --------------------------------------------------------------- 直播页 */

    private fun showLive() {
        ensureBuiltInLiveSources()
        currentScreen = "live"
        val urls = prefs.liveUrls()
        if (urls.isEmpty()) {
            addLiveDialog()
            return
        }
        val active = prefs.activeLiveUrl.ifEmpty { urls.first() }
        if (liveChannelSource != active || liveChannels.isEmpty()) {
            runApiK("live", "正在加载频道…", { api.liveChannels(active) }, { list ->
                liveChannels = list
                liveChannelSource = active
                if (list.isEmpty()) toast("没解析到频道，换个直播源试试") else showLive()
            })
            return
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(24), pagePad(48), dp(20))
        }
        col.addView(topBar())
        val shown = if (liveGroupFilter.isEmpty()) {
            liveChannels
        } else {
            liveChannels.filter { it.group == liveGroupFilter }
        }
        col.addView(titleText("直播  ·  ${shown.size}/${liveChannels.size} 个频道", 20f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("换源") { pickLiveSource() })
        bar.addView(button("添加直播源") { addLiveDialog() })
        bar.addView(
            button(if (liveGroupFilter.isEmpty()) "分组" else "分组：" + liveGroupFilter.take(10)) {
                val groups = listOf("全部分组") + liveChannels.map { it.group }.distinct().sorted()
                AlertDialog.Builder(this)
                    .setTitle("按分组筛选")
                    .setItems(groups.toTypedArray()) { _, which ->
                        liveGroupFilter = if (which == 0) "" else groups[which]
                        showLive()
                    }
                    .show()
            },
        )
        bar.addView(button("返回分类") { showSources() })
        col.addView(hScroll(bar))

        val sorted = shown.sortedWith(compareBy({ it.group }, { it.name }))
        val listView = newList()
        bindList(listView,
            sorted.map { "${it.group}  ·  ${it.name}" },
            onItem = { idx -> play(sorted[idx].url, sorted[idx].name) },
        )
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
        listView.requestFocus()
    }

    private fun pickLiveSource() {
        val urls = prefs.liveUrls()
        if (urls.isEmpty()) {
            addLiveDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择直播源")
            .setItems(urls.map(BuiltInLiveSources::label).toTypedArray()) { _, which ->
                prefs.activeLiveUrl = urls[which]
                liveChannels = emptyList()
                liveChannelSource = ""
                showLive()
            }
            .setPositiveButton("添加新的", { _, _ -> addLiveDialog() })
            .show()
    }

    private fun addLiveDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(hintText("填 M3U 播放列表地址。服务端会去抓这个地址，所以必须是公网可访问的（内网地址会被拒）。"))
        val urlField = field("https://iptv-org.github.io/iptv/countries/cn.m3u")
        box.addView(urlField)
        AlertDialog.Builder(this)
            .setTitle("添加直播源")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val u = urlField.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                val list = prefs.liveUrls()
                if (!list.contains(u)) list.add(u)
                prefs.saveLiveUrls(list)
                prefs.activeLiveUrl = u
                liveChannels = emptyList()
                liveChannelSource = ""
                showLive()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ------------------------------------------------------------ 继续观看 */

    private fun showHistory() {
        currentScreen = "history"
        val items = prefs.history()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(24), pagePad(48), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText("继续观看  ·  ${items.size} 条", 20f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回") { showRecommend() })
        bar.addView(
            button("清空历史") {
                prefs.clearHistory()
                toast("已清空观看历史")
                showHistory()
            },
        )
        col.addView(hScroll(bar))

        if (items.isEmpty()) {
            col.addView(hintText("还没有观看记录。播过的片子会出现在这里，方便接着看。"))
        } else {
            col.addView(hintText("选中即从上次位置继续播放；长按（或按菜单键）可删除单条。"))
            val listView = newList()
            bindList(listView,
                items.map { "${it.title}    ${formatTime(it.positionMs)} / ${formatTime(it.durationMs)}" },
                onItem = { idx ->
                    val h = items[idx]
                    playFromHistory(h)
                },
            )
            listView.setOnItemLongClickListener { _, _, pos, _ ->
                prefs.removeHistory(items[pos].playUrl)
                toast("已删除该条")
                showHistory()
                true
            }
            col.addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setScreen(col)
    }

    /** 继续观看：卡片形式（封面 + 片名 + 进度） */
    private fun showHistoryCards() {
        // 历史里缺封面的记录：用片名反查一次，拿到海报就写回（最多补 5 条）
        prefs.history().filter { it.cover.isEmpty() }.take(5).forEach { h ->
            scope.launch {
                val items = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        api.search(h.title.substringBefore(" · 第").trim(),
                            prefs.sources().filter { it.enabled }).items
                    }.getOrDefault(emptyList())
                }
                val cover = items.firstOrNull { it.name.trim() == historyTitle(h) }?.pic
                    ?: items.firstOrNull()?.pic.orEmpty()
                if (cover.isNotEmpty()) {
                    prefs.updateHistoryCover(h.playUrl, cover)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (currentScreen == "history") showHistoryCards()
                    }
                }
            }
        }
        currentScreen = "history"
        val items2 = prefs.history()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(20), pagePad(40), dp(16))
        }
        col.addView(topBar())
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(titleText("继续观看 · ${items2.size} 条", 18f))
        bar.addView(button("返回推荐") { showRecommend() })
        if (items2.isNotEmpty()) {
            bar.addView(button("清空历史") { prefs.clearHistory(); toast("已清空"); showHistoryCards() })
        }
        col.addView(hScroll(bar))

        if (items2.isEmpty()) {
            col.addView(hintText("还没有观看记录。播过的片子会出现在这里，方便接着看。"))
        } else {
            col.addView(hintText("选中即从上次位置继续播放"))
            val grid = newGrid().apply {
                numColumns = if (compact) 1 else 3
                horizontalSpacing = dp(12)
                verticalSpacing = dp(12)
            }
            grid.adapter = object : BaseAdapter() {
                override fun getCount() = items2.size
                override fun getItem(position: Int): Any = items2[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        addView(
                            ImageView(this@MainActivity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setBackgroundColor(Color.parseColor("#1a2233"))
                            },
                            LinearLayout.LayoutParams(dp(86), dp(120)),
                        )
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(12), 0, 0, 0)
                                addView(TextView(this@MainActivity).apply {
                                    setTextSize(15f); setTextColor(TEXT_COLOR); maxLines = 2
                                })
                                addView(TextView(this@MainActivity).apply {
                                    setTextSize(12f); setTextColor(0xFF94A3B8.toInt())
                                    maxLines = 1; setPadding(0, dp(4), 0, 0)
                                })
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                    val h = items2[position]
                    val poster = cell.getChildAt(0) as ImageView
                    val info = cell.getChildAt(1) as LinearLayout
                    (info.getChildAt(0) as TextView).text = h.title
                    (info.getChildAt(1) as TextView).text =
                        formatTime(h.positionMs) + " / " + formatTime(h.durationMs)
                    poster.tag = h.cover
                    poster.setImageBitmap(null)
                    imageLoader.load(h.cover, dp(120), dp(170)) { bmp ->
                        if (poster.tag == h.cover) poster.setImageBitmap(bmp)
                    }
                    cell.setOnClickListener { playFromHistory(h) }
                    return cell
                }
            }
            grid.setOnItemClickListener { _, _, pos, _ ->
                items2.getOrNull(pos)?.let { play(it.playUrl, it.title) }
            }
            grid.setOnItemLongClickListener { _, _, pos, _ ->
                prefs.removeHistory(items2[pos].playUrl)
                toast("已删除该条")
                showHistoryCards()
                true
            }
            col.addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setScreen(col)
        col.post { col.findFocus() ?: col.requestFocus() }
    }
    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /* ------------------------------------------------------------ 追剧 */

    private fun relativeTime(ms: Long): String {
        if (ms <= 0L) return "还没检查过"
        val d = System.currentTimeMillis() - ms
        return when {
            d < 60_000L -> "刚刚检查"
            d < 3_600_000L -> "${d / 60_000L} 分钟前检查"
            d < 86_400_000L -> "${d / 3_600_000L} 小时前检查"
            else -> "${d / 86_400_000L} 天前检查"
        }
    }

    /** 追剧列表：只看「这部剧更到第几集了」，点进去还是详情页 */
    private fun showFavorites() {
        currentScreen = "favorites"
        val list = prefs.favorites()
        val updates = list.count { it.newEpisodes > 0 }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(20), pagePad(40), dp(16))
        }
        col.addView(topBar())
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(
            titleText(
                if (updates > 0) "追剧 · ${list.size} 部（$updates 部有新集）" else "追剧 · ${list.size} 部",
                18f,
            ),
        )
        bar.addView(button("返回推荐") { showRecommend() })
        if (list.isNotEmpty()) {
            bar.addView(button("检查更新") { checkFavoritesUpdate(force = true) })
        }
        col.addView(hScroll(bar))

        if (list.isEmpty()) {
            col.addView(hintText("还没有追的剧。进详情页点「加入追剧」，之后服务端更新了集数，这里会自动告诉你。"))
        } else {
            col.addView(hintText("选中进入详情；长按可取消追剧"))
            val grid = newGrid().apply {
                numColumns = if (compact) 1 else 3
                horizontalSpacing = dp(12)
                verticalSpacing = dp(12)
            }
            grid.adapter = object : BaseAdapter() {
                override fun getCount() = list.size
                override fun getItem(position: Int): Any = list[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        addView(
                            ImageView(this@MainActivity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setBackgroundColor(Color.parseColor("#1a2233"))
                            },
                            LinearLayout.LayoutParams(dp(86), dp(120)),
                        )
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(12), 0, 0, 0)
                                addView(TextView(this@MainActivity).apply {
                                    setTextSize(15f); setTextColor(TEXT_COLOR); maxLines = 2
                                })
                                addView(TextView(this@MainActivity).apply {
                                    setTextSize(12f); maxLines = 1
                                    setPadding(0, dp(4), 0, 0)
                                })
                                addView(TextView(this@MainActivity).apply {
                                    setTextSize(11f)
                                    setTextColor(0xFF64748B.toInt())
                                    maxLines = 1; setPadding(0, dp(2), 0, 0)
                                })
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                    val f = list[position]
                    val poster = cell.getChildAt(0) as ImageView
                    val info = cell.getChildAt(1) as LinearLayout
                    (info.getChildAt(0) as TextView).text = f.title
                    val status = info.getChildAt(1) as TextView
                    status.text = when {
                        f.newEpisodes > 0 -> "更新至 ${f.episodeCount} 集 · 新增 ${f.newEpisodes} 集"
                        f.episodeCount > 0 -> "共 ${f.episodeCount} 集 · 暂无更新"
                        else -> "集数未知"
                    }
                    // 有新集用告警色，其余保持灰色：一眼能从列表里挑出该看哪部
                    status.setTextColor(
                        if (f.newEpisodes > 0) 0xFFEF9F27.toInt() else 0xFF94A3B8.toInt(),
                    )
                    (info.getChildAt(2) as TextView).text =
                        f.sourceName.ifEmpty { "未知来源" } + " · " + relativeTime(f.checkedAt)
                    poster.tag = f.cover
                    poster.setImageBitmap(null)
                    imageLoader.load(f.cover, dp(120), dp(170)) { bmp ->
                        if (poster.tag == f.cover) poster.setImageBitmap(bmp)
                    }
                    return cell
                }
            }
            grid.setOnItemClickListener { _, _, pos, _ ->
                val f = list.getOrNull(pos) ?: return@setOnItemClickListener
                openDetail(
                    SearchItem(
                        sourceName = f.sourceName,
                        vodId = f.vodId,
                        name = f.title,
                        pic = f.cover,
                        typeName = "",
                        year = "",
                        remarks = "",
                        sourceUrl = f.sourceUrl,
                    ),
                )
            }
            grid.setOnItemLongClickListener { _, _, pos, _ ->
                val f = list.getOrNull(pos) ?: return@setOnItemLongClickListener true
                AlertDialog.Builder(this)
                    .setTitle("取消追剧？")
                    .setMessage("《${f.title}》将从追剧列表移除，观看记录不受影响。")
                    .setPositiveButton("取消追剧") {
                            _, _ ->
                        prefs.removeFavorite(f.title)
                        toast("已取消追剧")
                        showFavorites()
                    }
                    .setNegativeButton("算了", null)
                    .show()
                true
            }
            col.addView(
                grid,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setScreen(col)
        col.post { col.findFocus() ?: col.requestFocus() }
    }

    /**
     * 检查追剧更新。
     *
     * 纯本地比对：把当前集数和追剧时记下的 [Favorite.episodeCount] 比一下，
     * 变多就算更新。6 小时内查过的跳过（[force] = 手动点「检查更新」时无视）。
     */
    private fun checkFavoritesUpdate(force: Boolean = false) {
        if (favoriteCheckJob?.isActive == true) {
            if (force) toast("追剧更新检查正在进行")
            return
        }
        val list = prefs.favorites()
        if (list.isEmpty()) {
            if (force) toast("还没有追的剧")
            return
        }
        val now = System.currentTimeMillis()
        val due = list.filter { force || now - it.checkedAt > FAVORITE_CHECK_INTERVAL_MS }
        if (due.isEmpty()) {
            if (force) toast("刚检查过，${FAVORITE_CHECK_INTERVAL_MS / 3_600_000L} 小时内不再重复")
            return
        }
        if (force) toast("正在检查 ${due.size} 部…")
        favoriteCheckJob = scope.launch {
            // 一次只查一部：避免首页刷新时同时向多个片源发请求，抢占用户的搜索。
            for (f in due) {
                if (currentScreen != "recommend" && currentScreen != "favorites") break
                val count = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val sources = prefs.sources().filter { it.enabled }
                        if (sources.isEmpty()) return@runCatching 0
                        val src = sources.firstOrNull { it.url == f.sourceUrl } ?: sources.first()
                        var detail = runCatching { api.detail(f.vodId, src) }.getOrNull()
                        // 原条目可能下架了：按片名重搜一次再取详情
                        if (detail == null || detail.episodes.isEmpty()) {
                            val hit = runCatching {
                                api.search(f.title, sources).items
                                    .firstOrNull { it.name.trim() == f.title }
                            }.getOrNull()
                            if (hit != null) {
                                val src2 = sources.firstOrNull { it.url == hit.sourceUrl } ?: src
                                detail = runCatching { api.detail(hit.vodId, src2) }.getOrNull()
                            }
                        }
                        detail?.episodes?.size ?: 0
                    }.getOrDefault(0)
                }
                if (count > 0) {
                    prefs.markFavoriteChecked(f.title, count)
                    if (currentScreen == "favorites") showFavorites()
                }
                delay(250)
            }
            lastFavoriteCheckAt = System.currentTimeMillis()
        }
    }

    /* ------------------------------------------------------------ 推荐首页 */

    private fun historyTitle(h: HistoryEntry): String = h.title.substringBefore(" · 第").trim()

    /** 删一条观看记录（只删记录，不动收藏/片源） */
    private fun confirmRemoveHistory(h: HistoryEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除这条观看记录？")
            .setMessage(historyTitle(h) + "  ·  " + h.title.substringAfter(" · ", ""))
            .setPositiveButton("删除") { _, _ ->
                prefs.removeHistory(h.playUrl)
                toast("已删除")
                showRecommend()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 进首页时顺手跑一次追剧检查：静默，失败就当没发生，不弹任何东西 */
    private fun autoCheckFavorites() {
        if (prefs.favorites().isEmpty()) return
        if (System.currentTimeMillis() - lastFavoriteCheckAt < 3_600_000L) return
        checkFavoritesUpdate(force = false)
    }

    /** 首屏稳定后再做低优先级维护；重绘首页或离开页面时取消排队任务。 */
    private fun scheduleHomeMaintenance() {
        homeMaintenanceJob?.cancel()
        homeMaintenanceJob = scope.launch {
            delay(2_000)
            var waits = 0
            while ((isLoadingMore || jobOf("status")?.isActive == true) && waits < 20) {
                delay(500)
                waits++
            }
            if (isLoadingMore || jobOf("status")?.isActive == true) return@launch
            if (currentScreen != "recommend") return@launch
            fillMissingHistoryCovers()
            if (currentScreen == "recommend") autoCheckFavorites()
        }
    }

    /** 旧版记录可能没存封面；每次最多三条，逐条补查，不与用户操作并发抢带宽。 */
    private suspend fun fillMissingHistoryCovers() {
        val sources = prefs.sources().filter { it.enabled }
        for (entry in prefs.history().filter { it.cover.isBlank() }.take(3)) {
            if (currentScreen != "recommend") break
            val title = historyTitle(entry)
            if (title.isEmpty() || entry.playUrl in coverLookupAttempted) continue
            val localCover = recommendItems.firstOrNull { it.title.trim() == title }?.cover.orEmpty()
            if (localCover.isNotEmpty()) {
                coverLookupAttempted.add(entry.playUrl)
                prefs.updateHistoryCover(entry.playUrl, localCover)
                updateHomeHistoryCover(entry.playUrl, localCover)
                continue
            }
            if (sources.isEmpty()) continue
            val cover = withContext(Dispatchers.IO) {
                runCatching {
                    val found = api.search(title, sources).items
                    found.firstOrNull { it.name.trim() == title }?.pic
                        ?: found.firstOrNull()?.pic.orEmpty()
                }.getOrDefault("")
            }
            coverLookupAttempted.add(entry.playUrl)
            if (cover.isNotEmpty()) {
                prefs.updateHistoryCover(entry.playUrl, cover)
                if (currentScreen == "recommend") updateHomeHistoryCover(entry.playUrl, cover)
            }
            delay(250)
        }
    }

    /** 只替换对应卡片的海报，不为一张图片重建整页。 */
    private fun updateHomeHistoryCover(playUrl: String, cover: String) {
        val (view, width, height) = homeHistoryCoverViews[playUrl] ?: return
        view.tag = cover
        imageLoader.load(cover, width, height) { bmp ->
            if (view.tag == cover && bmp != null) view.setImageBitmap(bmp)
        }
    }

    /** 方案 B 的续看主卡：海报完整保留纵向比例，右侧集中呈现进度与操作。 */
    private fun addContinueWatchingB(col: LinearLayout) {
        val h = prefs.history().firstOrNull() ?: return
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(8))
        }
        head.addView(titleText("最近观看", if (isTvDevice) 25f else 21f).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(button("全部记录") { showHistoryCards() })
        col.addView(head)

        val height = dp(if (isTvDevice) 210 else 176)
        val coverWidth = dp(if (isTvDevice) 145 else 118)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), BORDER)
            }
            clipToOutline = true
            elevation = dp(3).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { playFromHistory(h) }
            setOnLongClickListener { confirmRemoveHistory(h); true }
            setOnFocusChangeListener { v, focused ->
                v.background = GradientDrawable().apply {
                    setColor(PANEL)
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(if (focused) 3 else 1), if (focused) ACCENT else BORDER)
                }
            }
        }
        val poster = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xFFD7E9DF.toInt()) }
        }
        poster.addView(TextView(this).apply {
            text = historyTitle(h).take(2)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextSize(26f)
            setTextColor(ACCENT)
        }, FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        poster.addView(image, FrameLayout.LayoutParams(-1, -1))
        homeHistoryCoverViews[h.playUrl] = Triple(image, coverWidth, height)
        if (h.cover.isNotBlank()) updateHomeHistoryCover(h.playUrl, h.cover)
        card.addView(poster, LinearLayout.LayoutParams(coverWidth, height))

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (isTvDevice) 26 else 16), dp(12), dp(16), dp(12))
        }
        detail.addView(TextView(this).apply {
            text = "继续观看"
            setTextColor(ACCENT)
            setTextSize(12f)
            setTypeface(null, Typeface.BOLD)
        })
        detail.addView(TextView(this).apply {
            text = historyTitle(h)
            setTextColor(INK)
            setTextSize(if (isTvDevice) 24f else 19f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            setPadding(0, dp(5), 0, 0)
        })
        detail.addView(TextView(this).apply {
            text = h.title.substringAfter(" · ", "继续播放") +
                if (h.positionMs > 0) " · ${formatTime(h.positionMs)}" else ""
            setTextColor(BRIGHTER_TEXT)
            setTextSize(if (isTvDevice) 15f else 12f)
            maxLines = 1
            setPadding(0, dp(7), 0, dp(9))
        })
        val fraction = if (h.durationMs > 0) {
            (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f)
        } else 0f
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFFDDE7E2.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        track.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(3).toFloat()
            }
        }, FrameLayout.LayoutParams(0, dp(4)))
        track.post {
            track.getChildAt(0).layoutParams =
                FrameLayout.LayoutParams((track.width * fraction).toInt(), dp(4))
        }
        detail.addView(track, LinearLayout.LayoutParams(-1, dp(4)))
        detail.addView(TextView(this).apply {
            text = "▶  继续播放"
            setTextColor(ACCENT)
            setTextSize(if (isTvDevice) 15f else 13f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, 0)
        })
        card.addView(detail, LinearLayout.LayoutParams(0, -1, 1f))
        col.addView(card, LinearLayout.LayoutParams(-1, height))
        homeHistoryFirst = card
    }

    private fun addContinueWatching(col: LinearLayout) {
        val recent = prefs.history().take(5)
        if (recent.isEmpty()) return
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(16), 0, dp(12))
        }
        headRow.addView(
            titleText("最近观看", if (isTvDevice) 25f else 21f).apply {
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        headRow.addView(TextView(this).apply {
            text = "长按卡片可删除记录"
            setTextColor(Color.parseColor("#64748b"))
            setTextSize(12f)
        })
        col.addView(headRow)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recent.forEach { h ->
            val cardWidth = dp(if (isTvDevice) 180 else 142)
            val cardHeight = dp(if (isTvDevice) 240 else 206)
            val card = FrameLayout(this).apply {
                isFocusable = true
                isClickable = true
                isLongClickable = true
                setOnClickListener { playFromHistory(h) }
                // 单条删除：以前只能去「继续」页整批清空，看完的/点错的记录会一直挂在首页
                setOnLongClickListener {
                    confirmRemoveHistory(h)
                    true
                }
                setOnKeyListener { _, code, event ->
                    when {
                        code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN ->
                            homeGrid?.requestFocus() == true
                        // 遥控器长按 OK 不好按，菜单键也能删
                        code == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN -> {
                            confirmRemoveHistory(h)
                            true
                        }
                        else -> false
                    }
                }
                background = GradientDrawable().apply {
                    setColor(0xFF141A26.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                clipToOutline = true
                elevation = dp(5).toFloat()
            }
            card.setOnFocusChangeListener { view, focused ->
                view.scaleX = if (focused) 1.04f else 1f
                view.scaleY = if (focused) 1.04f else 1f
                view.background = GradientDrawable().apply {
                    setColor(0xFF141A26.toInt())
                    cornerRadius = dp(12).toFloat()
                    if (focused) setStroke(dp(3), Color.WHITE)
                }
            }
            card.addView(TextView(this).apply {
                text = historyTitle(h).take(2)
                gravity = Gravity.CENTER
                setTextColor(0xFFD7F2C8.toInt())
                setTextSize(32f)
                setTypeface(null, Typeface.BOLD)
            }, FrameLayout.LayoutParams(-1, -1))
            val coverView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            card.addView(coverView, FrameLayout.LayoutParams(-1, -1))
            homeHistoryCoverViews[h.playUrl] = Triple(coverView, cardWidth, cardHeight)
            if (h.cover.isNotBlank()) {
                updateHomeHistoryCover(h.playUrl, h.cover)
            }
            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                setPadding(dp(10), dp(34), dp(10), dp(11))
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                    Color.TRANSPARENT, 0xBB102413.toInt(), 0xF2152717.toInt(),
                ))
            }
            overlay.addView(TextView(this).apply {
                text = historyTitle(h)
                setTextColor(Color.WHITE)
                setTextSize(if (isTvDevice) 17f else 15f)
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
            })
            overlay.addView(TextView(this).apply {
                text = h.title.substringAfter(" · ", "继续播放") +
                    if (h.positionMs > 0) " · ${formatTime(h.positionMs)}" else ""
                setTextColor(0xFFE5F2DE.toInt())
                setTextSize(11f)
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
            })
            val fraction = if (h.durationMs > 0) {
                (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f)
            } else 0f
            val track = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(0x99FFFFFF.toInt())
                    cornerRadius = dp(2).toFloat()
                }
            }
            track.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(ACCENT)
                    cornerRadius = dp(2).toFloat()
                }
            }, FrameLayout.LayoutParams(0, dp(3)))
            track.post {
                track.getChildAt(0).layoutParams =
                    FrameLayout.LayoutParams((track.width * fraction).toInt(), dp(3))
            }
            overlay.addView(track, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(9) })
            card.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            row.addView(card, LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                rightMargin = dp(12)
            })
            if (homeHistoryFirst == null) homeHistoryFirst = card
        }
        col.addView(hScroll(row), LinearLayout.LayoutParams(-1, dp(if (isTvDevice) 250 else 216)))
    }

    /**
     * 首页入口瓦片。
     *
     * 踩过的坑：手机 3 列排布时行高只有 74dp，而「图标 + 4dp + 标签 15sp + 上下各 6dp」
     * 实际需要 70dp 以上，再叠上系统字体放大，标签底部就被切掉了 ——
     * 真机上「电影」会渲染成「中影」（电的竖弯钩被裁）。所以现在图标固定 30dp、
     * 标签 14sp、内边距 6dp、行高 80dp，并关掉父容器的裁剪。
     * 图标改成 [HomeIconView] 自绘（原来是 ▷ ⌕ ⚙ 这类字体符号，跨设备大小不一）。
     * 另外瓦片不再做 focusScale 放大：放大 3% 后外侧边框会被父容器裁掉一条，
     * 而瓦片本身已有高亮描边，够表示焦点了。
     */
    private fun homeTile(kind: String, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(8), dp(3), dp(8))
            isFocusable = true
            isClickable = true
            contentDescription = label
            clipChildren = false
            clipToPadding = false
            setOnClickListener { onClick() }
            // 图标自绘（HomeIconView）：用字体里的 ▷ ⌕ ⚙ 这些符号，各家字体大小粗细不一，
            // 真机上「搜索」「设置」会明显比别人小一圈，看着像没渲染出来
            val selected = kind == "tv"
            val iconSize = dp(if (isTvDevice) 34 else 25)
            val icon = HomeIconView(this@MainActivity, kind).apply {
                tintColor = if (selected) Color.WHITE else INK
            }
            addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            val caption = TextView(this@MainActivity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.WHITE else INK)
                setTextSize(if (isTvDevice) 17f else 12f)
                includeFontPadding = false
                isSingleLine = true
                maxLines = 1
                setPadding(0, dp(4), 0, 0)
            }
            addView(caption, LinearLayout.LayoutParams(-1, -2))
            applyFocusStyle(this, if (selected) ACCENT else PANEL,
                onFocusChanged = { focused ->
                    icon.tintColor = if (focused || selected) Color.WHITE else INK
                    caption.setTextColor(if (focused || selected) Color.WHITE else INK)
                    if (focused) background = GradientDrawable().apply {
                        setColor(ACCENT)
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(2), 0xFFB8DED4.toInt())
                    }
                },
                focusScale = 1f)
        }

    /**
     * 首页入口工厂：id 来自 [HomeBar]，顺序与显隐由「首页按钮排序」决定。
     * 电影 / 剧集 / 综艺 三个入口各自带分类，点一下就重载对应榜单。
     */
    private fun homeTileFor(id: String): LinearLayout = when (id) {
        "movie" -> homeTile("movie", "电影") { applyMediaType("movie", "电影") }
        "tv" -> homeTile("tv", "点播") { applyMediaType("tv", "剧集") }
        "show" -> homeTile("show", "综艺") { applyMediaType("show", "综艺") }
        "search" -> homeTile("search", "搜索") { showHome() }
        "live" -> homeTile("live", "直播") { liveChannels = emptyList(); showLive() }
        "history" -> homeTile("history", "继续") { showHistoryCards() }
        "fav" -> {
            // 有更新时在标签上带一句，用户不用进页面才知道有新集
            val updates = prefs.favoriteUpdateCount()
            homeTile("fav", if (updates > 0) "追剧·$updates" else "追剧") { showFavorites() }
        }
        "sources" -> homeTile("sources", "片源") { showSources() }
        "setup" -> homeTile("setup", "设置") { showSetup() }
        else -> homeTile("other", HomeBar.label(id)) { }
    }

    private fun showRecommend() {
        currentScreen = "recommend"
        homeHistoryCoverViews.clear()
        if (recommendItems.isEmpty() && !recommendRequested) {
            recommendRequested = true
            loadRecommend()
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 手机端这一列会被塞进 RecyclerView 当「头部 item」，左右留白由 RecyclerView 的
            // padding 统一给（网格行和头部才能对齐），所以这里不要再加一层
            setPadding(if (isTvDevice) dp(48) else 0, dp(22),
                if (isTvDevice) dp(48) else 0, dp(16))
        }
        val brandBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandBar.addView(
            brandRow(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        updateBadgeBar = brandBar
        updateBadge = updateCapsule()
        updateBadge?.let { brandBar.addView(it) }
        col.addView(brandBar)
        homeHistoryFirst = null
        // 启动阶段的状态行：非模态，不挡任何操作，连上后自动消失
        bootNotice?.let { notice ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            row.addView(
                hintText(notice).apply {
                    setTextColor(if (bootFailed) 0xFFFCA5A5.toInt() else 0xFF94A3B8.toInt())
                    setPadding(0, 0, dp(12), dp(6))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (bootFailed) row.addView(button("重新连接") { autoConnect(silent = true) })
            col.addView(row)
        }

        // 入口顺序与显隐取自「设置 → 首页按钮排序」，不再是写死的六个
        val visibleIds = run {
            val order = prefs.homeBarOrder().filter { HomeBar.DEFAULT.contains(it) }.toMutableList()
            for (id in HomeBar.DEFAULT) if (!order.contains(id)) order.add(id)
            val hidden = prefs.homeBarHidden()
            val shown = order.filterNot { hidden.contains(it) }
            if (shown.isEmpty()) HomeBar.DEFAULT else shown
        }
        val tiles = visibleIds.map { id -> homeTileFor(id) }
        homeBar = null
        homeBarRows.clear()
        if (compact) {
            // 方案 B：手机五个主入口保持单行，避免首屏被九宫格占满。
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val rowHeight = dp(76)
            tiles.chunked(5).forEach { chunk ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
                chunk.forEach { tile ->
                    row.addView(tile, LinearLayout.LayoutParams(0, rowHeight, 1f).apply {
                        rightMargin = dp(2)
                    })
                }
                repeat(5 - chunk.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, rowHeight, 1f))
                }
                rows.addView(row, LinearLayout.LayoutParams(-1, rowHeight + dp(4)))
                homeBarRows.add(row)
            }
            col.addView(rows, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            // 宽度按入口数量摊开：TV 上最多 8 个入口也要一屏放得下，不靠横滑
            val sidePad = if (isTvDevice) 96 else 40
            val gap = if (isTvDevice) 12 else 6
            val availableDp = resources.configuration.screenWidthDp - sidePad
            val perTile = (availableDp / tiles.size.coerceAtLeast(1)) - gap
            val tileWidth = dp(perTile.coerceIn(if (isTvDevice) 96 else 80, if (isTvDevice) 145 else 94))
            val tileHeight = dp(if (isTvDevice) 88 else 76)
            tiles.forEach { tile ->
                bar.addView(tile, LinearLayout.LayoutParams(tileWidth, tileHeight).apply {
                    rightMargin = dp(if (isTvDevice) 12 else 6)
                })
            }
            col.addView(hScroll(bar), LinearLayout.LayoutParams(-1, tileHeight + dp(4)))
            homeBar = bar
            homeBarRows.add(bar)
        }
        addContinueWatchingB(col)
        // 标题 + 榜单切换：以前"最新/热门"是写死的混合，用户以为没有这个功能
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(9))
        }
        titleRow.addView(
            titleText("最新热门推荐", if (isTvDevice) 25f else 21f).apply {
                setTextColor(INK)
                setTypeface(null, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(button(mediaTypeLabel()) {
            val types = listOf("tv", "movie", "show")
            val next = types[(types.indexOf(mediaType) + 1) % types.size]
            applyMediaType(next, when (next) { "movie" -> "电影"; "show" -> "综艺"; else -> "剧集" })
        })
        titleRow.addView(button("榜单：" + when (prefs.recommendTag) {
            "最新" -> "最新"
            "热门" -> "热门"
            "高分" -> "高分"
            else -> "混合"
        }) {
            val order = listOf("", "最新", "热门", "高分")
            val next = order[(order.indexOf(prefs.recommendTag).let { if (it < 0) 0 else it } + 1) % order.size]
            prefs.recommendTag = next
            noMoreRecommend = false
            isLoadingMore = false
            lastVisibleIndex = 0
            homeScrollY = 0
            homeRvPosition = -1
            pendingGridSelection = -1
            toast("榜单：" + when (next) {
                "最新" -> "只看最新"
                "热门" -> "只看热门"
                "高分" -> "只看豆瓣高分"
                else -> "最新 + 热门混合"
            })
            loadRecommend()
        })
        col.addView(titleRow)
        // 启动/连接中不显示「暂时没有推荐」：那时还没有结果，
        // 顶部已经有一行状态说明，再来一条只会让首屏显得像出错了。
        // 首屏空态分两种，别混在一起：
        //   还在拉数据 → 只留一行"正在整理…"，不摆「重试推荐」（那时按了也没用，反而像出错）
        //   拉完了确实没有 → 才给说明 + 重试
        if (recommendItems.isEmpty() && bootNotice == null) {
            if (isLoadingMore) {
                col.addView(hintText("正在整理最新热播剧集…"))
            } else {
                col.addView(hintText("暂时没有推荐，仍可搜索或从历史继续观看。"))
                col.addView(button("重试推荐") { recommendRequested = false; showRecommend() })
            }
        }

        // 手机：整页是一个 RecyclerView，「头部」作为第 0 项占满整行，后面接海报网格。
        // 电视保持原来的 GridView（遥控器焦点是自己逐行接管的，换列表控件风险大于收益，
        // 而且电视上一屏能放二十多个，本来也到不了性能瓶颈）。
        if (compact) {
            showRecommendFeed(col)
            return
        }

        val grid = newGrid().apply {
            numColumns = if (isTvDevice) 6 else 2
            horizontalSpacing = dp(if (isTvDevice) 18 else 12)
            verticalSpacing = dp(20)
            setPadding(0, dp(16), 0, 0)
        }
        grid.adapter = recommendAdapter()
            // 顶部入口栏可能是一行（TV）也可能是两行（手机 3×2），且都在 ScrollView/嵌套容器里，
            // 系统的焦点搜索找不到下方网格。这里逐行接管上下键：
            // 最后一行往下进网格，其余行往下/往上在行间移动。
            homeBarRows.forEachIndexed { rowIndex, row ->
                for (bi in 0 until row.childCount) {
                    row.getChildAt(bi).setOnKeyListener { _, code, ev ->
                        if (ev.action != android.view.KeyEvent.ACTION_DOWN) {
                            false
                        } else when (code) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (rowIndex == homeBarRows.lastIndex) {
                                    (homeHistoryFirst ?: grid).requestFocus()
                                    true
                                } else {
                                    val next = homeBarRows[rowIndex + 1]
                                    if (bi < next.childCount) {
                                        next.getChildAt(bi).requestFocus()
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                if (rowIndex == 0) {
                                    false
                                } else {
                                    val prev = homeBarRows[rowIndex - 1]
                                    if (bi < prev.childCount) {
                                        prev.getChildAt(bi).requestFocus()
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }
                            else -> false
                        }
                    }
                }
            }
            // 网格已在第一行时，上方向键回到顶部按钮
            grid.setOnKeyListener { _, code, ev ->
                if (code == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    ev.action == android.view.KeyEvent.ACTION_DOWN &&
                    grid.selectedItemPosition < grid.numColumns
                ) {
                    homeBarRows.firstOrNull()?.getChildAt(0)?.requestFocus()
                    true
                } else {
                    false
                }
            }
        grid.setOnItemClickListener { _, _, pos, _ ->
            recommendItems.getOrNull(pos)?.let { doSearch(it.title) }
        }
        homeGrid = grid
        // 整页放进 ScrollView 一起滚。
        // 以前是「头部固定 + 网格自己内部滚动」，手机上头部（入口栏 + 最近观看）就占了三分之二屏，
        // 网格只剩一两行，用户在上面滑半天历史记录纹丝不动、下面的推荐根本露不出来。
        col.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        syncHomeGridHeight()

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(col)
        }
        homeScroll = scroll
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            homeScrollY = scrollY
            if (suppressAutoLoad) {
                suppressAutoLoad = false
                return@setOnScrollChangeListener
            }
            val content = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
            val remaining = content.height - scrollY - scroll.height
            if (remaining < dp(600) && !isLoadingMore && !noMoreRecommend && recommendItems.isNotEmpty()) {
                loadRecommend(true)
            }
        }

        // 回到首页恢复上次的滚动位置：从播放器返回时整页会重建，不还原又跳回顶部
        setScreen(scroll, focusFirst = homeScrollY <= 0)
        if (homeScrollY > 0) {
            suppressAutoLoad = true
            scroll.post { scroll.scrollTo(0, homeScrollY) }
        }
        scheduleHomeMaintenance()
    }

    /**
     * 手机首页的整页列表。
     *
     * 结构是「一个 RecyclerView = 头部 item（占满整行）+ N 个海报格子」：
     *   · 头部随内容一起上滚，手机上不会再出现「头部钉死、下面只剩一条缝」；
     *   · 只渲染可见行，滚到两三百条也不会把全部行都 measure 一遍；
     *   · 触底自动续加载用 LayoutManager 的可见位置判断，不再靠算 content.height。
     */
    private fun showRecommendFeed(header: LinearLayout) {
        // 手机端没有 GridView，清掉引用免得 loadRecommend 去同步一个不存在的高度
        homeGrid = null
        val rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = if (position == 0) 2 else 1
                }
            }
            setPadding(dp(20), 0, dp(20), dp(16))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // 首页不需要增删动画：翻页时那一排卡片会挨个淡入，反而像卡了一下
            itemAnimator = null
        }
        val feed = HomeFeedAdapter(header)
        rv.adapter = feed
        homeRecycler = rv
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                homeRvPosition = lm.findFirstVisibleItemPosition()
                if (suppressAutoLoad) {
                    // 刚恢复位置的那一次回滚不算「用户滑到底」
                    suppressAutoLoad = false
                    return
                }
                val last = lm.findLastVisibleItemPosition()
                if (feed.itemCount - last <= 6 &&
                    !isLoadingMore && !noMoreRecommend && recommendItems.isNotEmpty()
                ) {
                    loadRecommend(true)
                }
            }
        })
        setScreen(rv, focusFirst = homeRvPosition <= 0)
        if (homeRvPosition > 0) {
            suppressAutoLoad = true
            rv.post {
                (rv.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(homeRvPosition, 0)
            }
        }
        scheduleHomeMaintenance()
    }

    /**
     * 把首页网格撑到内容全高。
     *
     * GridView 放进 ScrollView 时只会按最小高度测量（永远显示一行），
     * 所以先量一个真实格子的高度，再按行数算总高。卡片高度是固定的
     * （海报按屏宽算 + 两行标题 + 一行副标题），所以这个算法是准的。
     */
    private fun syncHomeGridHeight() {
        val grid = homeGrid ?: return
        val count = recommendItems.size
        if (count <= 0) return
        val adapter = grid.adapter as? BaseAdapter ?: return
        val cols = grid.numColumns.coerceAtLeast(1)
        val probe = adapter.getView(0, null, grid)
        probe.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val cellHeight = probe.measuredHeight.coerceAtLeast(dp(60))
        val rows = (count + cols - 1) / cols
        val total = dp(16) + rows * cellHeight + (rows - 1).coerceAtLeast(0) * dp(20)
        val lp = grid.layoutParams as? LinearLayout.LayoutParams
        if (lp == null || lp.height != total) {
            grid.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, total,
            )
        }
    }

    private fun mediaTypeLabel(): String = when (mediaType) {
        "movie" -> "电影"
        "show" -> "综艺"
        else -> "剧集"
    }

    /**
     * 切换首页分类（电影 / 剧集 / 综艺）。
     *
     * 关键：**切换成功之前屏幕上是旧内容**。
     * 旧写法是「清空列表 → 立刻重画 → 后台拉数据」，于是切的那一两秒里，用户看到的是
     * 一个空页面 + 「正在整理最新热播剧集…」+「重试推荐」按钮，像是加载失败了。
     * 现在改成：旧列表原样留着 → 数据到手再一次性换页；失败就提示一句，页面还停在原分类。
     *
     * 请求走独立的 "mediaswitch" 槽位，避免和首页「加载更多」互相取消（同槽位会互相顶掉）。
     */
    private fun applyMediaType(type: String, label: String) {
        if (type == mediaType && currentScreen == "recommend" && recommendItems.isNotEmpty()) {
            toast("当前就是「$label」，已经是最新推荐")
            return
        }
        toast("正在切换到「$label」…")
        runApiK("mediaswitch", null, {
            runBlocking {
                val latest = async(Dispatchers.IO) {
                    runCatching { api.douban(type, "最新", 0, 8) }.getOrDefault(emptyList())
                }
                val popular = async(Dispatchers.IO) {
                    runCatching { api.douban(type, "热门", 0, 8) }.getOrDefault(emptyList())
                }
                (latest.await() + popular.await()).distinctBy { it.title.trim() }
            }
        }, { items ->
            if (items.isEmpty()) {
                toast("「$label」暂时没有推荐，保持原来的列表")
                return@runApiK
            }
            // 数据到手才换：分类、列表、分页状态一起切
            mediaType = type
            recommendItems = items
            recommendPage = 0
            recommendRequested = true
            noMoreRecommend = false
            isLoadingMore = false
            lastVisibleIndex = 0        // 换分类等于换了一份列表，不能沿用上一份的滚动位置
            homeScrollY = 0
            homeRvPosition = -1
            pendingGridSelection = -1
            showRecommend()
            toast("已切换到「$label」")
        }, quiet = true, onError = { e ->
            toast("切换到「$label」失败：" + describe(e))
        })
    }

    private fun loadRecommend(more: Boolean = false) {
        if (more && (isLoadingMore || noMoreRecommend)) return
        isLoadingMore = true
        val page = if (more) recommendPage + 1 else 0
        // 记下发起时的分类：切换分类的请求在另一个槽位，可能先回来了。
        // 这时这份结果已经过期，直接丢掉，否则会把「电影」的一页追加到「剧集」列表后面。
        val loadingType = mediaType
        // 翻页不要提示：以前滚到底部会弹一个「加载更多…」对话框，非常吵。
        // 首屏加载也有页面内的「正在整理最新热播剧集…」兜着，同样不需要胶囊。
        runApiK("recommend", null, {
            runBlocking {
                val tag = prefs.recommendTag
                // 混合模式两路各取 8；单选某个榜单就一次取 16，条数别变少
                val specs = if (tag.isEmpty()) listOf("最新" to 8, "热门" to 8) else listOf(tag to 16)
                specs.map { (t, n) ->
                    async(Dispatchers.IO) {
                        runCatching { api.douban(loadingType, t, page * n, n) }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll().flatten().distinctBy { it.title.trim() }
            }
        }, { items ->
            isLoadingMore = false
            if (loadingType != mediaType) return@runApiK
            if (items.isEmpty()) {
                if (more) {
                    noMoreRecommend = true
                    toast("已经到底了")
                } else {
                    noMoreRecommend = true
                    showRecommend()
                }
            } else {
                val appending = more && recommendItems.isNotEmpty() && currentScreen == "recommend"
                val updated = (if (more) recommendItems + items else items)
                    .distinctBy { it.title.trim() }
                val unchanged = !more && updated == recommendItems
                recommendItems = updated
                recommendPage = page
                // 落盘：下次冷启动先用这份渲染
                prefs.saveRecommendCache(mediaType, recommendItems)
                if (unchanged && currentScreen == "recommend") return@runApiK
                if (appending && (homeGrid != null || homeRecycler != null)) {
                    // 追加数据只刷新适配器。以前每翻一页都把整页重建一遍，
                    // 滚到底部会整屏闪一下、焦点也掉回顶部入口栏。
                    (homeGrid?.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    homeRecycler?.adapter?.notifyDataSetChanged()
                    // 电视端网格是撑到内容全高的（外头是 ScrollView），新增一页要同步加高；
                    // 手机端 RecyclerView 自己算高度，不需要
                    syncHomeGridHeight()
                } else {
                    if (!more) {
                        lastVisibleIndex = 0
                        homeScrollY = 0
                        homeRvPosition = -1
                    }
                    showRecommend()
                }
            }
        })
    }

    /** 海报单元格的尺寸参数：电视 6 列、手机 2 列，左右留白也要算进去 */
    private fun recommendCellMetrics(): IntArray {
        val columns = if (isTvDevice) 6 else 2
        val sidePadding = if (isTvDevice) dp(96) else dp(40)
        val gap = dp(if (isTvDevice) 18 else 12) * (columns - 1)
        val posterWidth = (resources.displayMetrics.widthPixels - sidePadding - gap) / columns
        return intArrayOf(posterWidth, (posterWidth * 1.43f).toInt())
    }

    /** 空的海报单元格（图片位 + 剧名 + 评分）。GridView 与 RecyclerView 共用同一份结构 */
    private fun recommendCellView(): LinearLayout {
        val posterHeight = recommendCellMetrics()[1]
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(
                ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, posterHeight)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        setColor(0xFFDDEAE2.toInt())
                        cornerRadius = dp(14).toFloat()
                    }
                    clipToOutline = true
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    setTextSize(if (isTvDevice) 16f else 15f)
                    setTextColor(INK)
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 2
                    setPadding(0, dp(9), 0, 0)
                },
            )
            addView(TextView(this@MainActivity).apply {
                setTextSize(12f)
                setTextColor(BRIGHTER_TEXT)
                maxLines = 1
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    /** 把一个单元格填上第 position 条推荐（图片按 URL 校验，快速滚动不会串图） */
    private fun bindRecommendCell(cell: LinearLayout, position: Int) {
        val item = recommendItems.getOrNull(position) ?: return
        val metrics = recommendCellMetrics()
        val posterWidth = metrics[0]
        val posterHeight = metrics[1]
        val img = cell.getChildAt(0) as ImageView
        val label = cell.getChildAt(1) as TextView
        val rating = cell.getChildAt(2) as TextView
        label.text = item.title
        rating.text = if (item.rating.isEmpty()) "新剧 · 热播" else "★ ${item.rating}  ·  热播"
        img.tag = item.cover
        img.setImageBitmap(null)
        imageLoader.load(item.cover, posterWidth, posterHeight) { bmp ->
            if (img.tag == item.cover) img.setImageBitmap(bmp)
        }
        cell.setOnClickListener { doSearch(item.title) }
    }

    /** 海报网格：ImageView 复用时要校验 URL，否则快速滚动会串图。 */
    private fun recommendAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount() = recommendItems.size
        override fun getItem(position: Int): Any = recommendItems[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: recommendCellView()
            bindRecommendCell(cell, position)
            return cell
        }
    }

    /**
     * 手机首页的单层列表：第 0 项是整块头部（品牌栏 / 入口 / 最近观看 / 榜单标题），
     * 之后是海报网格。
     *
     * 为什么不用「ScrollView 套 GridView」：把 GridView 撑到内容全高之后，它的视口高度
     * 就等于全部内容高度，于是所有行都会被当成「屏内」一次性布局，复用失效 ——
     * 30 条看不出问题，滑到两三百条内存和帧率就线性变差。
     */
    private inner class HomeFeedAdapter(private val header: View) :
        androidx.recyclerview.widget.RecyclerView.Adapter<HomeFeedAdapter.Holder>() {

        inner class Holder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

        override fun getItemCount(): Int = 1 + recommendItems.size

        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_CELL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            if (viewType == TYPE_HEADER) {
                val box = FrameLayout(this@MainActivity)
                if (header.parent != null) (header.parent as? ViewGroup)?.removeView(header)
                box.addView(
                    header,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                Holder(box)
            } else {
                Holder(recommendCellView())
            }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position == 0) return
            bindRecommendCell(holder.itemView as LinearLayout, position - 1)
        }
    }

    /**
     * 设置类列表的统一行。
     *
     * **行本身绝对不能 focusable**（这里踩过坑，真机上就是「播放设置里怎么点都改不了」）：
     * 行一旦 isFocusable / isFocusableInTouchMode，手机触摸模式下点一下只会把焦点给它、
     * 亮一圈白框，ListView 的 onItemClick 却不触发；行自己挂的 OnClickListener 也会被
     * 焦点变化引起的列表重新布局打断（触摸事件变成 CANCEL）。表现就是「点了有反应但没反应」。
     *
     * 焦点交给 ListView 自己持有（和 GridView 的处理完全一致）：
     *   · 遥控器：上下移动选中行，OK 走 bindList 里的 key 监听
     *   · 手机：直接点击走 onItemClick
     * 两条路都通，不用给每行单独画焦点框 —— ListView 的 selector 已经画了。
     */
    private fun simpleAdapter(items: List<String>): BaseAdapter =
        object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    setTextSize(17f)
                    setTextColor(TEXT_COLOR)
                    // 上下留够 20dp：触摸目标要够大，遥控器上也更好扫读
                    setPadding(dp(18), dp(20), dp(18), dp(20))
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    background = GradientDrawable().apply {
                        setColor(PANEL)
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), BORDER)
                    }
                }
                tv.text = items[position]
                return tv
            }
        }
}
