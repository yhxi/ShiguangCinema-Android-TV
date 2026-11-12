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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 首页 RecyclerView 的两种 item：整块头部、海报格子 */
private const val TYPE_HEADER = 0
private const val TYPE_CELL = 1

/** 收藏自动检查更新的最小间隔（6 小时） */
private const val FAVORITE_CHECK_INTERVAL_MS = 6 * 3_600_000L

/** 搜索结果复用的时间窗：3 分钟内的同一关键词不再重复请求 */
private const val SEARCH_CACHE_MS = 3 * 60_000L

/** 详情页复用的时间窗：5 分钟内同一部剧（同源）不再重复请求 */
private const val DETAIL_CACHE_MS = 5 * 60_000L

/** 搜索时同时进行的源请求数上限（各源是不同主机，可以放得宽一些） */
private const val MAX_SEARCH_CONCURRENCY = 8

/** 搜索时同时打到服务端 /api/search 的请求数上限：同一台机器，压太狠会被限流 */
private const val SERVER_SEARCH_CONCURRENCY = 4

/** 单源请求的超时：整批源才需要 45 秒，单源给 15 秒足够，装死的源不能占住线程太久 */
private const val SEARCH_SOURCE_TIMEOUT_MS = 15_000

/** 分批合并结果的刷新间隔：太密会打断正在翻页的用户，太疏又看不出「随搜随出」 */
private const val PARTIAL_RENDER_INTERVAL_MS = 400L

/**
 * 原生客户端主界面。刻意不用 XML 布局、不引 AppCompat/Leanback：
 * 只用框架控件，D-pad 焦点是系统原生支持的，老设备上行为最可预测。
 *
 * 屏幕：设置 → 片源 → 首页（搜索 + 结果）→ 详情（选集）→ 播放器（独立 Activity）
 */
class MainActivity : EdgeSwipeBackActivity() {

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
    private var homeLiveEntry: View? = null
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
    private var homeRvOffset = 0
    private var uiMonitor: UiFrameMonitor? = null
    private var lastRepeatKeyAt = 0L
    private var homeFocusedTitle: String? = null
    private var homeLastHeaderFocus: View? = null
    /** 收藏自动检查的节流：本次进程内最近一次检查时间 */
    private var lastFavoriteCheckAt = 0L
    /** 搜索结果短时缓存：key = 关键词 + 源集合，value = (写入时间, 结果) */
    private val searchCache = HashMap<String, Pair<Long, List<SearchItem>>>()
    private var lastSearchKeyword = ""
    private var pendingSourceEpisode = -1
    private var pendingSourcePosition = 0L
    private var pendingSourceUrl = ""
    private var pendingSourceTitle = ""
    private var searchInProgress = false
    /**
     * 本次搜索的编号。取消搜索、或又发起一次新搜索时自增，
     * 在途的旧编号回包全部作废 —— 否则慢源的迟到结果会把用户拽回结果页。
     */
    @Volatile private var searchToken = 0
    private var searchConnections: SearchConnections? = null
    /**
     * 界面世代：每次**真正换页**（currentScreen 变了）+1。
     * 搜索结果只允许落在「发起搜索的那个页面」或「本次搜索已经画出来的结果页」，
     * 世代对不上就说明用户中途去了别处，哪怕后来又回到同一个页面也不再回跳。
     */
    private var screenEpoch = 0
    private var searchOriginEpoch = 0
    private var searchResultsEpoch = -1
    /** 结果网格：分批合并时用它把选中项与焦点还回原处，避免每来一批就跳回顶部 */
    private var resultsGrid: GridView? = null
    /** 结果页顶部计数与进度行：原地刷新时只改文字，不重建页面 */
    private var resultsTitle: TextView? = null
    /** 搜索页输入框的内容：整页重建（热门词回填等）时要把用户打的字带回去 */
    private var homeKeyword = ""
    private var homeKeywordField: EditText? = null
    /** 搜索页热门榜单的就地填充容器（label → 视图），showHome 每次重建时清空重建 */
    private val hotChipRows = linkedMapOf<String, LinearLayout>()
    private val hotBoxes = linkedMapOf<String, LinearLayout>()
    /** 搜索页的联想候选区与结果列表：打字时二选一显示 */
    private var homeSuggestBox: LinearLayout? = null
    private var homeResultsList: View? = null
    /** 最近搜索卡片：只要搜过就常驻，打字联想时收起 */
    private var homeHistoryBox: LinearLayout? = null
    /** 热门榜单整块（四张卡片的外壳）：出搜索结果时让位给结果列表 */
    private var homeHotSection: LinearLayout? = null
    /** 详情页短时缓存：key = vodId + 源地址，value = (写入时间, 详情) */
    private val detailCache = HashMap<String, Pair<Long, Detail>>()
    /** 片源管理列表：测速完只刷行内容，不重建整页 */
    private var sourcesListView: ListView? = null
    /** 顶部加载胶囊：非模态，所以用 BACK 键取消当前槽位的请求 */
    private var busyBar: LinearLayout? = null
    private var busyLabel: TextView? = null
    private var busyKey: String? = null
    private var busyCancellable = true
    /** 启动阶段的非模态提示：null = 不显示提示；仅首次连接时显示加载状态。 */
    private var bootNotice: String? = null
    private var bootFailed = false
    private var bootRetryAttempt = 0
    private var bootRetryJob: Job? = null
    /** 当前界面名，用于实现遥控器 BACK 的层级回退（否则任何界面按返回都会直接退出应用）。 */
    private var currentScreen = "setup"
    /** 设置页是否展开「服务器连接」区块。已连接时默认收起（低频操作不占首屏） */
    private var setupShowFields = false
    private val REQ_PICK_LIVE = 1004
    private val REQ_PICK_JSON = 1001
    private val REQ_STORAGE = 1002
    private val REQ_PLAYER = 1003

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
            val s = if (focusedNow && !prefs.smoothUi) focusScale else 1f
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

    private var lastHomeBackAt = 0L
    private var systemBackCallback: android.window.OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && !isTvDevice) {
            systemBackCallback = android.window.OnBackInvokedCallback { onBackPressed() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
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
        root.background = SoftBackgroundDrawable()
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
        if (Build.VERSION.SDK_INT >= 33) systemBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        cancelUpdateDownload(showMessage = false)
        homeMaintenanceJob?.cancel()
        favoriteCheckJob?.cancel()
        hotSearchJob?.cancel()
        qrServer?.stop()
        searchConnections?.cancel()
        scope.cancel()
        imageLoader.close()
        super.onDestroy()
    }

    private val quotaHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var quotaVisible = false
    private var childrenQuotaTile: LinearLayout? = null
    private var quotaLabelView: Button? = null
    private val quotaTick = object : Runnable {
        override fun run() {
            if (!quotaVisible) return
            val locked = ChildrenQuota.remaining(this@MainActivity) <= 0L
            childrenQuotaTile?.let { tile ->
                tile.alpha = 1f
                (tile.getChildAt(1) as? TextView)?.text = "儿童"
                tile.contentDescription = "儿童，${ChildrenQuota.label(this@MainActivity)}"
            }
            quotaLabelView?.text = "${ChildrenQuota.label(this@MainActivity)} · 家长设置 / 加时"
            if (currentScreen == "children_locked" && !locked) showChildren()
            quotaHandler.postDelayed(this, 1000)
        }
    }
    private fun showChildrenLocked() {
        currentScreen = "children_locked"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pagePad(40), dp(24), pagePad(40), dp(24)) }
        col.addView(titleText("今天的儿童时间已用完", 24f))
        col.addView(hintText("明天恢复为 ${ChildrenQuota.dailyMinutes(this)} 分钟；家长临时加时只对今天有效。"))
        col.addView(button("家长设置 / 加时") { ChildrenQuota.parent(this) { if (ChildrenQuota.remaining(this) > 0) showChildren() else showChildrenLocked() } })
        col.addView(button("返回首页") { childrenSearchOrigin = null; showRecommend() })
        setScreen(col)
    }

    override fun onPause() {
        recommendRetryJob?.cancel()
        recommendRetryJob = null
        quotaVisible = false
        quotaHandler.removeCallbacks(quotaTick)
        uiMonitor?.stop()
        homeMaintenanceJob?.cancel()
        favoriteCheckJob?.cancel()
        hotSearchJob?.cancel()
        if (currentScreen == "children") childrenOverviewScrollY = childrenOverviewScroll?.scrollY ?: 0
        // Do not keep speculative cover requests running while the app is in the background.
        childrenPrefetchJob?.cancel()
        childrenNearPrefetchJob?.cancel()
        childrenCoverJobs.values.toList().forEach { it.cancel() }
        childrenPreloadedSignature = ""
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        ChildrenQuota.init(this)
        quotaVisible = true
        if (currentScreen == "recommend") scheduleRecommendRetry()
        quotaHandler.removeCallbacks(quotaTick)
        quotaHandler.post(quotaTick)
        if (BuildConfig.TEST_BUILD && prefs.uiStatsEnabled) startUiMonitor()
        hideSystemUi()
        if (currentScreen == "children" || currentScreen == "children_more") {
            root.post {
                if (currentScreen != "children" && currentScreen != "children_more") return@post
                fun resumeVisibleCovers(view: View) {
                    if (view is ImageView) {
                        val title = view.tag as? String
                        if (title != null && ChildrenCatalog.preloadOrder().contains(title)) {
                            bindChildrenCover(view, title, view.width.coerceAtLeast(dp(120)), view.height.coerceAtLeast(dp(90)))
                        }
                    } else if (view is ViewGroup) {
                        for (index in 0 until view.childCount) resumeVisibleCovers(view.getChildAt(index))
                    }
                }
                resumeVisibleCovers(root)
                prefetchChildrenCovers(dp(240), dp(130))
            }
        }
        resumePendingUpdateInstall()
        // A server can also be changed in network diagnostics while this Activity is stopped.
        val savedServer = prefs.serverUrl
        if (::api.isInitialized && ::imageLoader.isInitialized && savedServer.isNotEmpty() && api.baseUrl != savedServer.trimEnd('/')) {
            api = Api(savedServer).apply { restoreCookie(prefs.cookie) }
            imageLoader.updateApi(api)
            searchCache.clear(); detailCache.clear()
            recommendItems = emptyList(); recommendRequested = false
            if (currentScreen == "recommend") showRecommend()
        }
        // 只在从播放器返回时重建首页；首次 onResume 和从系统界面返回无需白画一遍。
        if (refreshHomeAfterPlayback && ::root.isInitialized &&
            currentScreen == "recommend" && recommendItems.isNotEmpty()) {
            showRecommend()
        }
        refreshHomeAfterPlayback = false
    }

    private fun startUiMonitor() {
        if (uiMonitor == null) {
            @Suppress("DEPRECATION")
            val refresh = windowManager.defaultDisplay.refreshRate.coerceIn(30f, 240f)
            uiMonitor = UiFrameMonitor((1_000_000_000L / refresh).toLong())
        }
        uiMonitor?.start()
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
                if (key == "search") {
                    // 作废这次搜索的编号：工作线程里还在跑的源即使稍后返回，也不会再把结果页弹出来
                    searchToken++
                    searchConnections?.cancel()
                    searchInProgress = false
                    if (currentScreen == "results") showResults()
                }
                toast("已取消")
            } else {
                toast("正在处理，稍等一下…")
            }
            return
        }
        if (currentScreen != "recommend") lastHomeBackAt = 0L
        when (currentScreen) {
            "files" -> if (browseLiveImport) { browseLiveImport = false; showLive() } else showSources()
            "qradd" -> { qrServer?.stop(); qrServer = null; showSources() }
            "children" -> showRecommend()
            "children_more" -> showChildren()
            "children_empty" -> returnToChildrenOrigin()
            "results" -> {
                val section = childrenSearchOrigin
                childrenSearchOrigin = null
                if (section == null) showRecommend()
                else if (section < 0) showChildren() else showChildrenMore(section)
            }
            "playbacksources" -> { cancelJob("playbacksources"); showRecommend() }
            "serversettings" -> showSetup()
            "detail" -> if (childrenSearchOrigin != null && lastResults.isNotEmpty()) showResultsFromDetail() else showRecommend()
            "live", "setup", "history" -> showRecommend()
            "live_import" -> showLive()
            "sources" -> returnFromSources()
            "vodsources" -> showSources()
            "home" -> {
                homeKeywordField?.let { field ->
                    (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(field.windowToken, 0)
                    field.clearFocus()
                }
                showRecommend()
            }
            "recommend" -> {
                if (isTvDevice) super.onBackPressed()
                else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (lastHomeBackAt != 0L && now - lastHomeBackAt < 2000L) super.onBackPressed()
                    else { lastHomeBackAt = now; toast("再返回一次退出应用") }
                }
            }
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
        if (isTvDevice && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 &&
            event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRepeatKeyAt < 70L) return true
            lastRepeatKeyAt = now
        }
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
        // Share the restrained warm-white/sage background across phone and TV pages.
        root.background = SoftBackgroundDrawable()
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 加载胶囊挂在 root 上，removeAllViews 会把它一起摘掉，这里重新挂回最上层
        if (busyBar?.visibility == View.VISIBLE) attachBusyBar()
        // 只有真的换页才算「世代」推进；同一页面的原地重绘（补封面、批量合并结果）不算，
        // 否则后台每刷一次就会把正在进行的搜索判成「用户已经离开」。
        if (currentScreen != lastRenderedScreen) screenEpoch++
        // 离开结果页就松开网格引用，别把整棵视图树挂在 Activity 上
        if (currentScreen != "results") {
            resultsGrid = null
            resultsTitle = null
        }
        if (lastRenderedScreen != null && lastRenderedScreen != currentScreen && !prefs.smoothUi) {
            // 用 withLayer() 让动画走硬件层，老盒子上比逐帧重绘稳
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120L).withLayer().start()
        }
        lastRenderedScreen = currentScreen
        uiMonitor?.recordPage()
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
     * 小号标签（最近搜索的词条用它）。
     *
     * 只在白底卡片内出现，底色用 SOFT 浅灰绿跟卡片区分开；聚焦才有描边。
     */
    private fun chip(label: String, bg: Int = SOFT, onClick: () -> Unit): TextView =
        TextView(this).apply {
            setText(label)
            setTextSize(14f)
            setTextColor(TEXT_COLOR)
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
            applyFocusStyle(this, bg, this, focusScale = 1f)
        }

    /** 白底圆角卡片（热门榜单 / 最近搜索）：细描边 + 大圆角，让榜单像一块正式版面。 */
    private fun panelCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(PANEL)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), BORDER)
        }
    }

    /**
     * 卡片头：竖色条 + 加粗标题 + 右侧小注。
     * actionLabel 传了就在最右侧放一个可聚焦的小动作（如「清空」）。
     */
    private fun cardHeader(
        label: String,
        note: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    setColor(ACCENT)
                    cornerRadius = dp(2).toFloat()
                }
            },
            LinearLayout.LayoutParams(dp(4), dp(18)).apply { rightMargin = dp(8) },
        )
        row.addView(
            TextView(this).apply {
                setText(label)
                setTextSize(16f)
                setTextColor(INK)
                setTypeface(null, Typeface.BOLD)
            },
        )
        row.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        row.addView(
            TextView(this).apply {
                setText(note)
                setTextSize(12f)
                setTextColor(BRIGHTER_TEXT)
            },
        )
        if (actionLabel != null && onAction != null) {
            row.addView(
                TextView(this).apply {
                    setText(actionLabel)
                    setTextSize(13f)
                    setTextColor(ACCENT)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    setOnClickListener { onAction() }
                    applyFocusStyle(this, PANEL, this, focusScale = 1f)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(8) },
            )
        }
        return row
    }

    /**
     * 榜单词条：排名序号 + 片名，横向滚动排开就是一份带名次的榜单。
     * 前三名按榜单惯例用暖色（红 / 橙 / 琥珀），其余灰绿；点击直接搜。
     */
    private fun rankChip(rank: Int, title: String, onClick: () -> Unit): View {
        val rankColor = when (rank) {
            1 -> 0xFFE11D48.toInt()
            2 -> 0xFFF97316.toInt()
            3 -> 0xFFF59E0B.toInt()
            else -> BRIGHTER_TEXT
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(7), dp(14), dp(7))
            setOnClickListener { onClick() }
            // 整块是 LinearLayout（排名 + 片名两个文字），没有单一文字控件可传
            applyFocusStyle(this, SOFT, focusScale = 1f)
        }
        row.addView(
            TextView(this).apply {
                setText(rank.toString())
                setTextSize(14f)
                setTextColor(rankColor)
                setTypeface(null, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(7) },
        )
        row.addView(
            TextView(this).apply {
                setText(title)
                setTextSize(14f)
                setTextColor(TEXT_COLOR)
                isSingleLine = true
            },
        )
        return row
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
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp((46 * resources.configuration.fontScale.coerceIn(1f, 1.5f)).toInt()))
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
        block: suspend CoroutineScope.() -> T,
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
                if (busyKey == key) dismissProgress()
                then(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消（切换页面 / 被同槽位的新请求顶掉）不是网络错误。
                // 以前这里被 catch(Exception) 一并接住，界面上就会冒出
                // 「网络失败：StandaloneCoroutine was cancelled」这种莫名其妙的报错。
                // 这里必须重新抛出，交给协程框架按「正常取消」处理。
                throw e
            } catch (e: Exception) {
                if (busyKey == key) dismissProgress()
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
        block: suspend CoroutineScope.() -> T,
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
        bootRetryAttempt = 0
        bootRetryJob?.cancel()
        // 连上之后再拉推荐，避免还没连上就先发一轮注定失败的请求
        recommendRequested = true
        primeRecommendFromCache()   // 有缓存就先把海报墙画出来
        bootNotice = if (recommendItems.isEmpty()) "正在加载…" else null
        showRecommend()
        autoConnect(silent = true)
    }
    /** 首页启动连接失败后安静地退避重试，不把瞬时网络抖动变成需要用户处理的错误提示。 */
    private fun scheduleBootRetry() {
        bootRetryJob?.cancel()
        val delays = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L)
        val waitMs = delays[bootRetryAttempt.coerceAtMost(delays.lastIndex)]
        bootRetryAttempt++
        bootRetryJob = scope.launch {
            delay(waitMs)
            if (!isFinishing && !isDestroyed) autoConnect(silent = true)
        }
    }

    /**
     * 连接服务器（顺带拉服务端下发的片源配置）。
     *
     * @param silent 静默连接：不弹"正在连接"对话框，失败也不弹错误框，
     *   只把原因写到首页顶部的那行小字上。启动时走这条路。
     */
    private fun autoConnect(silent: Boolean = false) {
        if (jobOf("connect")?.isCompleted == false) return
        val connectionApi = api
        val connectionUrl = prefs.serverUrl
        val connectionPassword = prefs.password
        val oldCookie = prefs.cookie
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
                val first = connectionApi.status()
                val st = if (first.passwordRequired && !first.verified) {
                    connectionApi.login(connectionPassword)
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
                        runCatching { connectionApi.importSourceList(url) }.getOrDefault(emptyList())
                    }
                } else {
                    emptyList()
                }
                val cookie = connectionApi.currentCookie() ?: oldCookie
                if (coroutineContext[Job]?.isActive == false) throw kotlinx.coroutines.CancellationException()
                val saved = prefs.rememberServer(connectionUrl, connectionPassword, cookie, expectedCurrentUrl = connectionUrl)
                Triple(st, subSources, saved)
            },
            then = { (st, subSources, saved) ->
                seedLiveFromServer(st)
                seedDefaultSourcesAsync(st, subSources)
                bootNotice = if (saved) null else "已连接，但服务器凭据未能安全保存"
                bootFailed = false
                bootRetryAttempt = 0
                bootRetryJob?.cancel()
                bootRetryJob = null
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
                recommendRequested = true
                if (silent) {
                    // 首屏连接失败通常只是网络或服务器启动稍慢，保持首页安静并在后台重试。
                    bootFailed = false
                    bootNotice = null
                    if (currentScreen == "recommend") showRecommend()
                    scheduleBootRetry()
                } else {
                    bootFailed = true
                    bootNotice = "未连接：${describe(e)}"
                    if (currentScreen == "recommend") showRecommend()
                }
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

    private fun ensureBuiltInLiveSources(serverDefaults: List<String>? = null) {
        if (serverDefaults != null) prefs.removeServerDefaultLiveSources(serverDefaults)
        val urls = prefs.liveUrls()
        if (prefs.activeLiveUrl !in urls) {
            prefs.activeLiveUrl = urls.firstOrNull().orEmpty()
            liveChannels = emptyList()
            liveChannelSource = ""
        }
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
     * 内置默认片源：直接用 [BuiltInSources] 里固化的清单（包含 2026-11-11 新增的非凡 HTTPS）。
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
        browseLiveImport = false
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
    private var browseLiveImport = false

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

        runApiK("files", "读取目录…", {
            val entries = ArrayList<Pair<String, java.io.File>>()
            dir.parentFile?.let { entries.add("⬆  上级目录" to it) }
            val children = dir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() }),
            ).orEmpty()
            children.forEach { f ->
                val name = if (f.isDirectory) "📁  " + f.name else "📄  " + f.name
                entries.add(name to f)
            }
            entries
        }, { entries ->
            if (currentScreen == "files" && browsePath == dir.absolutePath) renderFileBrowser(entries)
        })
    }

    private fun renderFileBrowser(entries: List<Pair<String, java.io.File>>) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText((if (browseLiveImport) "选择直播 M3U/TXT · 当前：" else "选择 sources.json · 当前：") + browsePath, 15f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button(if (browseLiveImport) "返回电视直播" else "返回片源管理") {
            if (browseLiveImport) { browseLiveImport = false; showLive() } else showSources()
        })
        bar.addView(button("/sdcard") { showFileBrowser("/sdcard") })
        bar.addView(button("/storage") { showFileBrowser("/storage") })
        bar.addView(button("自动查找") {
            runApiK("files", "查找存储…", { findReadableStorageRoot() }, { found ->
                if (currentScreen == "files") {
                    if (found != null) showFileBrowser(found)
                    else toast("系统限制访问存储，请改用「粘贴文本」或「从网址下载」")
                }
            })
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
                    if (browseLiveImport) {
                        runApi("读取直播列表…", { f.inputStream().use { decodeLiveText(readBounded(it, 4 * 1024 * 1024)) } }, { text ->
                            browseLiveImport = false
                            importLiveText(text, f.name)
                        })
                    } else runApi("读取文件…", { f.readText() }, { text -> importText(text) })
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
        if (requestCode == REQ_PLAYER) {
            if (resultCode == RESULT_OK && data?.getBooleanExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, false) == true) {
                showPlaybackSources(data)
            } else {
                childrenSearchOrigin = null
                cancelJob("playbacksources")
                refreshHomeAfterPlayback = false
                showRecommend()
            }
            return
        }
        if (requestCode == REQ_PICK_LIVE) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            runApi("读取直播列表…", {
                val bytes = contentResolver.openInputStream(uri)?.use { readBounded(it, 4 * 1024 * 1024) }
                    ?: throw IOException("无法读取文件")
                decodeLiveText(bytes)
            }, { text -> importLiveText(text, uri.lastPathSegment?.substringAfterLast('/') ?: "本地直播列表") })
            return
        }
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
        val dialog = AlertDialog.Builder(this)
            .setTitle("播放缓存")
            .setMessage(playbackCacheMessage(PlaybackCache.knownSizeBytes().takeIf { it >= 0 }))
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
            .create()
        dialog.show()
        // 2GB 上限的缓存可能有几万个分片，遍历目录放到后台，算完再刷新文案
        scope.launch {
            val bytes = PlaybackCache.sizeBytesAsync(this@MainActivity)
            if (dialog.isShowing) dialog.setMessage(playbackCacheMessage(bytes))
        }
    }

    /** @param bytes null 表示占用还在后台算 */
    private fun playbackCacheMessage(bytes: Long?): String {
        val head = if (bytes == null) {
            "当前占用计算中…"
        } else {
            String.format(
                java.util.Locale.US,
                "当前占用 %.1f MB\n上限 %d MB，超出后自动淘汰最久未用的片段。",
                bytes / 1024.0 / 1024.0,
                prefs.cacheLimitMb,
            )
        }
        return head + if (PlaybackCache.isInUse()) "\n\n正在播放，退出播放后再清。" else ""
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
        // 播放缓存占用要遍历几万个分片，先用上次算到的值垫着，后台算完再刷新文案
        fun buildMsg(mediaDisk: Long?): String = buildString {
            append("海报：内存 ").append(imgs).append(" 张 · 磁盘 ").append(mb(posterDisk)).append('\n')
            append("播放缓存（磁盘）：")
            append(if (mediaDisk == null) "计算中…" else mb(mediaDisk))
            append(" / 上限 ").append(prefs.cacheLimitMb).append(" MB\n")
            append("观看历史：").append(hist).append(" 条\n")
            append("搜索记录：").append(search).append(" 条\n")
            append("播放进度：").append(progressCount).append(" 条\n\n")
            append("片源配置、直播源和服务器设置不会被清除。")
            if (PlaybackCache.isInUse()) append("\n\n正在播放，播放缓存这次会跳过（退出播放后再清）。")
        }
        val knownMedia = PlaybackCache.knownSizeBytes().takeIf { it >= 0 }
        val dialog = AlertDialog.Builder(this)
            .setTitle("清理缓存")
            .setMessage(buildMsg(knownMedia))
            .setPositiveButton("清理") { _, _ ->
                // 删几 GB 分片在老盒子上要好几秒，放后台做，界面给个"正在清理"
                progress("正在清理…", "clear", cancellable = false)
                scope.launch {
                    // Stop old metadata lookups as well as image downloads before clearing.
                    childrenPrefetchJob?.cancel()
                    childrenCoverJobs.values.toList().forEach { it.cancel() }
                    val n = withContext(Dispatchers.IO) { imageLoader.clearCache() }
                    childrenPreloadedSignature = ""
                    childrenCoverTried.clear()
                    childrenBadCovers.clear()
                    val mediaCleared = withContext(Dispatchers.IO) { PlaybackCache.clear(this@MainActivity) }
                    prefs.clearHistory()
                    prefs.clearSearchHistory()
                    prefs.clearAllProgress()
                    dismissProgress()
                    toast(
                        "已清理：海报 " + n + " 张 + " + mb(posterDisk) +
                            "、历史 " + hist + " 条、搜索 " + search + " 条、进度 " + progressCount + " 条" +
                            if (mediaCleared) "、播放缓存" else "（播放缓存占用中，未清）",
                    )
                    showSetup()
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        scope.launch {
            val bytes = PlaybackCache.sizeBytesAsync(this@MainActivity)
            if (dialog.isShowing) dialog.setMessage(buildMsg(bytes))
        }
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
            "后台播放          ${if (prefs.backgroundPlay) "开（回到桌面后继续播）" else "关（进入后台暂停）"}",
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
                        listOf(512, 1024, 2048, 4096).indexOf(prefs.cacheLimitMb).let { if (it < 0) 2 else it },
                    ) { i ->
                        prefs.cacheLimitMb = listOf(512, 1024, 2048, 4096)[i]
                        scope.launch {
                            val immediate = withContext(Dispatchers.IO) { PlaybackCache.recreate(this@MainActivity) }
                            toast("已设为 ${listOf("512 MB", "1 GB", "2 GB", "4 GB")[i]}" +
                                if (immediate) "，下次播放生效" else "，当前播放结束后生效")
                            if (currentScreen == "playsettings") refresh()
                        }
                    }
                    6 -> chooseOption("后台播放", listOf("开：回到桌面后继续播放", "关：进入后台暂停"),
                        if (prefs.backgroundPlay) 0 else 1
                    ) { i ->
                        prefs.backgroundPlay = (i == 0)
                        if (i == 0) ensureNotificationPermission()
                        toast(if (i == 0) "已开启：回到桌面后继续播放，退出播放页会停止" else "已关闭：进入后台暂停")
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
        col.addView(button("儿童计时管理 · ${ChildrenQuota.label(this)}") { ChildrenQuota.parent(this) { showSetupCompact() } })

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
        row("live", "电视直播", "导入、切换和管理直播源") { liveChannels = emptyList(); liveChannelSource = ""; showLive() }
        row("tv", "播放设置", "续播、画质、缓冲与跳过片头片尾") { showPlaySettings() }
        row("setup", "流畅模式：${if (prefs.smoothUi) "开" else "关"}", "减少页面淡入和焦点放大，保留焦点描边") {
            prefs.smoothUi = !prefs.smoothUi
            showSetupCompact()
        }
        if (BuildConfig.TEST_BUILD) row("history", "界面流畅度统计", "调试版：${if (prefs.uiStatsEnabled) "已开启" else "已关闭"}") {
            AlertDialog.Builder(this).setTitle("界面流畅度统计")
                .setMessage(uiMonitor?.summary() ?: "统计尚未开启")
                .setPositiveButton(if (prefs.uiStatsEnabled) "关闭统计" else "开启统计") { _, _ ->
                    prefs.uiStatsEnabled = !prefs.uiStatsEnabled
                    if (prefs.uiStatsEnabled) startUiMonitor() else uiMonitor?.stop()
                    showSetupCompact()
                }
                .setNegativeButton("关闭", null)
                .setNeutralButton("重置") { _, _ -> uiMonitor?.reset() }.show()
        }
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
            // Do not allow an older boot or login write to race a newer server choice.
            val previousStatus = jobOf("status")
            val previousConnect = jobOf("connect")
            bootRetryJob?.cancel()
            cancelJob("status")
            runApiK("connect", "正在连接…", {
                previousStatus?.join()
                previousConnect?.join()
                val st = candidate.status()
                val verified = if (st.passwordRequired && !st.verified) {
                    candidate.login(effectivePassword).verified
                } else {
                    st.verified
                }
                val saved = if (!st.passwordRequired || verified) {
                    if (coroutineContext[Job]?.isActive == false) throw kotlinx.coroutines.CancellationException()
                    prefs.rememberServer(url, effectivePassword, candidate.currentCookie().orEmpty(), makeCurrent = true)
                } else false
                Triple(st, verified, saved)
            }, { (st, verified, savedSecurely) ->
                if (st.passwordRequired && !verified) {
                    toast("密码未通过验证，请检查后重试")
                    return@runApiK
                }
                if (!savedSecurely) {
                    toast("服务器设置未能安全保存，已保留原来的服务器")
                    return@runApiK
                }
                api = candidate
                // 只换 Api 绑定，保留已下载的海报缓存（重建 loader 会白下一遍图）
                imageLoader.updateApi(candidate)
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
    private fun returnFromSources() {
        when (sourcesOrigin) {
            "setup" -> showSetup()
            "children_empty" -> showChildrenEmpty(lastSearchKeyword, "")
            else -> showRecommend()
        }
    }

    private fun showSources() {
        if (currentScreen !in setOf("sources", "vodsources", "addsource", "import", "qradd")) {
            sourcesOrigin = when (currentScreen) { "setup" -> "setup"; "children_empty" -> "children_empty"; else -> "home" }
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
        col.addView(button("＋ 添加片源 / 导入", primary = true) { showAddSource() })
        category("点播源 ${vodSources.size} 个 · 管理", "已启用 $enabledVodCount 个 · 影视采集站", ::showVodSources)
        category("直播源 ${liveSources.size} 个 · 管理", "IPTV 频道 · 默认使用第一个直播源", ::showLive)
        col.addView(button("返回") { returnFromSources() })
        setScreen(col)
    }

    /** 点播源的详细列表与检测状态。 */
    private val sourceChecked = HashSet<String>()
    private fun showVodSources() {
        currentScreen = "vodsources"
        val sources = prefs.sources()
        val enabledCount = sources.count { it.enabled }
        // 复选框多选：勾选后可批量启用/停用（直播源上线后沿用同一套交互）
        val checked = sourceChecked.apply { retainAll(sources.map { it.url }.toSet()) }

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
        if (!isTvDevice) {
            val actions = (2 until bar.childCount).map { bar.getChildAt(it) as Button }
            bar.removeAllViews()
            val primaryActions = listOf(
                button("返回") { showSources() },
                button("添加片源", primary = true) { showAddSource() },
                button("更多") {
                    AlertDialog.Builder(this).setTitle("片源操作")
                        .setItems(actions.map { it.text.toString() }.toTypedArray()) { _, index -> actions[index].performClick() }
                        .setNegativeButton("取消", null).show()
                },
            )
            primaryActions.forEach { action ->
                action.minimumWidth = 0; action.minWidth = 0
                action.setTextSize(14f)
                action.setPadding(dp(6), dp(10), dp(6), dp(10))
                bar.addView(action, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
            }
            col.addView(bar, LinearLayout.LayoutParams(-1, -2))
        } else col.addView(hScroll(bar))

        // 健康度看板：以前要一行行翻才能知道「到底还有几个源是活的」。
        // 统计限制为两行；操作集中在工具栏，避免窄屏文字把看板撑高。
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
                    setColor(0xFFE5F3ED.toInt()); cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            board.addView(hintText("可用 $ok · 失败 $bad · 未测 $unknown · 停用 ${sources.size - enabledCount}").apply {
                setTextColor(INK); setTextSize(13f); maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2))
            if (isTvDevice && lastAt > 0L) board.addView(hintText("最近检测：" +
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastAt))).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2))
            col.addView(board, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(6) })
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
                if (isTvDevice) {
                    row.addView(name)
                    row.addView(domain)
                } else {
                    val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                    name.ellipsize = android.text.TextUtils.TruncateAt.END
                    domain.setPadding(0, dp(3), dp(8), 0)
                    info.addView(name, LinearLayout.LayoutParams(-1, -2))
                    info.addView(domain, LinearLayout.LayoutParams(-1, -2))
                    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                    status.maxLines = 2
                }
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
            Triple("内置默认片源", "${BuiltInSources.ALL.size} 个内置点播接口，https 源可设备直连", {
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
            "直播源 · 自行导入 M3U / TXT",
            Triple("管理直播源", "第一个直播源默认启用，也可切换备用源", { showLive() }),
            Triple("导入直播源", "选择本地文件，或粘贴列表 / 订阅地址", { addLiveDialog() }),
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

    private val qrImportLock = Any()
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
            synchronized(qrImportLock) {
                val added = prefs.addSources(list)
                val live = api.parseLiveFromExport(text)
                if (live.isNotEmpty()) {
                    val cur = prefs.liveUrls()
                    live.forEach { if (!cur.contains(it)) cur.add(it) }
                    prefs.saveLiveUrls(cur)
                    if (prefs.activeLiveUrl.isEmpty()) prefs.activeLiveUrl = cur.first()
                }
                added
            }
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
                    ensureBuiltInLiveSources(st.defaultLiveSources)
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
    private data class SearchSourceCompletion(
        val source: SourceConfig,
        val result: SearchResult?,
        val direct: Boolean,
    )

    /** 一批搜索进度：已合并的结果 + 进度计数，用来边搜边显示 */
    private data class SearchProgress(
        val items: List<SearchItem>,
        val failures: Int,
        val done: Int,
        val total: Int,
        val retrying: Int = 0,
    )

    /**
     * 搜索结果按网页版的样子展示：封面 + 标题 + 类型/年份/备注 + 来源数。
     * 同一片名收成一张卡片（多来源在详情页「换源」里切）。
     *
     * @param keepSelection 分批合并结果时为 true：沿用上一版网格的选中项，
     *   并把焦点还给网格。慢源每返回一批就重建整页，如果不还原，
     *   用户正在往下翻的时候会被拽回第一条 —— 电视上尤其难受。
     *   （已经在结果页上的增量刷新走 [updateResultsInPlace]，不会重建整页。）
     */
    private fun showResults(keepSelection: Boolean = false) {
        currentScreen = "results"
        val prevGrid = resultsGrid
        // 只有「上一版结果页还挂在窗口上」才值得沿用选中项，否则索引会指到别的列表
        val prevTitle = if (keepSelection && prevGrid?.isAttachedToWindow == true) {
            resultGroups.getOrNull(prevGrid.selectedItemPosition)?.first
        } else {
            null
        }
        val gridHadFocus = keepSelection && prevGrid?.hasFocus() == true
        // 焦点停在工具条按钮上（来源筛选/返回推荐/重新搜索）时，记住它，重建后还回去
        val prevFocusLabel = if (keepSelection && !gridHadFocus) {
            (root.findFocus() as? Button)?.text?.toString()
        } else {
            null
        }
        val visibleCount = applyResultsData()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(20), pagePad(40), dp(16))
        }
        col.addView(topBar())

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val titleView = titleText("${resultGroups.size} 部 · $visibleCount 条结果", 18f)
        resultsTitle = titleView
        bar.addView(titleView)
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
        bar.addView(button(if (childrenSearchOrigin != null) "返回儿童乐园" else "返回推荐") {
            if (childrenSearchOrigin != null) returnToChildrenOrigin() else showRecommend()
        })
        bar.addView(button("重新搜索") { showHome() })
        col.addView(hScroll(bar))
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
            resultGroups.getOrNull(pos)?.let {
                selectedResultTitle = it.first
                openDetail(it.second.first())
            }
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

        resultsGrid = grid
        setScreen(col, focusFirst = !gridHadFocus && prevFocusLabel == null)
        if (prevFocusLabel != null) {
            // 布局完成后再找，此时新工具条才真正建好
            col.post { findButtonByText(col, prevFocusLabel)?.requestFocus() }
        }
        // 布局完成后再定位，否则 GridView 还没测出行高，setSelection 会被忽略
        val keepIdx = prevTitle?.let { t -> resultGroups.indexOfFirst { it.first == t } } ?: -1
        if (keepIdx >= 0 || gridHadFocus) {
            grid.post {
                if (keepIdx >= 0) grid.setSelection(keepIdx)
                if (gridHadFocus) grid.requestFocus()
            }
        }
    }

    /**
     * 重算结果分组（过滤 + 按「第一个源是否已知不可用」排序）。
     * 点进去默认用第一个源，上次测过失败的就往后排，让能看的先露头。
     * @return 过滤后的条目数
     */
    private fun applyResultsData(preserveOrder: Boolean = false): Int {
        val previousOrder = if (preserveOrder) resultGroups.map { it.first } else emptyList()
        val visible = if (sourceFilter.isEmpty()) {
            lastResults
        } else {
            lastResults.filter { it.sourceName == sourceFilter }
        }
        // 先把排序键算出来再排：sortedBy 的比较器每次比较都会重新取一次 key，
        // 而 prefs.sourceTest 每次要读两个 SharedPreferences 键。
        resultGroups = visible
            .groupBy { it.name }
            .map { (title, items) ->
                val bad = prefs.sourceTest(items.first().sourceUrl)?.first == false
                (if (bad) 1 else 0) to (title to items)
            }
            .sortedWith(compareBy({ searchTitleRank(it.second.first, lastSearchKeyword) }, { it.first }))
            .map { it.second }
        resultGroups = keepExistingResultOrder(resultGroups, previousOrder) { it.first }
        return visible.size
    }

    /**
     * 结果页原地刷新：只换数据、改两行文字，不重建工具栏与网格。
     *
     * 慢源每返回一批结果就整页重建的话，滚动位置、焦点、甚至 GridView 的复用池
     * 都会被打断；在结果页上直接用 notifyDataSetChanged 更平滑。
     * 前提是网格还挂在窗口上 —— 用户已经离开就什么都不做。
     */
    private fun updateResultsInPlace(): Boolean {
        val grid = resultsGrid ?: return false
        if (!grid.isAttachedToWindow) return false
        val selected = resultGroups.getOrNull(grid.selectedItemPosition)?.first
        val restoreFocus = grid.hasFocus() && !grid.isInTouchMode
        val visibleCount = applyResultsData(preserveOrder = true)
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        if (restoreFocus && selected != null) grid.post {
            if (currentScreen != "results" || resultsGrid !== grid || !grid.isAttachedToWindow || !grid.hasFocus()) return@post
            val position = searchSelectionToRestore(selected, resultGroups.map { it.first }, grid.isInTouchMode)
            if (position != null) grid.setSelection(position)
        }
        resultsTitle?.text = "${resultGroups.size} 部 · $visibleCount 条结果"
        return true
    }

    /** 按文案在视图树里找按钮：分批合并结果后把焦点还给用户原来停的那个按钮 */
    private fun findButtonByText(v: View, label: String): View? {
        if (v is Button && v.visibility == View.VISIBLE && v.text?.toString() == label) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findButtonByText(v.getChildAt(i), label)?.let { return it }
            }
        }
        return null
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
                val sourceCount = items.distinctBy { it.sourceUrl }.size
                append("${sourceCount} 个来源")
                if (srcs.isNotEmpty()) append(" · ${srcs.joinToString(" / ")}")
                if (bad) {
                    append("   ·⚠ 这个源上次检测失败，长按可换源")
                }
            }
            (info.getChildAt(2) as TextView).setTextColor(
                if (bad) 0xFFFCA5A5.toInt() else 0xFF64748B.toInt(),
            )

            if (poster.tag != first.pic) poster.setImageBitmap(null)
            poster.tag = first.pic
            // 按 ImageView 的实际尺寸取图：以前要 120x170 却只显示 86x120，白解了 2 倍像素
            imageLoader.load(first.pic, dp(86), dp(120), needed = posterSubscription(poster)::get) { bmp ->
                if (poster.tag == first.pic) poster.setImageBitmap(bmp)
            }
            cell.setOnClickListener { openDetail(first) }
            return cell
        }
    }

    /* --------------------------------------------------------------- 搜索页 */

    /* --------------------------------------------------------------- 热门搜索 */

    /** 搜索页的热门分类。动画/动漫的豆瓣榜单不做适龄过滤，儿童向用精选清单。 */
    private data class HotCategory(
        val label: String,
        val doubanType: String?,
        val doubanTag: String?,
    )

    private val hotCategories = listOf(
        HotCategory("热门电影", "movie", "热门"),
        HotCategory("热门电视剧", "tv", "热门"),
        // 儿童向动画只给精选清单：豆瓣的「动画」分类混着全部年龄段，家长要的是「闭眼点都安全」
        HotCategory("热门动画 · 适合小朋友", null, null),
        // 国漫：先给精选（用户点名的几部排最前），豆瓣「国产动画」榜单能拉到就接在后面
        HotCategory("热门动漫", "tv", "国产动画"),
    )

    /** 6 岁上下能看的动画：经典、全龄、无恐怖暴力元素 */
    private val KIDS_ANIMATION = listOf(
        "小猪佩奇", "汪汪队立大功", "超级飞侠", "海底小纵队", "萌鸡小队",
        "超级宝贝JOJO", "熊出没", "猪猪侠", "大耳朵图图", "小马宝莉",
    )

    /** 国漫精选：仙逆 / 凡人修仙传 / 斗破按用户点名顺序排最前 */
    private val DONGHUA_PICKS = listOf(
        "仙逆", "凡人修仙传", "斗破苍穹", "完美世界", "遮天", "斗罗大陆",
        "神印王座", "吞噬星空", "师兄啊师兄", "剑来", "牧神记", "诛仙",
    )

    private fun curatedTitles(label: String): List<String> = when {
        label.startsWith("热门动画") -> KIDS_ANIMATION
        label == "热门动漫" -> DONGHUA_PICKS
        else -> emptyList()
    }

    /** 各分类的热门片名；豆瓣拉不到就用精选清单兜底，保证每一类都有内容 */
    private val hotTitles = linkedMapOf<String, List<String>>()

    /** 各分类的完整榜单（「更多」弹层用）：精选 + 豆瓣整页，不截断到 12 条 */
    private val hotAllTitles = linkedMapOf<String, List<String>>()

    /** 联想词池：片名 → 所属分类，首字母匹配的数据源 */
    private val suggestPool = linkedMapOf<String, String>()

    /**
     * 刚完成一次搜索后置 true：回搜索页时输入框里是刚搜过的词，
     * 但这时应该显示热门榜单而不是把刚搜过的词再联想一遍。
     * 用户一打字就清掉。
     */
    private var homeSuppressSuggest = false

    /** 片名 → 拼音首字母的缓存，输入时不再逐字编码 */
    private val initialsCache = HashMap<String, String>()

    private fun ensureHotSearch() {
        if (hotTitles.size >= hotCategories.size || hotSearchJob?.isActive == true) return
        hotSearchJob = scope.launch {
            // 热门词只用于提示，不该在用户刚打开搜索页时与手动搜索抢网络。
            delay(800)
            if (currentScreen != "home") return@launch
            // 四类并发拉取：谁先回来谁先上屏。原来串行逐类请求，
            // 最后一类要多等前三类的网络时间，「更多」也就跟着慢。
            for (cat in hotCategories) {
                if (hotTitles.containsKey(cat.label)) continue
                launch {
                    val curated = curatedTitles(cat.label)
                    val fetched = if (cat.doubanType == null) {
                        emptyList()
                    } else {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                api.douban(cat.doubanType, cat.doubanTag!!, 0, 25)
                            }.getOrDefault(emptyList())
                        }.map { it.title }.filter { it.isNotBlank() }
                    }
                    // 精选排前面（顺序就是推荐顺序），豆瓣榜单接在后面补充；
                    // 完整列表给「更多」弹层，卡片上只放前 12 条
                    val merged = (curated + fetched).distinct()
                    hotAllTitles[cat.label] = merged
                    hotTitles[cat.label] = merged.take(12)
                    rebuildSuggestPool()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (currentScreen == "home") refreshHotChips()
                    }
                }
            }
        }
    }

    /** 重建联想词池：搜索历史 + 看过的剧 + 各类热门榜单 + 首页推荐 */
    private fun rebuildSuggestPool() {
        suggestPool.clear()
        fun add(title: String, label: String) {
            val t = title.trim()
            if (t.isNotEmpty() && !suggestPool.containsKey(t)) suggestPool[t] = label
        }
        prefs.searchHistory().forEach { add(it, "最近搜索") }
        prefs.history().take(20).forEach { add(it.seriesTitle.ifEmpty { historyTitle(it) }, "看过的") }
        // 用完整榜单（含「更多」弹层里的全部词条）做联想池：能首字母命中的片名更多
        hotAllTitles.forEach { (label, titles) -> titles.forEach { add(it, label) } }
        recommendItems.forEach { add(it.title, "推荐") }
        lastResults.forEach { add(it.name, "搜索结果") }
    }

    /**
     * 输入联想：片名包含关键词，或拼音首字母以关键词开头（2 个字母以上放宽到包含）。
     * 「cjqc」→《超级汽车》、「xn」→《仙逆》就是靠第二条命中的。
     */
    private fun matchSuggestions(q: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((title, label) in suggestPool) {
            val initials = initialsCache.getOrPut(title) { PinyinInitials.initialsOf(title) }
            val hit = title.lowercase().contains(q) ||
                initials.startsWith(q) ||
                (q.length >= 2 && initials.contains(q))
            if (hit) out.add(title to label)
            if (out.size >= 6) break
        }
        return out
    }

    /**
     * 就地刷新热门榜单卡片。数据是后台分批到的，到一批填一批；
     * 不重建整页，正在打字的输入法不会被关掉。
     * 词条带排名序号（前三名暖色高亮），像一份正式榜单而不是一排小标签。
     */
    private fun refreshHotChips() {
        for ((label, row) in hotChipRows) {
            val titles = hotTitles[label].orEmpty()
            val box = hotBoxes[label] ?: continue
            if (titles.isEmpty()) {
                box.visibility = View.GONE
                continue
            }
            box.visibility = View.VISIBLE
            row.removeAllViews()
            titles.forEachIndexed { idx, title ->
                row.addView(rankChip(idx + 1, title) { doSearch(title) })
            }
        }
    }

    /**
     * 「更多」：弹出一类的完整榜单。数据在进搜索页时就已经并发预取并缓存在本地，
     * 点开是纯内存读取、零网络等待；万一还没拉到，先退回精选清单顶上。
     */
    private fun showHotMore(label: String) {
        val titles = hotAllTitles[label].orEmpty()
            .ifEmpty { hotTitles[label].orEmpty() }
            .ifEmpty { curatedTitles(label) }
        if (titles.isEmpty()) {
            toast("榜单还在加载，稍后再点")
            return
        }
        val shown = titles.mapIndexed { i, t -> "${i + 1}. $t" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("$label · 共 ${titles.size} 个")
            .setItems(shown) { _, which -> doSearch(titles[which]) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 搜索页正文的显隐切换：正在打字且有候选 → 显示联想、隐藏榜单和结果；
     * 没在打字（刚搜过、回填的关键词）→ 恢复榜单和结果。
     * 全部是 visibility 切换，不重建视图。
     */
    private fun refreshSearchBody() {
        val box = homeSuggestBox ?: return
        val typing = homeKeyword.isNotBlank() && !homeSuppressSuggest
        val q = if (typing) homeKeyword.trim().lowercase() else ""
        val matches = if (q.isEmpty()) emptyList() else matchSuggestions(q)
        box.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
        if (matches.isNotEmpty()) {
            box.removeAllViews()
            box.addView(hintText("输入首字母或片名，选中直接搜").apply { setPadding(0, dp(4), 0, dp(2)) })
            matches.forEach { (title, label) ->
                box.addView(
                    focusRow("$title　·　$label") { doSearch(title) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)),
                )
            }
        }
        val searching = typing && matches.isNotEmpty()
        // 有候选时把最近搜索、热门榜单都收起来，让位给联想列表；
        // 没在打字时恢复。出过搜索结果的情况下榜单整块隐藏 ——
        // 结果比榜单重要，别把结果挤出屏幕。
        homeHistoryBox?.visibility = if (searching) View.GONE else View.VISIBLE
        homeHotSection?.visibility =
            if (searching || lastResults.isNotEmpty()) View.GONE else View.VISIBLE
        hotBoxes.forEach { (label, v) ->
            v.visibility = if (searching || hotTitles[label].isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        homeResultsList?.visibility = if (searching) View.GONE else View.VISIBLE
    }

    private fun showHome(clearKeyword: Boolean = true) {
        if (clearKeyword) homeKeyword = ""
        currentScreen = "home"
        // 热门词回填、后台维护都会调 showHome() 整页重建，输入框是新建的。
        // 这里记住「重建前焦点是不是在输入框上」，重建后把文字和焦点一起还回去，
        // 否则用户在电视上按了半天的片名会被后台刷新抹掉。
        val keepKeywordFocus = ::root.isInitialized && root.findFocus() is EditText
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(20), pagePad(48), dp(20))
        }

        col.addView(topBar())
        // 同页筛选保留尚未提交的输入。不能直接 setText：布局前控件宽度是 0，
        // 会算出横向滚动偏移、把开头一两个字裁掉 —— field() 里已经处理了这个时序坑。
        val keywordField = field("输入影片名称…", homeKeyword)
        homeKeywordField = keywordField
        keywordField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        keywordField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                // field() 回填的是同一段文字，不算用户输入（也不该清掉 suppress 标记）
                if (text == homeKeyword) return
                homeKeyword = text
                homeSuppressSuggest = false
                // 只就地刷新候选与榜单的显隐，不重建页面 —— 重建会关掉正在打字的输入法
                refreshSearchBody()
            }
        })
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

        // ── 最近搜索：搜过就常驻显示（电视上打字太难，重搜一部片子该是一次点击的事）──
        // 点词条直接重搜；右上角「清空」带确认。整卡在打字联想时收起（refreshSearchBody）。
        val history = prefs.searchHistory()
        if (history.isNotEmpty()) {
            val card = panelCard()
            card.addView(
                cardHeader("最近搜索", "点词条直接重搜", "清空") {
                    AlertDialog.Builder(this)
                        .setTitle("清空搜索历史")
                        .setMessage("确定清空这 ${history.size} 条记录？")
                        .setPositiveButton("清空") { _, _ ->
                            prefs.clearSearchHistory()
                            showHome(clearKeyword = false)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                },
            )
            val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            history.forEach { kw -> chipRow.addView(chip(kw) { doSearch(kw) }) }
            card.addView(hScroll(chipRow).apply { setPadding(0, dp(6), 0, 0) })
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    card,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) },
                )
            }
            homeHistoryBox = box
            col.addView(box)
        } else {
            homeHistoryBox = null
        }

        // ── 热门榜单：四类（电影 / 电视剧 / 儿童动画 / 国漫）各一张正式卡片 ──
        // 卡片 = 白底圆角 + 分类标题（竖色条 + 加粗 + 数据来源小注）+ 带排名的词条行。
        // 数据到了就地填词条，不重建整页 —— 重建会把正在打字的输入法关掉。
        // 出过搜索结果时整块隐藏（refreshSearchBody），把屏幕留给结果列表。
        ensureHotSearch()
        val hotSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (cat in hotCategories) {
            val note = when {
                cat.doubanType == null -> "精选片单 · 点词条直接搜"
                cat.doubanTag == "国产动画" -> "精选 + 豆瓣国产动画"
                else -> "豆瓣实时榜单"
            }
            val card = panelCard()
            // 「更多」弹完整榜单：数据早已预取缓存，点开即现，不用打字
            card.addView(cardHeader(cat.label, note, "更多") { showHotMore(cat.label) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            card.addView(hScroll(row).apply { setPadding(0, dp(6), 0, 0) })
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // 数据没到之前先藏着，refreshHotChips 填上词条再亮出来
                visibility = View.GONE
                addView(
                    card,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) },
                )
            }
            hotChipRows[cat.label] = row
            hotBoxes[cat.label] = box
            hotSection.addView(box)
        }
        hotSection.visibility = if (lastResults.isEmpty()) View.VISIBLE else View.GONE
        homeHotSection = hotSection
        col.addView(hotSection)

        // 输入联想：打字（含拼音首字母，如 cjqc → 超级汽车）时显示候选，
        // 遥控器上下移到候选行、OK 直接搜。有候选时隐藏热门榜单和结果列表。
        val suggestBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        homeSuggestBox = suggestBox
        col.addView(suggestBox)
        rebuildSuggestPool()
        refreshHotChips()

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val allSources = prefs.sources()
        statusRow.addView(
            TextView(this).apply {
                setText(
                    // 搜索只打启用源，这里就说启用数，免得「片源 20 个」和实际搜的 15 个对不上
                    "启用片源 ${allSources.count { it.enabled }}/${allSources.size} · 服务器 ${api.baseUrl}" +
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
                            showHome(clearKeyword = false)
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
                    showHome(clearKeyword = false)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(46),
                ).apply { leftMargin = dp(8) },
            )
        }
        col.addView(statusRow)

        // 最近搜索已上提为输入行下方的常驻卡片；这里没有结果时留一句引导即可。
        val listView = newList()
        homeResultsList = listView
        if (lastResults.isEmpty()) {
            col.addView(
                hintText("在上方输入片名开搜，或直接点热门榜单里的词条"),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
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

        // 结果列表在本方法后面才创建，这里最后再同步一次联想/榜单的显隐
        refreshSearchBody()

        setScreen(col)
        // 重建前焦点在输入框上就还给输入框（光标位置由 field() 回填文字时一并处理）
        if (clearKeyword || keepKeywordFocus || lastResults.isEmpty()) {
            keywordField.requestFocus()
        } else {
            listView.requestFocus()
        }
    }

    private fun doSearch(keyword: String) {
        if (currentScreen == "children") childrenOverviewScrollY = childrenOverviewScroll?.scrollY ?: childrenOverviewScrollY
        childrenSearchOrigin = when (currentScreen) {
            "children" -> -1
            "children_more" -> childrenSectionIndex
            else -> null
        }
        homeMaintenanceJob?.cancel()
        favoriteCheckJob?.cancel()
        hotSearchJob?.cancel()
        val kw = keyword.trim()
        if (kw.isEmpty()) { toast("请输入片名"); return }
        // 只搜「已启用」的源：停用的源不该再被请求，也不该出现在缓存键里
        val enabledSources = prefs.sources().filter { it.enabled }
        if (enabledSources.isEmpty()) {
            lastSearchKeyword = kw
            if (childrenSearchOrigin != null) showChildrenEmpty(kw, "当前没有启用的片源。")
            else { toast("没有启用的片源，去「片源管理」开启或添加"); showSources() }
            return
        }
        // 全部启用源一起搜索；优先展示已完成源的结果，慢源在后台继续补齐。
        val sources = enabledSources
        searchConnections?.cancel()
        val requestConnections = SearchConnections().also { searchConnections = it }
        val token = ++searchToken
        lastSearchKeyword = kw
        // 搜索词只保存在最近搜索；提交和重新进入搜索页时输入框保持空白。
        prefs.addSearchHistory(kw)
        homeKeyword = ""
        homeKeywordField?.takeIf { it.isAttachedToWindow }?.let { field ->
            field.setText("")
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(field.windowToken, 0)
        }
        homeSuppressSuggest = true
        if (currentScreen == "home") refreshSearchBody()
        searchInProgress = true
        sourceFilter = ""
        // 记下发起搜索时所在的界面世代：只有「还停在这个页面」或「本次搜索已经画出的结果页」
        // 才允许改界面，用户中途去了别处就不再回跳（哪怕后来又回到同一个页面）。
        searchOriginEpoch = screenEpoch
        searchResultsEpoch = -1
        // 搜一次要服务端把每个采集站都问一遍，十几秒很正常。
        // 刚搜过的关键词（3 分钟内、源的集合没变）直接复用结果 —— 看完一部点返回再搜同一部是常见动作。
        val cacheKey = prefs.serverUrl + "|" + kw + "|" + sources.joinToString(",") { it.url }
        searchCache[cacheKey]?.let { (at, items) ->
            if (System.currentTimeMillis() - at < SEARCH_CACHE_MS) {
                prefs.addSearchHistory(kw)
                lastResults = items
                searchInProgress = false
                if (items.isEmpty() && childrenSearchOrigin != null) showChildrenEmpty(kw, "") else showResults()
                return
            }
        }
        // 每来一批结果就重建整页会打断正在翻页的用户：这里合并刷新，最多 ~2.5 次/秒。
        // 首批结果不受节流影响（lastRenderAt 从 0 开始），保证「有结果就立刻显示」。
        var lastRenderAt = 0L
        val onPartial: (SearchProgress) -> Unit = { progress ->
            // 回调来自工作线程，回到主线程再动界面
            scope.launch {
                if (token != searchToken) return@launch
                if (progress.items.isEmpty()) return@launch
                if (screenEpoch != searchOriginEpoch && screenEpoch != searchResultsEpoch) return@launch
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastRenderAt < PARTIAL_RENDER_INTERVAL_MS) return@launch
                lastRenderAt = now
                lastResults = progress.items
                searchInProgress = true
                // 已经在结果页上就只刷数据；还没进结果页（首批结果）才建整页
                if (!updateResultsInPlace()) showResults(keepSelection = true)
                // showResults 可能刚把页面从「首页」切成「结果页」，世代已经推进，这里跟上
                searchResultsEpoch = screenEpoch
            }
        }
        runApiK("search", null, {
            val first = directThenServerSearch(
                kw, sources, requests = requestConnections, shouldContinue = { token == searchToken }, onPartial = onPartial,
            )
            // 兜底：豆瓣条目常带「（2023）」「：副标题」这类噪声，采集站匹配不到时再试一次干净片名
            if (first.items.isNotEmpty()) {
                first
            } else {
                val cleaned = cleanKeyword(kw)
                if (cleaned.isNotEmpty() && cleaned != kw) {
                    directThenServerSearch(
                        cleaned, sources, requests = requestConnections, shouldContinue = { token == searchToken }, onPartial = onPartial,
                    )
                } else {
                    first
                }
            }
        }, { result ->
            if (token != searchToken) return@runApiK
            searchInProgress = false
            prefs.addSearchHistory(kw)
            if (shouldCacheSearchResult(result.items.size, result.failures)) {
                searchCache[cacheKey] = System.currentTimeMillis() to result.items
            }
            if (searchCache.size > 20) {
                // 只留最近 20 条，按时间清掉最旧的
                searchCache.entries.sortedBy { it.value.first }.take(searchCache.size - 20)
                    .forEach { searchCache.remove(it.key) }
            }
            lastResults = result.items
            // 用户已经离开搜索/结果页（去了设置、详情、推荐…）：结果留着，但不要把人拽回来
            if (screenEpoch != searchOriginEpoch && screenEpoch != searchResultsEpoch) return@runApiK
            val failedNote = if (result.failures > 0) "（${result.failures} 个源无响应）" else ""
            if (result.items.isEmpty()) {
                if (childrenSearchOrigin != null) showChildrenEmpty(kw, failedNote)
                else {
                    showHome(clearKeyword = false)
                    val message = if (result.failures >= sources.size) "片源暂时无响应，请检查网络或片源。"
                        else if (result.failures > 0) "暂未找到结果；部分片源无响应。"
                        else "没有找到匹配的作品，可以换个片名再试。"
                    AlertDialog.Builder(this).setTitle("搜索未找到结果").setMessage(message)
                        .setPositiveButton("重试") { _, _ -> doSearch(kw) }
                        .setNeutralButton("片源管理") { _, _ -> showSources() }
                        .setNegativeButton("返回", null).show()
                }
            } else {
                // 已经停在结果页就原地收尾，不重建整页（会打断滚动与焦点）
                if (!updateResultsInPlace()) showResults()
            }
        }, onError = {
            if (token != searchToken) return@runApiK
            searchInProgress = false
            if (currentScreen == "results") showResults()
        })
    }

    /**
     * 对全部启用源并发查询：标准 HTTPS 苹果 CMS 从设备直连，其余源逐个交给服务端；
     * 直连失败或无结果的源再向服务端补查。每个源完成就回传一次合并结果，
     * 慢源不会挡住先返回的结果。
     *
     * 直连和服务端各用**独立的线程池**，不再靠信号量限流：
     * 以前服务端请求占满线程后，直连任务只能排在后面等，直连本来是快路径，
     * 结果被慢路径堵住；拆成两个池之后两边互不影响。
     *
     * @param shouldContinue 取消探测。搜索被取消、或被新的搜索顶掉时返回 false，
     *   这里立刻停止等待剩下的源 —— HTTP 请求本身打断不了，但界面不用陪着等。
     */
    private fun directThenServerSearch(
        kw: String,
        sources: List<SourceConfig>,
        requests: SearchConnections = SearchConnections(),
        shouldContinue: () -> Boolean = { true },
        onPartial: (SearchProgress) -> Unit = {},
    ): SearchResult {
        if (sources.isEmpty()) return SearchResult(emptyList(), 0)
        val total = sources.size
        val directable = sources.filter { DirectCms.shouldTryDirect(it) }
        val items = ArrayList<SearchItem>()
        val failed = ArrayList<SourceConfig>()
        var done = 0
        var failures = 0
        fun snapshot() = SearchResult(items.distinctBy { it.sourceUrl + "|" + it.vodId }, failures)
        fun emit(retrying: Int = 0) =
            onPartial(SearchProgress(snapshot().items, failures, done, total, retrying))

        // 完成的任务都往同一个队列里放，主循环只管按「谁先完成谁先算」取，
        // 这样两个线程池的结果能合并成一条时间线。
        val finished = java.util.concurrent.LinkedBlockingQueue<SearchSourceCompletion>()
        fun awaitCompletion(): SearchSourceCompletion? {
            while (shouldContinue() && !Thread.currentThread().isInterrupted) {
                val result = try {
                    finished.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
                if (result != null) return result
            }
            return null
        }
        val directPool = java.util.concurrent.Executors.newFixedThreadPool(
            minOf(directable.size, MAX_SEARCH_CONCURRENCY).coerceAtLeast(1),
        ) { r -> Thread(r, "search-direct").apply { isDaemon = true } }
        // 服务端请求都落在同一台机器上，8 路同时打过去容易被限流、也互相拖慢，限到 4 路。
        // 池大小固定 4（而不是按「必须走服务端的源数」算）：直连失败的源还要回退到这里补查，
        // 按前者算的话，20 个源全部直连失败时会退化成单线程串行。
        val serverPool = java.util.concurrent.Executors.newFixedThreadPool(
            SERVER_SEARCH_CONCURRENCY,
        ) { r -> Thread(r, "search-server").apply { isDaemon = true } }

        fun submit(source: SourceConfig, direct: Boolean) {
            val task = Runnable {
                val result = runCatching { requests.run {
                    if (direct) SearchResult(DirectCms.search(source, kw), 0)
                    else serverSearch(kw, source)
                } }.getOrNull()
                finished.offer(SearchSourceCompletion(source, result, direct))
            }
            if (direct) directPool.execute(task) else serverPool.execute(task)
        }
        try {
            sources.forEach { submit(it, it in directable) }
            for (i in 0 until total) {
                if (!shouldContinue()) return snapshot()
                val completion = awaitCompletion() ?: return snapshot()
                done++
                val result = completion.result
                if (result == null) {
                    // 直连失败留到下面回退服务端；服务端自己也失败才算真的失败
                    if (completion.direct) failed.add(completion.source) else failures++
                } else {
                    failures += result.failures
                    if (result.items.isNotEmpty()) {
                        items.addAll(result.items)
                    } else if (completion.direct) {
                        // 直连「成功但没结果」多半是采集站对直连限流/降级，交给服务端再问一次
                        failed.add(completion.source)
                    }
                }
                emit()
            }
            // 直连失败或无结果的源再回退服务端，仍保持并发。
            if (failed.isNotEmpty()) {
                val pending = failed.size
                failed.forEach { source ->
                    serverPool.execute(Runnable {
                        val result = runCatching { requests.run { serverSearch(kw, source) } }.getOrNull()
                        finished.offer(SearchSourceCompletion(source, result, true))
                    })
                }
                for (i in 0 until pending) {
                    if (!shouldContinue()) return snapshot()
                    val completion = awaitCompletion() ?: return snapshot()
                    val result = completion.result
                    if (result == null) {
                        failures++
                    } else {
                        failures += result.failures
                        if (result.items.isNotEmpty()) items.addAll(result.items)
                    }
                    emit(retrying = pending - i - 1)
                }
            }
            return snapshot()
        } finally {
            directPool.shutdownNow()
            serverPool.shutdownNow()
        }
    }

    /**
     * 每源单独打服务端 `/api/search`。
     *
     * 接口本来就收 `sources` 数组（服务端按数组逐个采集站串行请求），
     * 只传一个元素同样合法，返回结构不变 —— 所以单源请求是兼容的，
     * 只是把「服务端串行」拆成了「客户端并发 + 服务端各自串行」。
     *
     * 超时按单源收紧到 15 秒：45 秒是「一次问十几个站」的余量，
     * 单源用这个值会让一个装死的源白占工作线程一分多钟。
     */
    private fun serverSearch(kw: String, source: SourceConfig): SearchResult =
        api.search(kw, listOf(source), readTimeoutMs = SEARCH_SOURCE_TIMEOUT_MS)

    /** 详情：能直连就直连，拿不到再回退服务端（行为与服务端 /api/detail 对齐） */
    private fun directDetailOrServer(vodId: String, source: SourceConfig): Detail {
        if (DirectCms.shouldTryDirect(source)) {
            val d = runCatching { DirectCms.detail(source, vodId) }.getOrNull()
            if (d != null && d.episodes.isNotEmpty()) return d
        }
        return api.detail(vodId, source)
    }

    /* --------------------------------------------------------------- 详情页 */

    /** 从播放器返回时展示这部视频的来源，并补查其他已启用片源。 */
    private fun showPlaybackSources(result: Intent) {
        pendingSourceEpisode = result.getIntExtra(PlayerActivity.EXTRA_INDEX, -1)
        pendingSourcePosition = result.getLongExtra(PlayerActivity.EXTRA_RETURN_POSITION, 0L)
        pendingSourceUrl = result.getStringExtra(PlayerActivity.EXTRA_SOURCE_URL).orEmpty()
        val title = seriesNameOf(result.getStringExtra(PlayerActivity.EXTRA_SERIES_NAME).orEmpty())
        pendingSourceTitle = title
        if (title.isEmpty()) { showRecommend(); return }
        val sourceUrl = result.getStringExtra(PlayerActivity.EXTRA_SOURCE_URL).orEmpty()
        val sourceName = result.getStringExtra(PlayerActivity.EXTRA_SOURCE_NAME).orEmpty()
        val vodId = result.getStringExtra(PlayerActivity.EXTRA_VOD_ID).orEmpty()
        if (currentScreen == "live" && sourceUrl.isEmpty() && vodId.isEmpty()) {
            showLive()
            return
        }
        val cover = result.getStringExtra(PlayerActivity.EXTRA_COVER).orEmpty()
        val current = sourceUrl.takeIf { it.isNotEmpty() }?.let {
            SearchItem(sourceName, vodId, title, cover, "", "", "", it)
        }
        val normalized = sourceTitleKey(title)
        fun isSameTitle(item: SearchItem) = sourceTitleKey(item.name) == normalized
        val known = lastResults.filter(::isSameTitle)
        val initial = (listOfNotNull(current) + known).filter { it.sourceUrl.isNotEmpty() }
            .distinctBy { it.sourceUrl }

        var sourceRows: LinearLayout? = null
        var sourceStatus: TextView? = null
        var sourceScroll: ScrollView? = null
        fun render(items: List<SearchItem>, status: String) {
            if (currentScreen != "playbacksources") return
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pagePad(40), dp(20), pagePad(40), dp(20))
            }
            col.addView(topBar())
            col.addView(titleText("《$title》 · 选择播放源", 21f))
            val statusView = hintText(status).apply { setPadding(0, dp(8), 0, dp(12)) }
            val oldFocus = currentFocus?.tag as? String
            val oldScroll = sourceScroll?.scrollY ?: 0
            val rows = sourceRows ?: LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val oldButtons = (0 until rows.childCount).mapNotNull { rows.getChildAt(it) as? Button }.associateBy { it.tag as? String }
            rows.removeAllViews()
            col.addView(statusView)
            items.forEach { item ->
                val name = item.sourceName.ifEmpty {
                    item.sourceUrl.substringAfter("://").substringBefore('/').ifEmpty { "未知来源" }
                }
                val isCurrent = item.sourceUrl == sourceUrl
                val tested = prefs.sourceTest(item.sourceUrl)
                val health = when {
                    tested == null -> "未测速"
                    tested.first -> "上次测速 ${tested.second} ms"
                    else -> "上次测速失败"
                }
                val count = detailCache[item.vodId + "|" + item.sourceUrl]?.second?.episodes?.size
                val label = (if (isCurrent) "● 当前 · " else "") + name + " · " + health + (count?.let { " · $it 集" } ?: "")
                if (item.vodId.isNotEmpty()) {
                    val sourceButton = oldButtons[item.sourceUrl] ?: button(label, primary = isCurrent) { }
                    sourceButton.text = label
                    sourceButton.tag = item.sourceUrl
                    sourceButton.setOnClickListener { cancelJob("playbacksources"); openDetail(item) }
                    rows.addView(sourceButton.apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                    })
                } else {
                    rows.addView(hintText("$label（当前来源暂无详情）"))
                }
            }
            if (items.isEmpty()) rows.addView(hintText("暂无可选择的来源，请稍后重试。"))
            if (sourceRows != null) {
                sourceStatus?.text = status
                sourceScroll?.post {
                    sourceScroll?.scrollTo(0, oldScroll)
                    if (isTvDevice && oldFocus != null) (0 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { it.tag == oldFocus }?.requestFocus()
                }
                return
            }
            sourceRows = rows
            sourceStatus = statusView
            val scroller = ScrollView(this).apply { addView(rows) }
            sourceScroll = scroller
            col.addView(scroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            setScreen(col)
        }

        currentScreen = "playbacksources"
        lastResults = initial
        render(initial, "正在查找本片的其他来源…")
        val enabledSources = prefs.sources().filter { it.enabled }
        if (enabledSources.isEmpty()) {
            render(initial, "已知来源 ${initial.size} 个；当前没有启用的片源。")
            return
        }
        cancelJob("playbacksources")
        jobs["playbacksources"] = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    // 用户离开这个页面（job 被取消）就立刻停止等待剩下的源
                    val job = coroutineContext[Job]
                    val connections = SearchConnections()
                    try {
                        directThenServerSearch(sourceSearchKeyword(title), enabledSources, requests = connections,
                            shouldContinue = { job?.isActive != false })
                    } finally {
                        connections.cancel()
                    }
                }
                if (currentScreen != "playbacksources") return@launch
                val matches = found.items.filter(::isSameTitle)
                val merged = (initial.map { item ->
                    if (item.vodId.isEmpty()) matches.firstOrNull { it.sourceUrl == item.sourceUrl } ?: item else item
                } + matches).filter { it.sourceUrl.isNotEmpty() }.distinctBy { it.sourceUrl }
                lastResults = merged
                render(merged, "找到 ${merged.size} 个来源，选择一个查看集数。")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                render(initial, "其他来源暂时查找失败，下面是已知来源。")
            }
        }
    }

    /**
     * 打开详情。
     *
     * 以前源挂了就只弹一个错误框，用户没有任何"换一个源试试"的入口 ——
     * 而搜索结果按片名合并成一张卡片，卡片上只记着第一个源，等于死路。
     * 现在失败时把同片名的其他源列出来让用户点。
     */
    private fun openDetail(item: SearchItem) {
        if (currentScreen == "results") selectedResultTitle = item.name
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
        if (childrenSearchOrigin != null && lastResults.isNotEmpty()) {
            col.addView(button("← 返回搜索结果") { showResultsFromDetail() })
        }
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
        val quotaTitle = detail.info.title.ifEmpty { item.name }
        col.addView(button(if (ChildrenQuota.isChild(this, quotaTitle)) "儿童计时管理" else "加入儿童计时") {
            if (ChildrenQuota.isChild(this, quotaTitle)) ChildrenQuota.parent(this) {} else addToChildrenQuota(quotaTitle)
        })
        if (compact) {
            if (detail.info.actor.isNotEmpty()) addPreview("主演：${detail.info.actor}", "主演", 1, extraInfo)
            if (detail.info.desc.isNotEmpty()) addPreview(stripHtml(detail.info.desc), "简介", 1, extraInfo)
        }

        val coverUrl = detail.info.cover.ifEmpty { item.pic }
        if (coverUrl.isNotEmpty()) {
            imageLoader.load(coverUrl, dp(300), dp(440), onPreview = { cover.setImageBitmap(it) }) { bmp -> bmp?.let { cover.setImageBitmap(it) } }
        }

        col.addView(hintText("选择集数（共 ${detail.episodes.size} 集）："))

        // 换源：同一个片名在其他采集站的结果，一键切换（按来源名去重，避免同一站出现两次）
        // 以前这里 take(6) 只显示前 6 个来源，7 个以上就再也切不过去了，现在全列出来（外面有横滑容器）
        val alternatives = lastResults
            .filter { it.name == item.name }
            .distinctBy { it.sourceUrl }
        val sourceHeader = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourceHeader.addView(hintText("当前来源：${item.sourceName.ifEmpty { "未知" }}  ·  ${alternatives.size.coerceAtLeast(1)} 个来源"),
            if (compact) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        sourceHeader.addView(button("切换来源") {
            if (alternatives.size <= 1) toast("当前只找到一个来源")
            else AlertDialog.Builder(this).setTitle("选择来源")
                .setItems(alternatives.map { (if (it.sourceUrl == item.sourceUrl) "● " else "") + it.sourceName.ifEmpty { "来源" } }.toTypedArray()) { _, i ->
                    if (alternatives[i].sourceUrl != item.sourceUrl) openDetail(alternatives[i])
                }.setNegativeButton("取消", null).show()
        })
        col.addView(sourceHeader)
        if (alternatives.size > 1 && !compact) {
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

        val transferIndex = pendingSourceEpisode
        if (pendingSourceUrl.isNotEmpty() && pendingSourceUrl != item.sourceUrl && transferIndex >= 0 &&
            sourceTitleKey(pendingSourceTitle) == sourceTitleKey(item.name)) {
            pendingSourceEpisode = -1
            pendingSourceUrl = ""
            if (transferIndex < detail.episodes.size) {
                AlertDialog.Builder(this).setTitle("继续观看第 ${transferIndex + 1} 集？")
                    .setMessage("不同来源的集数和时长可能不一致，请确认对应集数。")
                    .setPositiveButton("继续观看") { _, _ ->
                        if (pendingSourcePosition > 5_000L) prefs.saveProgress(detail.episodes[transferIndex], pendingSourcePosition)
                        startEpisode(transferIndex)
                    }.setNegativeButton("手动选集", null).show()
            } else toast("新来源集数不足，请手动选集")
        }

        // 续播：全项目以前从来没读过 progressOf()，进详情页永远从第 1 集开始，
        // 1200 集的剧每次都要手动翻回去。这里一次性把「看过哪几集」算出来。
        val marked = prefs.resumeThresholdSeconds * 1000L
        // 两份记录都看：按地址的 + 按「剧名+集号」的（换源之后地址变了，后者才认得出来）
        val progressKey = detail.info.title.ifEmpty { item.name }
        val watched = prefs.watchedEpisodes(progressKey, detail.episodes, marked)
        val recent = prefs.history().firstOrNull { normalizeSeries(it.seriesTitle.ifEmpty { historyTitle(it) }) == normalizeSeries(progressKey) }
        val recentIndex = recent?.let { entry -> detail.episodes.indexOf(entry.playUrl).takeIf { it >= 0 } ?: entry.episodeIndex }
        val lastIdx = if (prefs.resumeEnabled) resumeEpisodeIndex(recentIndex, watched) else -1
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
            col.addView(hintText("看过 $watchedCount / ${detail.episodes.size} 集"))
        }

        // 收藏：只记「加入时这部剧共几集」，之后集数变多就说明更新了（纯本地比对）
        val faved = prefs.isFavorite(progressKey)
        col.addView(
            button(if (faved) "✓ 已收藏" else "＋ 加入收藏") {
                if (prefs.isFavorite(progressKey)) {
                    prefs.removeFavorite(progressKey)
                    toast("已取消收藏")
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
                    toast("已加入收藏，有新集会提示")
                }
                (findButtonByText(col, "✓ 已收藏") ?: findButtonByText(col, "＋ 加入收藏"))?.let { (it as Button).text = if (prefs.isFavorite(progressKey)) "✓ 已收藏" else "＋ 加入收藏" }
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
                    watched[position] -> "第 ${position + 1} 集 · 看过"
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

        val detailsRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.removeView(epGrid)
        val headerScroll = ScrollView(this).apply { addView(col) }
        detailsRoot.addView(headerScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        detailsRoot.addView(epGrid, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreen(detailsRoot)
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
        val child = currentScreen == "children" || currentScreen == "children_more" || childrenSearchOrigin != null || ChildrenQuota.isChild(this, seriesName.ifEmpty { title })
        if (child) {
            ChildrenQuota.mark(this, seriesName.ifEmpty { title })
            if (ChildrenQuota.remaining(this) <= 0L) { ChildrenQuota.locked(this) { playFromList(urls, index, title, cover, seriesName, vodId, sourceUrl, sourceName) }; return }
        }
        refreshHomeAfterPlayback = true
        startActivityForResult(Intent(this, PlayerActivity::class.java).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(urls))
            putExtra(PlayerActivity.EXTRA_INDEX, index.coerceIn(0, urls.size - 1))
            putExtra("children_daily_content", child)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_COVER, cover)
            // 选集回查用：只有这几项齐了，播放器才能从 1 集恢复成整季
            putExtra(PlayerActivity.EXTRA_SERIES_NAME, seriesName)
            putExtra(PlayerActivity.EXTRA_VOD_ID, vodId)
            putExtra(PlayerActivity.EXTRA_SOURCE_URL, sourceUrl)
            putExtra(PlayerActivity.EXTRA_SOURCE_NAME, sourceName)
            if (child) {
                putExtra(PlayerActivity.EXTRA_CHILD_TIMER, prefs.childrenTimerMinutes)
            }
        }, REQ_PLAYER)
    }

    /* ------------------------------------------------------------ 简易适配器 */

    /* --------------------------------------------------------------- 直播页 */

    private fun openLiveFromHome() {
        ensureBuiltInLiveSources()
        val active = prefs.activeLiveUrl
        if (active.isBlank()) {
            toast("请先到 设置 → 电视直播 导入直播源")
            return
        }
        fun start(channels: List<Channel>) {
            if (channels.isEmpty()) { toast("没有可播放频道，请到设置中的电视直播检查直播源"); return }
            val ordered = sortedLiveChannels(channels)
            val index = defaultLiveChannelIndex(ordered)
            if (!isCctvOne(ordered[index].name)) toast("列表中未找到 CCTV1，将播放第一个频道")
            launchLivePlayer(ordered, index)
        }
        if (liveChannelSource == active && liveChannels.isNotEmpty()) { start(liveChannels); return }
        runApiK("live", "正在打开电视直播…", {
            if (active.startsWith("local-live:")) prefs.localLiveChannels(active) else api.liveChannels(active)
        }, { channels ->
            liveChannels = channels; liveChannelSource = active
            start(channels)
        })
    }
    private fun launchLivePlayer(channels: List<Channel>, index: Int) {
        if (channels.isEmpty()) return
        val json = org.json.JSONArray()
        channels.forEach { json.put(org.json.JSONObject().put("name", it.name).put("url", it.url).put("group", it.group).put("headers", org.json.JSONObject(it.headers))) }
        // Keep large IPTV lists out of Binder's activity transaction size limit.
        runApiK("live-session", "正在打开频道…", {
            val file = File(cacheDir, "live-session-${java.util.UUID.randomUUID()}.json")
            file.writeText(json.toString(), Charsets.UTF_8)
            file.name
        }, { name ->
            startActivityForResult(Intent(this, LivePlayerActivity::class.java).apply {
                putExtra("playlist_file", name)
                putExtra("channel_index", index)
            }, REQ_PLAYER)
        })
    }

    private fun showLive() {
        ensureBuiltInLiveSources()
        currentScreen = "live"
        val urls = prefs.liveUrls()
        if (urls.isEmpty()) {
            showLiveImportPage()
            return
        }
        val active = prefs.activeLiveUrl.takeIf { it in urls } ?: urls.first()
        if (liveChannelSource != active) {
            runApiK("live", "正在加载频道…", {
                if (active.startsWith("local-live:")) prefs.localLiveChannels(active) else api.liveChannels(active)
            }, { list ->
                liveChannels = list
                liveChannelSource = active
                if (list.isEmpty()) toast("没解析到频道，可重新导入或切换直播源")
                showLive()
            }, onError = { error ->
                toast("读取直播源失败：${describe(error)}")
                showLiveImportPage()
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
        bar.addView(button("导入直播源") { addLiveDialog() })
        bar.addView(button("删除当前源") {
            AlertDialog.Builder(this).setTitle("删除当前直播源？")
                .setMessage(prefs.liveSourceLabel(active))
                .setPositiveButton("删除") { _, _ ->
                    prefs.removeLiveSource(active)
                    prefs.activeLiveUrl = ""
                    liveChannels = emptyList(); liveChannelSource = ""
                    showLive()
                }.setNegativeButton("取消", null).show()
        })
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

        val allOrdered = sortedLiveChannels(liveChannels)
        val sorted = allOrdered.filter { liveGroupFilter.isEmpty() || it.group == liveGroupFilter }
        val listView = newList()
        bindList(listView,
            sorted.map { "${it.group}  ·  ${it.name}" },
            onItem = { idx -> launchLivePlayer(allOrdered, allOrdered.indexOf(sorted[idx])) },
        )
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
        listView.requestFocus()
    }

    private fun pickLiveSource() {
        val urls = prefs.liveUrls()
        if (urls.isEmpty()) {
            showLiveImportPage()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择直播源")
            .setItems(urls.map(prefs::liveSourceLabel).toTypedArray()) { _, which ->
                prefs.activeLiveUrl = urls[which]
                liveChannels = emptyList()
                liveChannelSource = ""
                liveGroupFilter = ""
                showLive()
            }
            .setPositiveButton("添加新的", { _, _ -> addLiveDialog() })
            .show()
    }

    private fun showLiveImportPage() {
        currentScreen = "live"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(48), dp(24), pagePad(48), dp(20))
        }
        col.addView(topBar())
        col.addView(titleText("电视直播", 24f))
        col.addView(hintText(if (prefs.liveUrls().isEmpty())
            "尚未导入直播源。支持 M3U 和频道名,地址格式的 TXT 列表；支持 HTTP / HTTPS 频道地址。"
            else "当前源未能加载，可选择已有源，或重新导入 M3U / TXT 列表。"))
        if (prefs.liveUrls().isNotEmpty()) col.addView(button("选择已有直播源") { pickLiveSource() })
        col.addView(button("从本地文件导入") { pickLiveFile() })
        col.addView(button("粘贴直播源") { pasteLiveSource() })
        setScreen(col)
    }

    private fun addLiveDialog() {
        AlertDialog.Builder(this).setTitle("导入直播源")
            .setItems(arrayOf("从本地文件导入（M3U / TXT）", "粘贴列表或订阅地址")) { _, which ->
                if (which == 0) pickLiveFile() else pasteLiveSource()
            }.setNegativeButton("取消", null).show()
    }
    private fun pickLiveFile() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_PICK_LIVE)
        } catch (_: Exception) {
            toast("系统文件选择器不可用，尝试内置文件浏览器")
            browseLiveImport = true
            showFileBrowser("/storage")
        }
    }
    private fun pasteLiveSource() {
        inputPage("粘贴直播源", "粘贴 M3U / TXT 列表内容，或一个 HTTP / HTTPS 订阅地址", multiline = true, backTo = { showLive() }) { text ->
            importLiveText(text, "粘贴的直播列表", allowSubscription = true)
        }
        currentScreen = "live_import"
    }
    private fun decodeLiveText(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if ('\uFFFD' in utf8) bytes.toString(java.nio.charset.Charset.forName("GB18030")) else utf8
    }
    private fun importLiveText(text: String, name: String, allowSubscription: Boolean = false) {
        val value = text.trim().removePrefix("\uFEFF")
        if (value.length > 4 * 1024 * 1024) { toast("列表过大，请控制在 4MB 以内"); return }
        if (allowSubscription && '\n' !in value && '\r' !in value &&
            (value.startsWith("https://", true) || value.startsWith("http://", true))) {
            prefs.saveLiveUrls((prefs.liveUrls() + value).distinct())
            prefs.activeLiveUrl = value
        } else {
            runApiK("live-import", "正在导入直播列表…", {
                val report = inspectLocalLivePlaylist(value)
                if (report.channels.isNotEmpty()) prefs.saveLocalLivePlaylist(name, report.channels)
                report
            }, { report ->
                if (report.channels.isEmpty()) {
                    toast("没有识别到 HTTP / HTTPS 频道，请检查 M3U / TXT 格式")
                    return@runApiK
                }
                liveChannels = emptyList(); liveChannelSource = ""; liveGroupFilter = ""
                showLive()
                val extra = if (report.skipped > 0 || report.unsupportedDirectives > 0)
                    "\n跳过 ${report.skipped} 行；${report.unsupportedDirectives} 条请求头等指令暂不支持，部分频道可能无法播放。" else ""
                AlertDialog.Builder(this).setTitle("直播源导入完成")
                    .setMessage("已导入 ${report.channels.size} 个频道，列表保存在本机。$extra")
                    .setPositiveButton("知道了", null).show()
            })
            return
        }
        liveChannels = emptyList(); liveChannelSource = ""; liveGroupFilter = ""
        showLive()
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
        col.addView(titleText("全部历史", 20f))

        if (items.isEmpty()) {
            col.addView(hintText("还没有观看记录。播过的片子会出现在这里，方便接着看。"))
        } else {
            col.addView(hintText("选中即从上次位置继续播放 · 长按可查看详情、加入儿童计时或删除。"))
            val listView = newList()
            bindList(listView,
                items.map { "${it.title}    ${formatTime(it.positionMs)} / ${formatTime(it.durationMs)}" },
                onItem = { idx ->
                    val h = items[idx]
                    playFromHistory(h)
                },
            )
            listView.setOnItemLongClickListener { _, _, pos, _ ->
                confirmRemoveHistory(items[pos])
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
        col.addView(titleText("全部历史", 18f))

        if (items2.isEmpty()) {
            col.addView(hintText("还没有观看记录。播过的片子会出现在这里，方便接着看。"))
        } else {
            col.addView(hintText("选中即从上次位置继续播放 · 长按可查看详情、加入儿童计时或删除"))
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
                    if (poster.tag != h.cover) poster.setImageBitmap(null)
                    poster.tag = h.cover
                    imageLoader.load(h.cover, dp(120), dp(170)) { bmp ->
                        if (poster.tag == h.cover) poster.setImageBitmap(bmp)
                    }
                    cell.setOnClickListener { playFromHistory(h) }
                    // The cell consumes touch events, so GridView's item long-click
                    // listener alone does not receive a long press on this row.
                    cell.setOnLongClickListener { confirmRemoveHistory(h); true }
                    cell.setOnKeyListener { _, code, event ->
                        if (code == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
                            confirmRemoveHistory(h); true
                        } else false
                    }
                    return cell
                }
            }
            grid.setOnItemClickListener { _, _, pos, _ ->
                items2.getOrNull(pos)?.let { playFromHistory(it) }
            }
            grid.setOnItemLongClickListener { _, _, pos, _ ->
                confirmRemoveHistory(items2[pos])
                true
            }
            grid.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
                    items2.getOrNull(grid.selectedItemPosition)?.let { confirmRemoveHistory(it) }
                    true
                } else false
            }
            col.addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setScreen(col)
        col.post { col.findFocus() ?: col.requestFocus() }
    }
    /** Percentage is for this episode; unknown duration keeps the elapsed-time fallback. */
    private fun historyProgressLabel(h: HistoryEntry): String {
        if (h.durationMs <= 0L) return "已看 ${formatTime(h.positionMs.coerceAtLeast(0L))}"
        val percent = ((h.positionMs.toDouble() / h.durationMs).coerceIn(0.0, 1.0) * 100).toInt()
        return if (percent == 0) "刚开始看" else "已看 $percent%"
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /* ------------------------------------------------------------ 收藏 */

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

    /** 收藏列表：只看「这部剧更到第几集了」，点进去还是详情页 */
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
                if (updates > 0) "收藏 · ${list.size} 部（$updates 部有新集）" else "收藏 · ${list.size} 部",
                18f,
            ),
        )
        bar.addView(button("返回推荐") { showRecommend() })
        if (list.isNotEmpty()) {
            bar.addView(button("检查更新") { checkFavoritesUpdate(force = true) })
        }
        col.addView(hScroll(bar))

        if (list.isEmpty()) {
            col.addView(hintText("还没有收藏的节目。进详情页点「加入收藏」，有新集时这里会提示。"))
        } else {
            col.addView(hintText("选中进入详情；长按可取消收藏"))
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
                    if (poster.tag != f.cover) poster.setImageBitmap(null)
                    poster.tag = f.cover
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
                    .setTitle("取消收藏？")
                    .setMessage("《${f.title}》将从收藏列表移除，观看记录不受影响。")
                    .setPositiveButton("取消收藏") {
                            _, _ ->
                        prefs.removeFavorite(f.title)
                        toast("已取消收藏")
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
     * 检查收藏更新。
     *
     * 纯本地比对：把当前集数和收藏时记下的 [Favorite.episodeCount] 比一下，
     * 变多就算更新。6 小时内查过的跳过（[force] = 手动点「检查更新」时无视）。
     */
    private fun checkFavoritesUpdate(force: Boolean = false) {
        if (favoriteCheckJob?.isActive == true) {
            if (force) toast("收藏更新检查正在进行")
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
        val title = h.seriesTitle.ifEmpty { seriesNameOf(h.title) }
        val tracked = ChildrenQuota.isChild(this, title)
        AlertDialog.Builder(this).setTitle(title)
            .setItems(arrayOf(if (tracked) "儿童计时管理 · ${ChildrenQuota.origin(this, title)}" else "加入儿童计时", "查看详情", "删除观看记录")) { _, which ->
                when (which) {
                    1 -> {
                        childrenSearchOrigin = null
                        if (h.vodId.isBlank() || h.sourceUrl.isBlank()) doSearch(title)
                        else openDetail(SearchItem(h.sourceName, h.vodId, title, h.cover, "", "", "", h.sourceUrl))
                    }
                    0 -> if (tracked) ChildrenQuota.parent(this) {} else addToChildrenQuota(title)
                    2 -> removeHistoryDialog(h)
                }
            }.show()
    }

    private fun addToChildrenQuota(title: String) {
        AlertDialog.Builder(this).setTitle("加入儿童计时")
            .setMessage("《$title》以后从历史记录、搜索等入口播放，都会计入当天儿童总时长，并受每日上限限制。设置保存在本机。")
            .setPositiveButton("加入") { _, _ -> ChildrenQuota.mark(this, title); toast("已加入儿童计时") }
            .setNegativeButton("取消", null).show()
    }

    private fun removeHistoryDialog(h: HistoryEntry) {
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

    /** 进首页时顺手跑一次收藏检查：静默，失败就当没发生，不弹任何东西 */
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
        imageLoader.load(cover, width, height, onPreview = { bmp -> if (view.tag == cover) view.setImageBitmap(bmp) }) { bmp ->
            if (view.tag == cover && bmp != null) view.setImageBitmap(bmp)
        }
    }

    /** Compact recent items: two on phones, three on TV; full history stays available. */
    private fun addContinueWatchingB(col: LinearLayout) {
        val recent = prefs.history()
        if (recent.isEmpty()) return
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(6))
        }
        head.addView(titleText("最近观看", if (isTvDevice) 25f else 21f).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(button("全部历史") { showHistoryCards() })
        col.addView(head)
        if (!isTvDevice) {
            addMobileRecentPosters(col, recent)
            return
        }
        val row = LinearLayout(this).apply {
            orientation = if (isTvDevice) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val scroll = hScroll(row).apply { isFillViewport = false }
        val cardWidth = ((resources.displayMetrics.widthPixels - dp(96) - dp(24)) / 3).coerceAtLeast(dp(180))
        val coverWidth = dp(96)
        val coverHeight = coverWidth * 3 / 2
        val contentHeight = maxOf(coverHeight, dp((128 * resources.configuration.fontScale.coerceIn(1f, 1.5f)).toInt()))
        val height = contentHeight + dp(16)
        recent.forEachIndexed { index, h ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { playFromHistory(h) }
                setOnLongClickListener { confirmRemoveHistory(h); true }
                setOnKeyListener { _, code, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false
                    else when (code) {
                        KeyEvent.KEYCODE_MENU -> { confirmRemoveHistory(h); true }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val next = index + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                            if (next in 0 until row.childCount) row.getChildAt(next).requestFocus() else true
                        }
                        else -> false
                    }
                }
                setPadding(dp(2), dp(8), dp(2), dp(8))
                // Borderless at rest; a soft highlight keeps TV focus visible.
                background = android.graphics.drawable.StateListDrawable().apply {
                    fun surface(color: Int, focus: Boolean = false) = GradientDrawable().apply {
                        setColor(color); cornerRadius = dp(12).toFloat()
                        if (focus && isTvDevice) setStroke(dp(2), ACCENT)
                    }
                    addState(intArrayOf(android.R.attr.state_pressed), surface(0xFFD5E7DF.toInt()))
                    addState(intArrayOf(android.R.attr.state_focused), surface(0xFFE2EDE5.toInt(), true))
                    addState(intArrayOf(), surface(Color.TRANSPARENT))
                }
            }
            val poster = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(0xFFD7E9DF.toInt()); cornerRadius = dp(10).toFloat()
                }
                clipToOutline = true
            }
            poster.addView(TextView(this).apply {
                text = historyTitle(h).take(2)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextSize(if (isTvDevice) 20f else 16f)
                setTextColor(ACCENT)
            }, FrameLayout.LayoutParams(-1, -1))
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            poster.addView(image, FrameLayout.LayoutParams(-1, -1))
            homeHistoryCoverViews[h.playUrl] = Triple(image, coverWidth, coverHeight)
            if (h.cover.isNotBlank()) updateHomeHistoryCover(h.playUrl, h.cover)
            card.addView(poster, LinearLayout.LayoutParams(coverWidth, coverHeight).apply { gravity = Gravity.CENTER_VERTICAL })
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(2), dp(3), dp(2))
            }
            detail.addView(TextView(this).apply {
                text = historyTitle(h)
                setTextColor(INK)
                setTextSize(19f)
                setTypeface(null, Typeface.BOLD)
                setLines(2)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2))
            detail.addView(TextView(this).apply {
                val number = Regex("第\\s*(\\d+)").find(h.title)?.groupValues?.getOrNull(1)
                text = listOfNotNull(number?.let { "第 $it 集" }, historyProgressLabel(h)).joinToString(" · ")
                setTextColor(BRIGHTER_TEXT)
                setTextSize(14f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, dp(7))
            })
            val fraction = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
            val track = FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(0xFFDDE7E2.toInt()); cornerRadius = dp(3).toFloat() }
            }
            track.addView(View(this).apply {
                background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(3).toFloat() }
            }, FrameLayout.LayoutParams(0, dp(4)))
            track.post { track.getChildAt(0).layoutParams = FrameLayout.LayoutParams((track.width * fraction).toInt(), dp(4)) }
            detail.addView(track, LinearLayout.LayoutParams(-1, dp(4)))
            if (isTvDevice) detail.addView(TextView(this).apply {
                text = "继续播放  ›"
                setTextColor(ACCENT)
                setTextSize(15f)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                setPadding(0, dp(7), 0, 0)
            })
            card.addView(detail, LinearLayout.LayoutParams(0, contentHeight, 1f))
            card.contentDescription = "${historyTitle(h)}，继续播放，长按管理"
            if (!isTvDevice) {
                card.addView(TextView(this).apply {
                    text = "▶"
                    gravity = Gravity.CENTER
                    setTextSize(17f)
                    setTextColor(ACCENT)
                    includeFontPadding = false
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFFE0ECE4.toInt())
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    leftMargin = dp(16)
                    rightMargin = dp(4)
                    gravity = Gravity.CENTER_VERTICAL
                })
            }
            card.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                if (focused) scroll.post { scroll.requestChildRectangleOnScreen(row, android.graphics.Rect(card.left, card.top, card.right, card.bottom), false) }
            }
            row.addView(card, LinearLayout.LayoutParams(cardWidth, height).apply {
                if (index > 0) leftMargin = dp(12)
            })
            if (homeHistoryFirst == null) homeHistoryFirst = card
        }
        col.addView(scroll, LinearLayout.LayoutParams(-1, height))
    }

    /** Phone history follows the same poster grid as the recommendations below. */
    private fun addMobileRecentPosters(col: LinearLayout, recent: List<HistoryEntry>) {
        val metrics = recommendCellMetrics()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recent.forEachIndexed { index, h ->
            val card = recommendCellView()
            val poster = card.getChildAt(0) as ImageView
            val title = card.getChildAt(1) as TextView
            val metadata = card.getChildAt(2) as TextView
            title.text = historyTitle(h)
            title.setLines(2)
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            val episode = Regex("第\\s*(\\d+)").find(h.title)?.groupValues?.getOrNull(1)
            metadata.text = listOfNotNull(episode?.let { "第 $it 集" }, historyProgressLabel(h)).joinToString(" · ")
            homeHistoryCoverViews[h.playUrl] = Triple(poster, metrics[0], metrics[1])
            if (h.cover.isNotBlank()) updateHomeHistoryCover(h.playUrl, h.cover)
            val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = if (h.durationMs > 0) ((h.positionMs.toDouble() / h.durationMs).coerceIn(0.0, 1.0) * 1000).toInt() else 0
                progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDDE7E2.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(7) })
            card.isClickable = true
            card.isFocusable = true
            card.contentDescription = "${historyTitle(h)}，${metadata.text}，点击继续播放，长按管理"
            card.setOnClickListener { playFromHistory(h) }
            card.setOnLongClickListener { confirmRemoveHistory(h); true }
            row.addView(card, LinearLayout.LayoutParams(metrics[0], -2).apply {
                if (index > 0) leftMargin = dp(12)
            })
            if (homeHistoryFirst == null) homeHistoryFirst = card
        }
        col.addView(hScroll(row), LinearLayout.LayoutParams(-1, -2))
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
            val iconSize = dp(if (isTvDevice) 34 else 25)
            val icon = HomeIconView(this@MainActivity, kind).apply {
                tintColor = INK
            }
            addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            val caption = TextView(this@MainActivity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(INK)
                setTextSize(if (isTvDevice) 17f else 12f)
                includeFontPadding = false
                isSingleLine = true
                maxLines = 1
                setPadding(0, dp(4), 0, 0)
            }
            addView(caption, LinearLayout.LayoutParams(-1, -2))
            applyFocusStyle(this, PANEL,
                onFocusChanged = { focused ->
                    icon.tintColor = if (focused) Color.WHITE else INK
                    caption.setTextColor(if (focused) Color.WHITE else INK)
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
        "live" -> homeTile("live", "直播") { liveChannels = emptyList(); liveChannelSource = ""; showLive() }
        "kids" -> homeTile("kids", "儿童") { showChildren() }.apply {
            alpha = 1f
            childrenQuotaTile = this
        }
        "history" -> homeTile("history", "继续") { showHistoryCards() }
        "fav" -> {
            // 有更新时在标签上带一句，用户不用进页面才知道有新集
            val updates = prefs.favoriteUpdateCount()
            homeTile("fav", if (updates > 0) "收藏·$updates" else "收藏") { showFavorites() }
        }
        "sources" -> homeTile("sources", "片源") { showSources() }
        "setup" -> homeTile("setup", "设置") { showSetup() }
        else -> homeTile("other", HomeBar.label(id)) { }
    }

    private fun returnToChildrenOrigin() {
        val section = childrenSearchOrigin
        childrenSearchOrigin = null
        if (section == null || section < 0) showChildren() else showChildrenMore(section)
    }

    private fun showResultsFromDetail() {
        val title = selectedResultTitle
        showResults()
        val position = resultGroups.indexOfFirst { it.first == title }
        resultsGrid?.post {
            if (position >= 0) resultsGrid?.setSelection(position)
            resultsGrid?.requestFocus()
        }
    }

    private fun showChildrenEmpty(keyword: String, failedNote: String) {
        currentScreen = "children_empty"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(22), pagePad(40), dp(20))
        }
        col.addView(childrenHeader("暂未找到《$keyword》") { returnToChildrenOrigin() })
        col.addView(hintText("可以重试，或检查已启用的片源。$failedNote"))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("重试") {
            val section = childrenSearchOrigin
            if (section == null || section < 0) showChildren() else showChildrenMore(section)
            doSearch(keyword)
        })
        actions.addView(button("片源管理") { showSources() })
        actions.addView(button("返回儿童乐园") { returnToChildrenOrigin() })
        col.addView(hScroll(actions))
        setScreen(col)
    }

    // Resolve and preload all 100 titles with bounded concurrency; visible cards are queued first.
    private fun childrenDailyPicks(section: Int, count: Int): List<String> {
        val calendar = java.util.Calendar.getInstance()
        val day = calendar.get(java.util.Calendar.YEAR).toLong() * 366 + calendar.get(java.util.Calendar.DAY_OF_YEAR)
        val store = getSharedPreferences("children_daily_recommendations", MODE_PRIVATE)
        val titles = ChildrenCatalog.sections[section].titles
        val key = "section:$section"
        val cached = store.getString(key, "").orEmpty().split("\n").filter { it in titles }
        if (store.getLong("day", -1L) == day && cached.size >= count) return cached.take(count)
        if (store.getLong("day", -1L) != day) store.edit().clear().putLong("day", day).apply()
        val picks = dailyChildrenPicks(titles, day, prefs.history().map(::historyTitle).toSet(), 3)
        store.edit().putString(key, picks.joinToString("\n")).apply()
        return picks.take(count)
    }

    private var childrenSectionIndex = -1
    private var childrenSearchOrigin: Int? = null
    private var selectedResultTitle: String? = null
    private var childrenOverviewScroll: ScrollView? = null
    private var childrenOverviewScrollY = 0
    private var childrenOverviewFocus: String? = null
    private var childrenOverviewFocusSection: Int? = null
    private val childrenOverviewRowOffsets = mutableMapOf<Int, Int>()
    private var childrenMoreGrid: GridView? = null
    private var childrenMoreTitle: String? = null
    private var childrenMoreFilter = 0
    private var childrenMoreFilterSection = -1
    // 封面解析的并发上限。低配盒子也能承受的海报下载并发。
    private val childrenCoverSlots = kotlinx.coroutines.sync.Semaphore(8)
    private val childrenCovers = mutableMapOf<String, String>()
    private val childrenCoverTried = mutableSetOf<String>()
    private val childrenCoverWaiting = mutableMapOf<String, MutableList<java.lang.ref.WeakReference<ImageView>>>()
    private val childrenCoverJobs = mutableMapOf<String, Job>()
    private val childrenBadCovers = mutableMapOf<String, MutableSet<String>>()
    private var childrenPrefetchJob: Job? = null
    private var childrenNearPrefetchJob: Job? = null
    private var homePrefetchGeneration = 0L
    private var childrenCoverSignature = ""
    /** 手动指定的封面（片名 → URL）：优先级高于自动解析，永远不会被覆盖 */
    private var childrenPinnedCovers: Map<String, String> = emptyMap()
    private var childrenPinSyncAt = 0L
    private var childrenSources: List<SourceConfig> = emptyList()
    private var childrenCacheWriteJob: Job? = null
    private var childrenPreloadedSignature = ""
    private var childrenLastPreloadAt = 0L

    private fun prepareChildrenCovers() {
        val sources = prefs.sources().filter { it.enabled && !it.isAdult }
        val signature = stableDigest("cover-match-v4\n" + prefs.serverUrl + "\n" + sources.joinToString("\n") { it.url })
        childrenSources = sources
        childrenPinnedCovers = prefs.pinnedChildrenCovers()
        if (signature == childrenCoverSignature) {
            val pending = childrenCoverWaiting.keys.any { it.startsWith("$signature:") }
            if (!pending && android.os.SystemClock.elapsedRealtime() - childrenLastPreloadAt >= 60_000L) {
                childrenCoverTried.clear()
                childrenBadCovers.clear()
                childrenPreloadedSignature = ""
            }
            return
        }
        childrenCacheWriteJob?.cancel()
        childrenPrefetchJob?.cancel()
        childrenBadCovers.clear()
        childrenCoverSignature = signature
        childrenPreloadedSignature = ""
        childrenLastPreloadAt = 0L
        childrenCovers.clear()
        childrenCoverTried.clear()
        childrenCovers.putAll(prefs.loadChildrenCoverCache(signature))
    }

    private fun persistChildrenCovers(signature: String) {
        childrenCacheWriteJob?.cancel()
        childrenCacheWriteJob = scope.launch {
            delay(1_000)
            if (signature == childrenCoverSignature) {
                val snapshot = childrenCovers.toMap()
                withContext(Dispatchers.IO) { prefs.saveChildrenCoverCache(signature, snapshot) }
            }
        }
    }

    private data class ChildrenImageResult(val bitmap: android.graphics.Bitmap?)

    /** Null means the wait expired; a result with a null bitmap means an actual load failure. */
    private suspend fun awaitChildrenImage(url: String, width: Int, height: Int, background: Boolean = true): ChildrenImageResult? =
        kotlinx.coroutines.withTimeoutOrNull(35_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                imageLoader.load(url, width, height, background = background, needed = { continuation.isActive }, onCancelled = {
                    continuation.cancel(kotlinx.coroutines.CancellationException("图片缓存已清理"))
                }) { bitmap ->
                    if (continuation.isActive) continuation.resumeWith(Result.success(ChildrenImageResult(bitmap)))
                }
            }
        }

    private fun invalidateChildrenCover(title: String, url: String, signature: String) {
        if (signature != childrenCoverSignature || childrenCovers[title] != url) return
        childrenCovers.remove(title)
        childrenCoverTried.remove(title)
        childrenBadCovers.getOrPut(title) { mutableSetOf() }.add(url)
        persistChildrenCovers(signature)
    }

    private fun updateChildrenCoverStatus(image: ImageView, text: String?) {
        val badge = (image.parent as? FrameLayout)?.getChildAt(2) as? TextView ?: return
        val visibleText = text?.takeUnless { it == "暂无封面" || it == "可重试" }
        badge.text = visibleText.orEmpty()
        badge.visibility = if (visibleText == null) View.GONE else View.VISIBLE
    }

    private fun showChildrenCoverActions(title: String, image: ImageView, width: Int, height: Int) {
        AlertDialog.Builder(this).setTitle("《$title》封面")
            .setItems(arrayOf("从搜索结果选封面", "换一张封面", "重试封面加载")) { _, which ->
                if (which == 0) { showChildrenCoverPicker(title, image, width, height); return@setItems }
                if (which == 2) { retryChildrenCover(title, image, width, height); return@setItems }
                val signature = childrenCoverSignature
                val key = "$signature:$title"
                if (childrenCoverWaiting.containsKey(key)) { toast("封面加载中，请稍后再换"); return@setItems }
                val old = childrenCovers[title]
                if (old != null) childrenBadCovers.getOrPut(title) { mutableSetOf() }.add(old)
                childrenCovers.remove(title)
                childrenCoverTried.remove(title)
                scope.launch {
                    try { queueChildrenCover(title, width, height, image)?.join() }
                    finally {
                        if (signature == childrenCoverSignature && childrenCovers[title] == null && old != null) {
                            childrenCovers[title] = old
                            if (image.tag == title && image.isAttachedToWindow) bindChildrenCover(image, title, width, height)
                            toast("暂无其他可用封面，已保留原封面")
                        }
                        if (signature == childrenCoverSignature) persistChildrenCovers(signature)
                    }
                }
            }.setNegativeButton("取消", null).show()
    }

    /**
     * 从各片源的搜索结果里手动挑一张封面（长按卡片 → 从搜索结果选封面）。
     * 与自动解析不同：这里把每个结果的海报连同片名一起列出来，由用户肉眼判断，
     * 所以不按片名严格过滤（像《小羊肖恩》这种自动匹配不到的，人工一眼就能认出来）。
     */
    private fun showChildrenCoverPicker(title: String, image: ImageView, width: Int, height: Int) {
        val frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.setPadding(dp(16), dp(12), dp(16), dp(12))
        val status = hintText("正在从启用的片源搜索《$title》…").apply { setPadding(0, 0, 0, dp(6)) }
        frame.addView(status)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(
            ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)),
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("为《$title》选封面")
            .setView(frame)
            .setNegativeButton("关闭", null)
            .show()

        val cellW = dp(104)
        val cellH = dp(140)
        val collected = linkedMapOf<String, String>()  // url → 展示片名
        fun addCandidate(name: String, rawPic: String) {
            val url = rawPic.trim().let { if (it.startsWith("//")) "https:$it" else it }
            if (!url.startsWith("http")) return
            collected.putIfAbsent(url, name.ifBlank { title })
        }
        fun flushGrid() {
            if (!dialog.isShowing) return
            grid.removeAllViews()
            var row: LinearLayout? = null
            collected.entries.forEachIndexed { idx, (url, name) ->
                if (idx % 3 == 0) {
                    row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        dialog.dismiss()
                        applyPinnedChildrenCover(title, url, image, width, height)
                    }
                }
                cell.addView(
                    ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0xFFE8EEEA.toInt())
                        imageLoader.load(url, cellW, cellH) { bmp -> if (bmp != null) setImageBitmap(bmp) }
                    },
                    LinearLayout.LayoutParams(cellW, cellH),
                )
                cell.addView(
                    TextView(this).apply {
                        text = name
                        setTextSize(10f)
                        setTextColor(BRIGHTER_TEXT)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    },
                    LinearLayout.LayoutParams(cellW, -2),
                )
                row?.addView(cell, LinearLayout.LayoutParams(-2, -2).apply {
                    if (idx % 3 < 2) rightMargin = dp(8)
                })
            }
            status.text = if (collected.isEmpty()) {
                "没有找到候选海报，可以稍后重试"
            } else {
                "共 ${collected.size} 张候选 · 点一张设为《$title》的封面"
            }
        }

        scope.launch {
            val sources = childrenSources.ifEmpty { prefs.sources().filter { it.enabled && !it.isAdult } }
            ChildrenArtworkIndex.urls(applicationContext, title).forEach { addCandidate(title, it) }
            val coverConnections = SearchConnections()
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000L
            val watchdog = scope.launch { delay(21_000); coverConnections.cancel() }
            try {
                val directSources = sources.filter { DirectCms.shouldTryDirect(it) }
                if (directSources.isNotEmpty()) {
                    val items = withContext(Dispatchers.IO) {
                        directSources.map { src ->
                            async { runCatching { coverConnections.run { DirectCms.search(src, title) } }.getOrDefault(emptyList()) }
                        }.awaitAll()
                    }.flatten()
                    items.forEach { addCandidate(it.name, it.pic) }
                    flushGrid()
                }
                for (batch in sources.chunked(4)) {
                    if (collected.size >= 30 || coverConnections.isCancelled() ||
                        android.os.SystemClock.elapsedRealtime() >= deadline || !dialog.isShowing) break
                    val items = withContext(Dispatchers.IO) {
                        batch.map { src ->
                            async {
                                runCatching { coverConnections.run { api.search(title, listOf(src), readTimeoutMs = 6_000).items } }
                                    .getOrDefault(emptyList())
                            }
                        }.awaitAll()
                    }.flatten()
                    items.forEach { addCandidate(it.name, it.pic) }
                    flushGrid()
                }
            } finally {
                watchdog.cancel()
                coverConnections.cancel()
            }
            flushGrid()
        }
    }

    /** 应用手动选定的封面：本机立即生效并持久化，随后尽力推送服务端做全屋同步。 */
    private fun applyPinnedChildrenCover(title: String, url: String, image: ImageView, width: Int, height: Int) {
        prefs.setPinnedChildrenCovers(prefs.pinnedChildrenCovers().toMutableMap().apply { put(title, url) })
        childrenPinnedCovers = prefs.pinnedChildrenCovers()
        childrenCovers[title] = url
        childrenCoverTried.add(title)
        persistChildrenCovers(childrenCoverSignature)
        bindChildrenCover(image, title, width, height)
        val snapshot = prefs.pinnedChildrenCovers()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { api.saveCustomCovers(snapshot) }
            toast(if (ok) "封面已同步到服务器，全家设备可见" else "封面已保存在本机（服务端暂不支持同步）")
        }
    }

    /** 拉取服务端的手动封面并合并（远端优先，家里别的设备设置的会覆盖进来）。不支持时静默跳过。 */
    private fun syncChildrenPinnedCovers() {
        if (android.os.SystemClock.elapsedRealtime() - childrenPinSyncAt < 300_000L) return
        childrenPinSyncAt = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            val remote = withContext(Dispatchers.IO) { runCatching { api.customCovers() }.getOrNull() } ?: return@launch
            val local = prefs.pinnedChildrenCovers()
            val merged = local + remote
            if (merged == local) return@launch
            prefs.setPinnedChildrenCovers(merged)
            childrenPinnedCovers = merged
            merged.forEach { (t, u) -> if (childrenCovers[t] == null) childrenCovers[t] = u }
            persistChildrenCovers(childrenCoverSignature)
            toast("已同步 ${merged.size - local.size} 条手动封面")
            // 正停在儿童板块的话重建一次，让新封面立刻可见（焦点与滚动位置会自动恢复）
            if (currentScreen == "children") showChildren()
        }
    }

    private fun setChildrenBitmap(image: ImageView, bitmap: android.graphics.Bitmap) {
        image.setBackgroundColor(0xFFD9F1E7.toInt())
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setImageBitmap(bitmap)
    }

    private fun retryChildrenCover(title: String, image: ImageView, width: Int, height: Int) {
        val key = "$childrenCoverSignature:$title"
        if (childrenCoverWaiting.containsKey(key)) { toast("正在获取封面，请稍候"); return }
        childrenCoverTried.remove(title)
        childrenBadCovers.remove(title)
        childrenCovers.remove(title)
        persistChildrenCovers(childrenCoverSignature)
        updateChildrenCoverStatus(image, "加载中")
        queueChildrenCover(title, width, height, image)
    }

    private fun queueChildrenCover(title: String, width: Int, height: Int, image: ImageView? = null): Job? {
        val signature = childrenCoverSignature
        val pinned = childrenPinnedCovers[title]
        val cached = pinned ?: childrenCovers[title]
        if (cached != null) {
            if (image != null) imageLoader.load(cached, width, height, onCancelled = {
                if (image.tag == title) updateChildrenCoverStatus(image, "可重试")
            }) { bmp ->
                if (image.tag != title || signature != childrenCoverSignature) return@load
                if (bmp != null) { setChildrenBitmap(image, bmp); updateChildrenCoverStatus(image, null) }
                else if (pinned != null && cached == pinned) {
                    // 手动指定的封面失效：不自动换源重试（绝不能覆盖用户的选择），
                    // 留「可重试」角标，长按卡片可以重新挑一张
                    updateChildrenCoverStatus(image, "可重试")
                } else {
                    invalidateChildrenCover(title, cached, signature)
                    queueChildrenCover(title, width, height, image)
                }
            }
            return null
        }
        if (title in childrenCoverTried) {
            image?.let { updateChildrenCoverStatus(it, "暂无封面") }
            return null
        }
        image?.let { updateChildrenCoverStatus(it, "加载中") }
        val key = "$signature:$title"
        val existing = childrenCoverWaiting[key]
        if (existing != null) {
            if (image != null) existing.add(java.lang.ref.WeakReference(image))
            return childrenCoverJobs[key]
        }
        val waiting = mutableListOf<java.lang.ref.WeakReference<ImageView>>()
        if (image != null) waiting.add(java.lang.ref.WeakReference(image))
        childrenCoverWaiting[key] = waiting
        val sources = childrenSources
        val requestApi = api
        val job = scope.launch {
            try {
                childrenCoverSlots.acquire()
                val coverConnections = SearchConnections()
                // 25 秒快速失败：一个片名 25 秒内找不到封面，再等 60 秒也大概率找不到，
                // 只会占着并发坑拖累别的片名。失败后标 tried，下一轮轮换直接跳过。
                val deadline = android.os.SystemClock.elapsedRealtime() + 25_000L
                val watchdog = scope.launch { delay(26_000); coverConnections.cancel() }
                try {
                        var earlyShown = false
                        suspend fun tryCoverUrls(urls: List<String>, perImageBudget: Long = 15_000L): String {
                            val candidates = urls.filter { it !in childrenBadCovers[title].orEmpty() }.take(4)
                            var best = ""
                            var bestScore = -1.0
                            var i = 0
                            // 每轮并发「比稿」2 张、择优保留：既保留挑选余地避免选中低清图，
                            // 又不用像串行那样一张张等（两张同时下，一轮顶两轮）
                            while (i < candidates.size) {
                                val remaining = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                                if (remaining == 0L) break
                                val pair = candidates.subList(i, minOf(i + 2, candidates.size))
                                // Keep completed results even if a sibling times out; publish a good
                                // matching cover immediately while remaining candidates are compared.
                                val arrived = arrayOfNulls<ChildrenImageResult>(pair.size)
                                kotlinx.coroutines.withTimeoutOrNull(minOf(remaining, perImageBudget)) {
                                    pair.mapIndexed { index, candidate -> async {
                                        val visible = waiting.any { it.get()?.tag == title }
                                        val result = awaitChildrenImage(candidate, width, height, background = !visible)
                                        arrived[index] = result
                                        val bitmap = result?.bitmap
                                        if (!earlyShown && bitmap != null && waiting.any { it.get()?.tag == title } && signature == childrenCoverSignature &&
                                            childrenPinnedCovers[title] == null &&
                                            childrenCoverQuality(bitmap.width, bitmap.height, width, height) >= .82) {
                                            earlyShown = true
                                            waiting.forEach { ref ->
                                                val target = ref.get()
                                                if (target != null && target.tag == title) {
                                                    setChildrenBitmap(target, bitmap)
                                                    updateChildrenCoverStatus(target, null)
                                                }
                                            }
                                        }
                                    } }.awaitAll()
                                }
                                val results = arrived.toList()
                                for ((idx, result) in results.withIndex()) {
                                    val candidate = pair[idx]
                                    if (result?.bitmap != null) {
                                        val score = childrenCoverQuality(result.bitmap.width, result.bitmap.height, width, height)
                                        if (score > bestScore) { best = candidate; bestScore = score }
                                    } else if (result != null && android.os.SystemClock.elapsedRealtime() < deadline) {
                                        childrenBadCovers.getOrPut(title) { mutableSetOf() }.add(candidate)
                                    }
                                }
                                if (bestScore >= .82) break
                                i += 2
                            }
                            return best
                        }
                    val previewSlot = kotlinx.coroutines.sync.Semaphore(1)
                    suspend fun revealFromSearch(items: List<SearchItem>) {
                        if (items.isEmpty() || waiting.none { it.get()?.tag == title }) return
                        previewSlot.acquire()
                        try {
                            if (!earlyShown && childrenPinnedCovers[title] == null && signature == childrenCoverSignature) {
                                val candidates = childrenCoverCandidates(title, items.map { it.name to it.pic })
                                if (candidates.isNotEmpty()) tryCoverUrls(candidates, perImageBudget = 4_000L)
                            }
                        } finally { previewSlot.release() }
                    }
                    val independent = withContext(Dispatchers.IO) { ChildrenArtworkIndex.urls(applicationContext, title) }
                    var chosen = tryCoverUrls(independent, perImageBudget = 6_000L)
                    // 直连源全部并发查：原来逐源串行，一个慢源卡几秒就把后面全部拖住。
                    // 一次发齐、一次收齐，再统一挑候选图。
                    if (chosen.isEmpty()) {
                        val directSources = sources.filter { DirectCms.shouldTryDirect(it) }
                        if (directSources.isNotEmpty()) {
                            val items = withContext(Dispatchers.IO) {
                                directSources.map { src ->
                                    async {
                                        val found = runCatching { coverConnections.run { DirectCms.search(src, title) } }
                                            .getOrDefault(emptyList())
                                        withContext(Dispatchers.Main) { revealFromSearch(found) }
                                        found
                                    }
                                }.awaitAll()
                            }.flatten()
                            chosen = tryCoverUrls(childrenCoverCandidates(title, items.map { it.name to it.pic }))
                        }
                    }
                    // 服务端每批 4 个源并发：保持「先到先用」的行为，批内的源不再互相等待
                    for (batch in sources.chunked(4)) {
                        if (chosen.isNotEmpty() || coverConnections.isCancelled() ||
                            android.os.SystemClock.elapsedRealtime() >= deadline) break
                        val items = withContext(Dispatchers.IO) {
                            batch.map { src ->
                                async {
                                    val found = runCatching { coverConnections.run { requestApi.search(title, listOf(src), readTimeoutMs = 6_000).items } }
                                        .getOrDefault(emptyList())
                                    withContext(Dispatchers.Main) { revealFromSearch(found) }
                                    found
                                }
                            }.awaitAll()
                        }.flatten()
                        chosen = tryCoverUrls(childrenCoverCandidates(title, items.map { it.name to it.pic }))
                        if (chosen.isNotEmpty() || signature != childrenCoverSignature || requestApi !== api) break
                    }
                    if (requestApi !== api || signature != childrenCoverSignature) return@launch
                    childrenCoverTried.add(title)
                    if (chosen.isNotEmpty()) {
                        // A parent may have selected a cover while this search was running.
                        chosen = childrenPinnedCovers[title] ?: chosen
                        childrenCovers[title] = chosen
                        persistChildrenCovers(signature)
                        waiting.forEach { ref ->
                            val target = ref.get() ?: return@forEach
                            if (target.tag == title) imageLoader.load(chosen, width, height, onCancelled = {
                                if (target.tag == title) updateChildrenCoverStatus(target, "可重试")
                            }) { bmp ->
                                if (target.tag == title && bmp != null) {
                                    setChildrenBitmap(target, bmp)
                                    updateChildrenCoverStatus(target, null)
                                }
                            }
                        }
                    } else {
                        waiting.forEach { ref ->
                            val target = ref.get() ?: return@forEach
                            if (target.tag == title) updateChildrenCoverStatus(target, "暂无封面")
                        }
                    }
                } finally {
                    watchdog.cancel()
                    coverConnections.cancel()
                    childrenCoverSlots.release()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                waiting.forEach { ref ->
                    val target = ref.get() ?: return@forEach
                    if (target.tag == title) updateChildrenCoverStatus(target, "可重试")
                }
                throw e
            } finally {
                childrenCoverWaiting.remove(key)
                childrenCoverJobs.remove(key)
            }
        }
        childrenCoverJobs[key] = job
        return job
    }

    private fun bindChildrenCover(image: ImageView, title: String, width: Int, height: Int) {
        image.setBackgroundColor(Color.TRANSPARENT)
        image.tag = title
        image.setImageDrawable(null)
        updateChildrenCoverStatus(image, "加载中")
        queueChildrenCover(title, width, height, image)
    }

    private fun prefetchChildrenCovers(width: Int, height: Int) {
        if (childrenPreloadedSignature == childrenCoverSignature) return
        childrenPreloadedSignature = childrenCoverSignature
        childrenLastPreloadAt = android.os.SystemClock.elapsedRealtime()
        val signature = childrenCoverSignature
        childrenPrefetchJob?.cancel()
        // Keep at most two speculative lookups in flight, reserving a slot for visible cards.
        childrenPrefetchJob = scope.launch {
            // 先让可见卡片的解析占用并发坑，4 秒后再开始后台预热
            delay(4_000)
            // 只预热首页附近的候选，给可见卡片和下一屏留出下载资源。
            val nearby = ChildrenCatalog.sections.indices.flatMap { childrenDailyPicks(it, if (isTvDevice) 3 else 2) } +
                ChildrenCatalog.sections.flatMap { it.titles.take(if (isTvDevice) 4 else 3) }
            for (batch in nearby.distinct().chunked(2)) {
                if (signature != childrenCoverSignature) break
                batch.map { title -> async {
                    val cover = childrenCovers[title]
                    if (cover != null) {
                        val result = awaitChildrenImage(cover, width, height)
                        if (result != null && result.bitmap == null) {
                            invalidateChildrenCover(title, cover, signature)
                            queueChildrenCover(title, width, height)?.join()
                        }
                    } else {
                        queueChildrenCover(title, width, height)?.join()
                    }
                } }.awaitAll()
            }
        }
    }

    private fun childrenCard(width: Int, height: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        isFocusable = true
        isClickable = true
        clipChildren = false
        val art = FrameLayout(this@MainActivity).apply {
            background = GradientDrawable().apply { setColor(0xFFD9F1E7.toInt()); cornerRadius = dp(10).toFloat() }
            clipToOutline = true
            addView(ChildrenArtworkView(this@MainActivity), FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(-1, -1))
            addView(TextView(this@MainActivity).apply {
                text = "加载中"; setTextColor(Color.WHITE); setTextSize(10f)
                setPadding(dp(6), dp(3), dp(6), dp(3))
                background = GradientDrawable().apply { setColor(0xAA173B35.toInt()); cornerRadius = dp(6).toFloat() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        addView(art, LinearLayout.LayoutParams(-1, height))
        addView(TextView(this@MainActivity).apply {
            gravity = Gravity.CENTER
            setTextColor(INK); setTextSize(if (isTvDevice) 16f else 14f)
            setTypeface(null, Typeface.BOLD); maxLines = 2; minLines = if (isTvDevice) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(if (isTvDevice) 7 else 4), dp(2), dp(2))
        }, LinearLayout.LayoutParams(-1, -2))
        applyFocusStyle(this, PANEL, focusScale = 1.02f)
    }

    private fun bindChildrenCard(card: LinearLayout, title: String, width: Int, height: Int) {
        val frame = card.getChildAt(0) as FrameLayout
        val image = frame.getChildAt(1) as ImageView
        (frame.getChildAt(0) as ChildrenArtworkView).title = title
        (card.getChildAt(1) as TextView).text = title
        bindChildrenCover(image, title, width, height)
        card.setOnClickListener {
            if (currentScreen == "children") {
                childrenOverviewFocus = title
                childrenOverviewScrollY = childrenOverviewScroll?.scrollY ?: 0
            }
            doSearch(title)
        }
        card.setOnLongClickListener { showChildrenCoverActions(title, image, width, height); true }
        card.contentDescription = title
    }

    private fun childrenHeader(title: String, back: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(button("← 返回") { back() }.apply { tag = SKIP_FIRST_FOCUS })
        addView(titleText(title, if (isTvDevice) 26f else 22f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun showChildren() {
        currentScreen = "children"
        prepareChildrenCovers()
        // 尽力拉取家里其他设备手动指定的封面（服务端不支持时静默跳过）
        syncChildrenPinnedCovers()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(18), pagePad(40), dp(24))
        }
        col.addView(childrenHeader("儿童乐园") { showRecommend() })
        col.addView(button("${ChildrenQuota.label(this)} · 家长设置 / 加时") {
            ChildrenQuota.parent(this) { showChildren() }
        }.also { quotaLabelView = it })
        var firstCard: View? = null
        val focusViews = mutableMapOf<String, View>()
        val catalogTitles = ChildrenCatalog.preloadOrder().map(::normalizeSeries).toSet()
        val recentChildren = prefs.history().filter { normalizeSeries(it.seriesTitle.ifEmpty { historyTitle(it) }) in catalogTitles }.take(3)
        if (recentChildren.isNotEmpty()) {
            col.addView(titleText("继续看", if (isTvDevice) 20f else 18f))
            val recentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            recentChildren.forEach { entry ->
                val cardWidth = if (isTvDevice) dp(200) else dp(150)
                val cardHeight = if (isTvDevice) dp(110) else dp(92)
                val title = entry.seriesTitle.ifEmpty { historyTitle(entry) }
                val card = childrenCard(cardWidth, cardHeight)
                bindChildrenCard(card, title, cardWidth, cardHeight)
                (card.getChildAt(1) as TextView).text = "$title · 第 ${entry.episodeIndex.coerceAtLeast(0) + 1} 集\n${entry.positionMs / 60_000}:${(entry.positionMs / 1000 % 60).toString().padStart(2, '0')}"
                focusViews["recent:$title"] = card
                card.setOnClickListener {
                    childrenOverviewFocus = "recent:$title"
                    childrenOverviewScrollY = childrenOverviewScroll?.scrollY ?: 0
                    playFromHistory(entry)
                }
                if (firstCard == null) firstCard = card
                recentRow.addView(card, LinearLayout.LayoutParams(cardWidth, -2).apply { rightMargin = dp(8) })
            }
            col.addView(hScroll(recentRow))
        }
        col.addView(hintText("按年龄推荐 · 点动画选择片源 · 年龄仅供参考").apply {
            setPadding(dp(6), dp(4), dp(6), dp(12))
        })
        val columns = if (isTvDevice) 3 else 2
        val width = ((resources.displayMetrics.widthPixels - pagePad(40) * 2 - dp(20) - dp(8) * (columns - 1)) / columns).coerceAtLeast(dp(72))
        val height = if (isTvDevice) dp(110) else (width * .62f).toInt()
        ChildrenCatalog.sections.forEachIndexed { index, section ->
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(PANEL); cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), BORDER)
                }
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(titleText("${section.age}  ${section.title}", if (isTvDevice) 20f else 18f).apply {
                setTypeface(null, Typeface.BOLD); setTextColor(ACCENT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val more = button("全部 ›") {
                childrenOverviewFocus = "more:$index"
                childrenOverviewScrollY = childrenOverviewScroll?.scrollY ?: 0
                showChildrenMore(index)
            }
            more.apply {
                elevation = 0f; stateListAnimator = null
                minimumWidth = dp(64); minWidth = dp(64)
                minimumHeight = dp(48); minHeight = dp(48)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setTextSize(if (isTvDevice) 16f else 14f)
                contentDescription = "查看${section.age}全部动画"
            }
            focusViews["more:$index"] = more
            head.addView(more)
            panel.addView(head)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            }
            val preferred = childrenDailyPicks(index, 3)
            val calendar = java.util.Calendar.getInstance()
            val day = calendar.get(java.util.Calendar.YEAR).toLong() * 366 + calendar.get(java.util.Calendar.DAY_OF_YEAR)
            val ordered = dailyChildrenPicks(section.titles, day, prefs.history().map(::historyTitle).toSet(), section.titles.size)
            val rowScroll = hScroll(row)
            scope.launch {
                val listed = withContext(Dispatchers.IO) {
                    ordered.filter { ChildrenArtworkIndex.urls(applicationContext, it).isNotEmpty() }.toSet()
                }
                if (currentScreen != "children" || !row.isAttachedToWindow) return@launch
                val candidates = (preferred.filter { it in childrenCovers || it in listed } + ordered.filter { it in childrenCovers || it in listed } + ordered).distinct()
                var next = 0
                lateinit var addMore: () -> Unit
                addMore = {
                    val end = minOf(next + columns, candidates.size)
                    while (next < end) {
                        val cardIndex = next++
                        val title = candidates[cardIndex]
                        val card = childrenCard(width, height)
                        bindChildrenCard(card, title, width, height)
                        card.setOnClickListener {
                            childrenOverviewFocus = title
                            childrenOverviewFocusSection = index
                            childrenOverviewScrollY = childrenOverviewScroll?.scrollY ?: 0
                            childrenOverviewRowOffsets[index] = rowScroll.scrollX
                            doSearch(title)
                        }
                        focusViews[title] = card
                        card.setOnKeyListener { _, code, event ->
                            if (event.action != KeyEvent.ACTION_DOWN || code !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false
                            else {
                                val target = cardIndex + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                                if (target >= row.childCount && target < candidates.size) addMore()
                                if (target in 0 until row.childCount) row.getChildAt(target).requestFocus() else true
                            }
                        }
                        val originalFocus = card.onFocusChangeListener
                        card.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                            originalFocus?.onFocusChange(view, focused)
                            if (focused) {
                                if (cardIndex >= row.childCount - columns && next < candidates.size) addMore()
                                rowScroll.post { rowScroll.requestChildRectangleOnScreen(row, android.graphics.Rect(card.left, card.top, card.right, card.bottom), false) }
                            }
                        }
                        row.addView(card, LinearLayout.LayoutParams(width, -2).apply { rightMargin = dp(8) })
                        if (firstCard == null) firstCard = card
                        if (isTvDevice && childrenOverviewFocusSection == index && childrenOverviewFocus == title) card.post { if (currentScreen == "children") card.requestFocus() }
                    }
                }
                addMore()
                val savedOffset = childrenOverviewRowOffsets[index] ?: 0
                val restoreCount = (savedOffset / (width + dp(8))) + columns + 1
                while (next < minOf(candidates.size, restoreCount)) addMore()
                if (isTvDevice && childrenOverviewFocusSection == index && childrenOverviewFocus != null) {
                    val wanted = candidates.indexOf(childrenOverviewFocus)
                    while (wanted >= next) addMore()
                }
                rowScroll.post { if (currentScreen == "children") rowScroll.scrollTo(savedOffset, 0) }
                rowScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
                    childrenOverviewRowOffsets[index] = scrollX
                    if (next < candidates.size && scrollX + rowScroll.width >= row.width - width) addMore()
                }
                if (isTvDevice && currentFocus == null) firstCard?.requestFocus()
            }
            panel.addView(rowScroll)
            col.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(if (isTvDevice) 12 else 8) })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false; clipChildren = false; clipToPadding = false
            addView(col)
        }
        childrenOverviewScroll = scroll
        setScreen(scroll, focusFirst = false)
        scroll.post {
            if (currentScreen != "children") return@post
            scroll.scrollTo(0, childrenOverviewScrollY)
            if (isTvDevice) (focusViews[childrenOverviewFocus] ?: firstCard)?.requestFocus()
        }
        prefetchChildrenCovers(maxOf(width, dp(240)), maxOf(height, dp(130)))
    }

    private fun showChildrenMore(index: Int) {
        val section = ChildrenCatalog.sections.getOrNull(index) ?: return
        prepareChildrenCovers()
        syncChildrenPinnedCovers()
        if (childrenMoreFilterSection != index) {
            childrenMoreFilterSection = index
            childrenMoreFilter = 0
            childrenMoreTitle = null
        }
        currentScreen = "children_more"
        childrenSectionIndex = index
        val rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePad(40), dp(18), pagePad(40), dp(18))
        }
        rootColumn.addView(childrenHeader("${section.age}  ${section.title}") { showChildren() })
        val hint = hintText("")
        val selectedName = hintText("").apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        val columns = if (isTvDevice) 4 else 2
        val width = ((resources.displayMetrics.widthPixels - pagePad(40) * 2 - dp(12) * (columns - 1)) / columns).coerceAtLeast(dp(110))
        val height = if (isTvDevice) dp(130) else dp(105)
        val filterPositions = mutableMapOf<Int, Pair<Int, Int>>()
        var items: List<String> = section.titles
        lateinit var grid: GridView
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val filter = button("") { }
        fun updateFilter() {
            val watched = if (childrenMoreFilter == 2) prefs.history().map(::historyTitle).toSet() else emptySet()
            items = filterChildrenTitles(section.titles, childrenMoreFilter, childrenCovers.keys, watched)
            filter.text = when (childrenMoreFilter) { 1 -> "筛选：已收录封面地址"; 2 -> "筛选：最近看过"; else -> "筛选：全部" }
            hint.text = "${items.size} / ${section.titles.size} 部 · 年龄仅供参考" +
                if (items.isEmpty()) " · 当前没有符合条件的作品" else ""
            (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
            val position = items.indexOf(childrenMoreTitle).coerceAtLeast(0)
            grid.post {
                if (items.isNotEmpty()) grid.setSelection(position)
                else {
                    selectedName.text = "当前筛选没有作品，可切换筛选条件"
                    if (isTvDevice) filter.requestFocus()
                }
            }
        }
        filter.setOnClickListener {
            AlertDialog.Builder(this).setTitle("筛选作品")
                .setSingleChoiceItems(arrayOf("全部", "已收录封面地址", "最近看过"), childrenMoreFilter) { dialog, which ->
                    filterPositions[childrenMoreFilter] = grid.firstVisiblePosition to (grid.getChildAt(0)?.top ?: 0)
                    childrenMoreFilter = which; childrenMoreTitle = null; updateFilter()
                    filterPositions[which]?.let { saved -> grid.post {
                        if (saved.first in items.indices) grid.smoothScrollToPositionFromTop(saved.first, saved.second, 1)
                    } }
                    dialog.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        actions.addView(filter)
        actions.addView(button("重试当前封面") {
            val position = grid.selectedItemPosition
            val card = grid.selectedView as? LinearLayout
            if (position !in items.indices || card == null) toast("请先选择一部作品；手机可长按卡片")
            else {
                val image = (card.getChildAt(0) as FrameLayout).getChildAt(1) as ImageView
                retryChildrenCover(items[position], image, width, height)
            }
        })
        rootColumn.addView(hScroll(actions))
        rootColumn.addView(hint)
        rootColumn.addView(selectedName)
        grid = newGrid().apply {
            numColumns = columns
            setDrawSelectorOnTop(true)
            horizontalSpacing = dp(12); verticalSpacing = dp(12)
            clipChildren = false; clipToPadding = false
            setPadding(dp(4), dp(8), dp(4), dp(8))
            adapter = object : BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(position: Int) = items[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val card = convertView as? LinearLayout ?: childrenCard(width, height)
                    bindChildrenCard(card, items[position], width, height)
                    card.setOnClickListener(null)
                    card.setOnLongClickListener(null)
                    card.isFocusable = false; card.isClickable = false; card.isLongClickable = false
                    return card
                }
            }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val title = items.getOrNull(pos) ?: return
                    childrenMoreTitle = title
                    selectedName.text = "当前选择：$title"
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { selectedName.text = "选择作品查看完整片名" }
            }
            setOnItemClickListener { _, _, position, _ ->
                childrenMoreTitle = items.getOrNull(position)
                childrenMoreTitle?.let { doSearch(it) }
            }
            setOnItemLongClickListener { _, view, position, _ ->
                val card = view as? LinearLayout ?: return@setOnItemLongClickListener false
                val title = items.getOrNull(position) ?: return@setOnItemLongClickListener false
                val image = (card.getChildAt(0) as FrameLayout).getChildAt(1) as ImageView
                showChildrenCoverActions(title, image, width, height)
                true
            }
        }
        var lastPrefetchKey = ""
        grid.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) = Unit
            override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                if (visible <= 0 || first + visible >= total) return
                val start = first + visible
                val key = "$childrenMoreFilter:$start:${items.getOrNull(start)}"
                if (lastPrefetchKey == key) return
                lastPrefetchKey = key
                val next = items.drop(start).take(columns)
                childrenNearPrefetchJob?.cancel()
                childrenNearPrefetchJob = scope.launch {
                    delay(250)
                    if (currentScreen != "children_more" || childrenMoreGrid !== grid) return@launch
                    for (batch in next.chunked(2)) {
                        if (currentScreen != "children_more") break
                        batch.map { title -> async { queueChildrenCover(title, width, height)?.join() } }.awaitAll()
                    }
                }
            }
        })
        childrenMoreGrid = grid
        rootColumn.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val savedTitle = childrenMoreTitle
        updateFilter()
        setScreen(rootColumn, focusFirst = false)
        grid.post {
            if (currentScreen != "children_more") return@post
            val position = items.indexOf(savedTitle)
            if (position >= 0) grid.setSelection(position)
            if (isTvDevice && items.isNotEmpty()) grid.requestFocus()
        }
    }

    private fun showRecommend() {
        currentScreen = "recommend"
        scheduleRecommendRetry()
        childrenSearchOrigin = null
        homeHistoryCoverViews.clear()
        if (recommendItems.isEmpty() && !recommendRequested) {
            recommendRequested = true
            loadRecommend()
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Header and poster grid share the RecyclerView's outer gutters on both devices.
            setPadding(0, dp(22), 0, dp(16))
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
        col.addView(button("电视直播") { openLiveFromHome() }.apply {
            contentDescription = "电视直播"
            homeLiveEntry = this
        }, LinearLayout.LayoutParams(-1, dp(if (isTvDevice) 52 else 48)).apply { topMargin = dp(10) })
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
        if (recommendItems.isEmpty() && bootNotice == null) {
            col.addView(hintText("正在整理最新热门推荐…"))
        }

        showRecommendFeed(col)
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
        homeScroll = null
        val columns = if (isTvDevice) 6 else 2
        val restorePosition = homeRvPosition
        val restoreOffset = homeRvOffset
        val restoreTitle = homeFocusedTitle
        val restoreTitlePosition = restoreTitle?.let { title -> recommendItems.indexOfFirst { it.title == title } + 1 } ?: 0
        homeLastHeaderFocus = null
        val rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, columns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = if (position == 0) columns else 1
                }
            }
            setPadding(dp(if (isTvDevice) 48 else 20), 0, dp(if (isTvDevice) 48 else 20), dp(16))
            clipToPadding = false
            clipChildren = false
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
                homeRvOffset = lm.findViewByPosition(homeRvPosition)?.top?.minus(recyclerView.paddingTop) ?: 0
                val last = lm.findLastVisibleItemPosition()
                scheduleHomePosterPrefetch(rv, last, columns)
                if (suppressAutoLoad) {
                    // 刚恢复位置的那一次回滚不算「用户滑到底」
                    suppressAutoLoad = false
                    return
                }
                if (feed.itemCount - last <= 6 &&
                    !isLoadingMore && recommendRetry == null && !noMoreRecommend && recommendItems.isNotEmpty()
                ) {
                    loadRecommend(true)
                }
            }
        })
        if (isTvDevice) {
            homeBarRows.forEachIndexed { rowIndex, row ->
                for (i in 0 until row.childCount) row.getChildAt(i).setOnKeyListener { v, code, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false
                    else if (code == KeyEvent.KEYCODE_DPAD_DOWN && rowIndex == homeBarRows.lastIndex) {
                        homeLastHeaderFocus = v
                        val history = homeLiveEntry ?: homeHistoryFirst
                        if (history != null && history.isShown) history.requestFocus()
                        else if (recommendItems.isNotEmpty()) {
                            rv.scrollToPosition(1)
                            rv.postOnAnimation { rv.findViewHolderForAdapterPosition(1)?.itemView?.requestFocus() }
                        }
                        history != null && history.isShown || recommendItems.isNotEmpty()
                    } else false
                }
            }
        }
        setScreen(rv, focusFirst = restorePosition <= 0 && restoreOffset == 0 && !(isTvDevice && restoreTitlePosition > 0))
        if (restorePosition >= 0 && (restorePosition > 0 || restoreOffset != 0 || (isTvDevice && restoreTitlePosition > 0))) {
            suppressAutoLoad = true
            rv.post {
                (rv.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(restorePosition, restoreOffset)
                if (isTvDevice && restoreTitlePosition > 0) {
                    val pos = restoreTitlePosition
                    if (pos > 0) {
                        val target = rv.findViewHolderForAdapterPosition(pos)?.itemView
                        if (target != null) target.requestFocus()
                        else {
                            rv.scrollToPosition(pos)
                            rv.postOnAnimation { rv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
                        }
                    }
                }
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
            val latest = async(Dispatchers.IO) {
                runCatching { api.douban(type, "最新", 0, 8) }.getOrDefault(emptyList())
            }
            val popular = async(Dispatchers.IO) {
                runCatching { api.douban(type, "热门", 0, 8) }.getOrDefault(emptyList())
            }
            (latest.await() + popular.await()).distinctBy { it.title.trim() }
        }, { items ->
            if (items.isEmpty()) {
                toast("「$label」暂时没有推荐，保持原来的列表")
                return@runApiK
            }
            // 数据到手才换：分类、列表、分页状态一起切
            recommendRetryJob?.cancel()
            recommendRetryJob = null
            recommendRetry = null
            recommendRetryAttempts = 0
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

    private var recommendRetry: (() -> Unit)? = null
    private var recommendRetryJob: Job? = null
    private var recommendRetryAttempts = 0

    private fun scheduleRecommendRetry() {
        if (!quotaVisible || currentScreen != "recommend" || recommendRetry == null || recommendRetryJob?.isActive == true) return
        val waitMs = minOf(60_000L, 5_000L * (1L shl recommendRetryAttempts.coerceIn(0, 4)))
        recommendRetryJob = scope.launch {
            delay(waitMs)
            recommendRetryJob = null
            if (quotaVisible && currentScreen == "recommend") recommendRetry?.invoke()
        }
    }

    private fun queueRecommendRetry(more: Boolean) {
        recommendRetry = { loadRecommend(more) }
        scheduleRecommendRetry()
        recommendRetryAttempts = (recommendRetryAttempts + 1).coerceAtMost(4)
    }

    private fun loadRecommend(more: Boolean = false) {
        if (more && (isLoadingMore || noMoreRecommend)) return
        recommendRetryJob?.cancel()
        recommendRetryJob = null
        recommendRetry = null
        isLoadingMore = true
        val page = if (more) recommendPage + 1 else 0
        // 记下发起时的分类：切换分类的请求在另一个槽位，可能先回来了。
        // 这时这份结果已经过期，直接丢掉，否则会把「电影」的一页追加到「剧集」列表后面。
        val loadingType = mediaType
        val loadingTag = prefs.recommendTag
        // 翻页不要提示：以前滚到底部会弹一个「加载更多…」对话框，非常吵。
        // 首屏加载也有页面内的「正在整理最新热播剧集…」兜着，同样不需要胶囊。
        runApiK("recommend", null, {
            val tag = loadingTag
            // 混合模式两路各取 8；单选某个榜单就一次取 16，条数别变少
            val specs = if (tag.isEmpty()) listOf("最新" to 8, "热门" to 8) else listOf(tag to 16)
            val results = specs.map { (t, n) ->
                async(Dispatchers.IO) {
                    try { Result.success(api.douban(loadingType, t, page * n, n)) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                }
            }.awaitAll()
            val available = results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.title.trim() }
            if (available.isEmpty() && results.any { it.isFailure }) throw results.first { it.isFailure }.exceptionOrNull()!!
            available to results.any { it.isFailure }
        }, { (items, partialFailure) ->
            isLoadingMore = false
            if (loadingType != mediaType || loadingTag != prefs.recommendTag) return@runApiK
            if (items.isEmpty()) {
                if (more) {
                    noMoreRecommend = true
                    toast("已经到底了")
                } else {
                    queueRecommendRetry(more = false)
                }
            } else {
                if (partialFailure && !more) queueRecommendRetry(more = false) else recommendRetryAttempts = 0
                val appending = more && recommendItems.isNotEmpty() && currentScreen == "recommend"
                val previousCount = recommendItems.size
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
                    val added = recommendItems.size - previousCount
                    if (added > 0) homeRecycler?.adapter?.notifyItemRangeInserted(previousCount + 1, added)
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
        }, quiet = true, onError = {
            isLoadingMore = false
            if (loadingType == mediaType && loadingTag == prefs.recommendTag) {
                queueRecommendRetry(more)
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

    /** Preload only the next row, after visible requests have had a chance to start. */
    private fun scheduleHomePosterPrefetch(rv: RecyclerView, lastVisible: Int, columns: Int) {
        if (lastVisible < 0) return
        val generation = ++homePrefetchGeneration
        rv.postDelayed({
            if (!rv.isAttachedToWindow || currentScreen != "recommend" || generation != homePrefetchGeneration) return@postDelayed
            val metrics = recommendCellMetrics()
            // Position zero is the full-width header, so lastVisible is the next item index.
            for (index in lastVisible until minOf(lastVisible + columns, recommendItems.size)) {
                val item = recommendItems[index]
                imageLoader.load(item.cover, metrics[0], metrics[1], background = true,
                    needed = { rv.isAttachedToWindow && currentScreen == "recommend" && generation == homePrefetchGeneration }) { }
            }
        }, 250)
    }

    /** 把一个单元格填上第 position 条推荐（图片按 URL 校验，快速滚动不会串图） */
    private val posterSubscriptions = java.util.WeakHashMap<ImageView, java.util.concurrent.atomic.AtomicBoolean>()
    private fun posterSubscription(image: ImageView): java.util.concurrent.atomic.AtomicBoolean {
        posterSubscriptions.remove(image)?.set(false)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        posterSubscriptions[image] = active
        return active
    }

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
        val previousCover = img.tag as? String
        img.tag = item.cover
        if (previousCover != item.cover) img.setImageDrawable(null)
        imageLoader.load(item.cover, posterWidth, posterHeight, needed = posterSubscription(img)::get, onPreview = { bmp -> if (img.tag == item.cover) img.setImageBitmap(bmp) }) { bmp ->
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
                val cell = recommendCellView()
                if (isTvDevice) {
                    cell.isFocusable = true
                    applyFocusStyle(cell, PANEL, onFocusChanged = { focused ->
                        if (focused) {
                            homeFocusedTitle = cell.tag as? String
                            cell.requestRectangleOnScreen(android.graphics.Rect(-dp(8), -dp(8), cell.width + dp(8), cell.height + dp(8)))
                        }
                    }, focusScale = 1.02f)
                    cell.setOnKeyListener { _, code, event ->
                        val position = (cell.layoutParams as? RecyclerView.LayoutParams)?.viewAdapterPosition ?: -1
                        if (code == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && position in 1..6) {
                            (homeHistoryFirst?.takeIf { it.isShown } ?: homeLastHeaderFocus ?: homeBarRows.lastOrNull()?.getChildAt(0))?.requestFocus() == true
                        } else false
                    }
                }
                Holder(cell)
            }

        override fun onViewRecycled(holder: Holder) {
            if (holder.itemViewType == TYPE_CELL) {
                val image = (holder.itemView as? LinearLayout)?.getChildAt(0) as? ImageView
                if (image != null) posterSubscriptions.remove(image)?.set(false)
            }
            super.onViewRecycled(holder)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position == 0) return
            holder.itemView.tag = recommendItems.getOrNull(position - 1)?.title
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
