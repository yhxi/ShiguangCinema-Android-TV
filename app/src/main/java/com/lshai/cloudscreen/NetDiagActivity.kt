package com.lshai.cloudscreen

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 网络诊断。
 *
 * 「慢」是个笼统的感受，可能卡在五个完全不同的地方：
 *
 *   1. 本机网络（WiFi 信号差 / 走了 VPN）
 *   2. 连接测试（DNS / TCP / TLS / 首字节），只作独立观测，不相加
 *   3. 服务端处理（自己算得久，或者回上游豆瓣慢）
 *   4. 采集站（搜索时服务端要挨个问它们）
 *   5. 图片（封面走直连还是回退代理）
 *
 * 这里按五类现象计时。接口结果是端到端耗时，不能从一次完整请求中
 * 减去另一笔完整请求，伪造“纯网络”或“纯服务端”的精确数字。
 */
class NetDiagActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: Api

    /** 结果行：key → 右侧数值 TextView */
    private val valueViews = LinkedHashMap<String, TextView>()

    /** 结果行：key → 下面那行小字说明 */
    private val detailViews = LinkedHashMap<String, TextView>()

    /** 双服务器对比：表格格子与各家结果（两线程并行写，锁保护） */
    private val cmpCellViews = LinkedHashMap<String, TextView>()
    private val cmpResults = LinkedHashMap<String, CompareResult>()
    private val cmpLock = Any()

    /** 报告的纯文本（复制用） */
    private val report = StringBuilder()

    private lateinit var body: LinearLayout
    private lateinit var startBtn: TextView
    private var running = false
    private var tickSec = 0

    /**
     * 检测代际：每次「开始诊断 / 对比两台」都 ++。
     * 允许随时打断上一次：旧线程的界面更新（genTL 里的旧代际）会被丢弃，避免污染新结果。
     */
    @Volatile
    private var runGen = 0
    private val genTL = ThreadLocal<Int>()

    @Volatile
    private var tickGen: Int? = null
    private var tickLabel = "诊断中"

    /** 当前勾选参与诊断的服务端数量：1 = 单测，2 = 对比（决定主按钮文案） */
    @Volatile
    private var selCount = 2

    /** 按钮上的秒表：诊断要跑半分钟，光写「诊断中…」用户不知道是死是活 */
    private val tick = object : Runnable {
        override fun run() {
            val g = tickGen
            if (g == null || g != runGen || !running) return
            tickSec++
            startBtn.text = "$tickLabel… ${tickSec}s"
            startBtn.postDelayed(this, 1_000)
        }
    }

    /** 界面更新统一走这里：带代际校验，被新检测接管的旧线程更新直接丢弃 */
    private fun uiIf(block: () -> Unit) {
        val g = genTL.get()
        ui { if (g == null || g == runGen) block() }
    }

    /** 状态接口的端到端耗时，只作同次测试的参考，不冒充每次请求的固定链路成本。 */
    private var statusMs = 0L
    private var directImageMs = 0L
    private var proxiedImageMs = 0L
    private var serverHost = ""

    private lateinit var subtitleTv: TextView

    /** 显示用服务端名：预设显示名字（不暴露网址），自填的显示域名 */
    private fun serverDisplayName(): String =
        ServerPresets.ALL.firstOrNull { it.url.trimEnd('/') == api.baseUrl.trimEnd('/') }?.name
            ?: serverHost

    private fun subtitleText(): String =
        "服务端 ${serverDisplayName()}\n" +
            "逐项测一遍：本机网络 → 连接 → 接口 → 图片 → 采集站。\n" +
            "全程约 30~60 秒，结果会一项一项冒出来。"

    /** 切到指定服务端并立即诊断（会话 Cookie 优先用已保存的） */
    private fun useServer(url: String) {
        val cookie = if (url.trimEnd('/') == prefs.serverUrl.trimEnd('/')) prefs.cookie
            else prefs.savedServers()
                .firstOrNull { it.url.trimEnd('/') == url.trimEnd('/') }?.cookie.orEmpty()
        api = Api(url).apply { restoreCookie(cookie) }
        serverHost = runCatching { URL(api.baseUrl).host }.getOrDefault("")
        subtitleTv.text = subtitleText()
        start()
    }

    private data class CompareResult(
        val preset: ServerPreset,
        val statusMs: Long,
        val statusOk: Boolean,
        val doubanMs: Long,
        val doubanOk: Boolean,
    ) {
        // 推荐接口权重更高：它反映服务端真实处理能力（含上游），状态接口更像纯链路
        val score: Long get() = doubanMs * 2 + statusMs
    }

    /** 双服务器对比：两台并行实测，表格格子实时刷新，直观看出谁优谁劣。随时可点，立即接管当前检测 */
    private fun compareServers() {
        val myGen = ++runGen
        running = true
        body.removeAllViews()
        statusMs = 0L
        directImageMs = 0L
        proxiedImageMs = 0L
        tickSec = 0
        tickLabel = "对比中"
        tickGen = myGen
        startBtn.text = "对比中… 0s"
        startBtn.alpha = 0.5f
        startBtn.post(tick)
        cmpCellViews.clear()
        synchronized(cmpLock) { cmpResults.clear() }

        Thread {
            genTL.set(myGen)
            try {
                report.setLength(0)
                report.append("时光影院 双服务器对比 · v").append(BuildConfig.VERSION_NAME).append('\n')
                report.append("时间：").append(nowText()).append("\n\n")
                ui {
                    section("双服务器对比 · 两台并行实测")
                    buildCompareTable()
                }
                // 两台并行：各自独立线程采样，格子随每次请求实时刷新
                val workers = ServerPresets.ALL.map { preset ->
                    Thread {
                        genTL.set(myGen)
                        compareOne(preset)
                    }
                }
                workers.forEach { it.start() }
                workers.forEach { it.join() }
                if (myGen != runGen) return@Thread

                val results = synchronized(cmpLock) { cmpResults.values.toList() }
                ui { if (myGen == runGen) finishCompareTable(results) }
            } finally {
                if (myGen == runGen) {
                    running = false
                    tickGen = null
                    ui {
                        startBtn.alpha = 1f
                        startBtn.text = if (selCount >= 2) "一键对比" else "开始诊断"
                        startBtn.removeCallbacks(tick)
                    }
                }
            }
        }.start()
    }

    /** 单台服务端的对比采样：状态 3 次 + 推荐 1 次，每完成一次请求就刷一次格子 */
    private fun compareOne(preset: ServerPreset) {
        val key = preset.name
        val candidate = Api(preset.url).apply {
            restoreCookie(
                if (preset.url.trimEnd('/') == prefs.serverUrl.trimEnd('/')) prefs.cookie
                else prefs.savedServers().firstOrNull {
                    it.url.trimEnd('/') == preset.url.trimEnd('/')
                }?.cookie.orEmpty(),
            )
        }
        // 先补登录：未验证时部分接口 401，耗时没有可比性
        runCatching {
            val st = candidate.status()
            if (st.passwordRequired && !st.verified) candidate.login(preset.password)
        }
        val times = mutableListOf<Long>()
        var statusOk = false
        repeat(3) {
            val (ms, ok) = timeOf { candidate.status(); true }
            if (ok) {
                times.add(ms)
                statusOk = true
            }
            ui {
                setCell(
                    "cmp_status_$key",
                    if (ok) "$ms ms（${times.size}/3）" else "超时（${times.size}/3）",
                    if (ok) GREY else RED,
                )
            }
            Thread.sleep(200)
        }
        val statusMed = times.sorted().getOrNull(times.size / 2) ?: 0L
        ui {
            setCell(
                "cmp_status_$key",
                if (statusOk) "$statusMed ms" else "无响应",
                if (statusOk) GREY else RED,
            )
        }
        val (dbMs, dbOk) = timeOf { candidate.douban("tv", "热门", 0, 16); true }
        ui {
            setCell("cmp_douban_$key", if (dbOk) "$dbMs ms" else "失败", if (dbOk) GREY else RED)
        }
        appendReport(
            preset.name, "状态 $statusMed ms · 推荐 $dbMs ms",
            if (statusOk) (if (dbOk) "双通" else "仅状态通") else "无响应",
        )
        synchronized(cmpLock) {
            cmpResults[key] = CompareResult(preset, statusMed, statusOk, dbMs, dbOk)
        }
    }

    /** 对比表：表头两列服务端名，下面每行一个指标；格子引用存进 cmpCellViews 实时刷 */
    private fun buildCompareTable() {
        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = cardBg()
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            text = "指标"
            setTextColor(HINT)
            setTextSize(12.5f)
        }, cellWeight(0.7f))
        ServerPresets.ALL.forEach { preset ->
            val current = preset.url.trimEnd('/') == prefs.serverUrl.trimEnd('/')
            val tv = TextView(this).apply {
                text = preset.name + if (current) "（当前）" else ""
                setTextColor(INK)
                setTextSize(14f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            cmpCellViews["cmp_head_${preset.name}"] = tv
            head.addView(tv, cellWeight(1f))
        }
        table.addView(head)

        fun metricRow(label: String, key1: String, key2: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(0xFFF2F5EE.toInt())
                    cornerRadius = dp(10).toFloat()
                }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(INK)
                setTextSize(13f)
            }, cellWeight(0.7f))
            listOf(key1, key2).forEach { k ->
                val cell = TextView(this).apply {
                    text = "…"
                    setTextColor(GREY)
                    setTextSize(13.5f)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                }
                cmpCellViews[k] = cell
                row.addView(cell, cellWeight(1f))
            }
            table.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
        val p1 = ServerPresets.ALL[0].name
        val p2 = ServerPresets.ALL[1].name
        metricRow("状态接口 · 3 次中位", "cmp_status_$p1", "cmp_status_$p2")
        metricRow("推荐接口 · 真实处理", "cmp_douban_$p1", "cmp_douban_$p2")

        body.addView(table, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
    }

    /** 两台都测完：逐指标标出优/劣（✓ 绿 / 红），给出结论卡与启用按钮 */
    private fun finishCompareTable(results: List<CompareResult>) {
        val alive = results.filter { it.statusOk }
        if (results.size == 2 && alive.size == 2) {
            val (a, b) = results
            markWinner(
                "cmp_status_${a.preset.name}", "cmp_status_${b.preset.name}",
                a.statusMs, b.statusMs,
            )
            markWinner(
                "cmp_douban_${a.preset.name}", "cmp_douban_${b.preset.name}",
                a.doubanMs, b.doubanMs,
            )
        } else {
            results.forEach { r ->
                if (!r.statusOk) setCell("cmp_status_${r.preset.name}", "无响应", RED)
            }
        }

        val verdict = when {
            alive.isEmpty() -> "两台服务端都连不上，请检查本机网络后重试"
            alive.size == 1 -> "建议启用 ${alive[0].preset.name}：另一台无响应"
            else -> {
                val best = alive.minByOrNull { it.score }!!
                val other = alive.first { it !== best }
                val parts = mutableListOf<String>()
                if (other.statusMs > best.statusMs) parts.add("状态快 ${other.statusMs - best.statusMs} ms")
                if (other.doubanMs > best.doubanMs) parts.add("推荐快 ${other.doubanMs - best.doubanMs} ms")
                val detail = if (parts.isEmpty()) "" else "（${parts.joinToString("，")}）"
                setCell("cmp_head_${best.preset.name}", "★ ${best.preset.name}", ACCENT)
                "建议启用 ${best.preset.name}$detail"
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), ACCENT)
            }
        }
        card.addView(TextView(this).apply {
            text = verdict
            setTextColor(INK)
            setTextSize(15f)
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "点下面的按钮切换当前使用的服务端；打开 App 的默认服务端（tv）不变。"
            setTextColor(HINT)
            setTextSize(12f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(6), 0, dp(10))
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ServerPresets.ALL.forEach { preset ->
            bar.addView(actionButton("启用 ${preset.name}") { enableServer(preset) })
        }
        card.addView(bar)
        body.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
    }

    /** 一对格子里标出快慢：快的加 ✓ 变绿，慢的变红；一样快则都不动 */
    private fun markWinner(keyA: String, keyB: String, msA: Long, msB: Long) {
        if (msA <= 0 || msB <= 0) return
        val aWins = msA <= msB
        val winKey = if (aWins) keyA else keyB
        val loseKey = if (aWins) keyB else keyA
        val winText = cmpCellViews[winKey]?.text?.toString().orEmpty()
        val loseText = cmpCellViews[loseKey]?.text?.toString().orEmpty()
        if (msA == msB) return
        setCell(winKey, "$winText ✓", GREEN)
        setCell(loseKey, loseText, RED)
    }

    private fun setCell(key: String, text: String, color: Int) {
        cmpCellViews[key]?.apply {
            this.text = text
            setTextColor(color)
        }
    }

    private fun cellWeight(w: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    /** 把预设服务端设为当前使用：登录验证通过后写回本地配置 */
    private fun enableServer(preset: ServerPreset) {
        Thread {
            runCatching {
                val candidate = Api(preset.url).apply { restoreCookie(prefs.cookie) }
                val st = candidate.status()
                val verified = if (st.passwordRequired && !st.verified) {
                    candidate.login(preset.password).verified
                } else {
                    st.verified
                }
                if (st.passwordRequired && !verified) {
                    ui { Toast.makeText(this, "密码未通过验证，无法启用 ${preset.name}", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                api = candidate
                serverHost = runCatching { URL(preset.url).host }.getOrDefault("")
                prefs.serverUrl = preset.url.trimEnd('/')
                prefs.password = preset.password
                prefs.cookie = candidate.currentCookie().orEmpty()
                prefs.rememberServer(preset.url, preset.password, prefs.cookie)
                ui {
                    subtitleTv.text = subtitleText()
                    Toast.makeText(this, "已启用 ${preset.name}", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                ui { Toast.makeText(this, "启用失败：${it.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = Api(prefs.serverUrl.ifEmpty { BuildConfig.DEFAULT_URL })
            .apply { restoreCookie(prefs.cookie) }
        serverHost = runCatching { URL(api.baseUrl).host }.getOrDefault("")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE_BG)
            setPadding(dp(20), dp(18), dp(20), dp(10))
        }

        // 顶栏：品牌 + 「← 返回主页」胶囊（与主界面各子页同款）
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            setText("▶")
            setTextSize(15f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(9).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        top.addView(TextView(this).apply {
            text = "时光影院"
            setTextColor(INK)
            setTextSize(17f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        })
        top.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        top.addView(TextView(this).apply {
            text = "← 返回主页"
            setTextColor(INK)
            setTextSize(14f)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = cardBg(strokeColor = BORDER, strokeW = 1)
            isClickable = true
            isFocusable = true
            setOnClickListener { goHome() }
            setOnFocusChangeListener { v, f ->
                v.background = cardBg(
                    strokeColor = if (f) ACCENT else BORDER,
                    strokeW = if (f) 2 else 1,
                )
            }
        })
        root.addView(top)

        root.addView(TextView(this).apply {
            text = "网络诊断"
            setTextColor(INK)
            setTextSize(22f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(16), 0, 0)
        })
        subtitleTv = TextView(this).apply {
            text = subtitleText()
            setTextColor(HINT)
            setTextSize(13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(subtitleTv)

        // 预设服务端卡片：每台一个复选框 —— 勾一台点「开始诊断」单测它，全选则一键对比
        val selected = HashSet<String>().apply { addAll(ServerPresets.ALL.map { it.name }) }
        val selCardViews = HashMap<String, LinearLayout>()
        fun primaryLabel(): String = if (selected.size >= 2) "一键对比" else "开始诊断"

        val serverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(4))
        }
        ServerPresets.ALL.forEachIndexed { i, preset ->
            val current = preset.url.trimEnd('/') == prefs.serverUrl.trimEnd('/')
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(10), dp(12))
                background = cardBg(strokeColor = if (current) ACCENT else BORDER, strokeW = if (current) 2 else 1)
                isClickable = true
                isFocusable = true
            }
            fun restyleCard(focused: Boolean) {
                card.background = cardBg(
                    strokeColor = when {
                        focused -> ACCENT
                        preset.name in selected -> ACCENT
                        else -> BORDER
                    },
                    strokeW = when {
                        focused -> 3
                        preset.name in selected || current -> 2
                        else -> 1
                    },
                )
            }
            val words = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }
            words.addView(TextView(this@NetDiagActivity).apply {
                text = preset.name + if (current) "（当前）" else ""
                setTextColor(INK)
                setTextSize(16f)
                setTypeface(null, Typeface.BOLD)
            })
            words.addView(TextView(this@NetDiagActivity).apply {
                text = "勾一台单测 · 全选对比"
                setTextColor(HINT)
                setTextSize(11.5f)
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val cb = android.widget.CheckBox(this).apply {
                isChecked = true
                setOnCheckedChangeListener { _, now ->
                    if (now) selected.add(preset.name) else selected.remove(preset.name)
                    selCount = selected.size
                    restyleCard(false)
                    if (!running) startBtn.text = primaryLabel()
                }
            }
            card.addView(cb)
            card.setOnClickListener { cb.toggle() }
            card.setOnFocusChangeListener { v, f -> restyleCard(f) }
            selCardViews[preset.name] = card
            restyleCard(false)
            serverRow.addView(
                card,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(10)
                },
            )
        }
        root.addView(serverRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        startBtn = actionButton("一键对比", primary = true) {
            when (selected.size) {
                0 -> Toast.makeText(this, "先勾选至少一台服务端", Toast.LENGTH_SHORT).show()
                1 -> ServerPresets.ALL
                    .firstOrNull { it.name in selected }
                    ?.let { useServer(it.url) }
                else -> compareServers()
            }
        }
        actions.addView(startBtn)
        actions.addView(actionButton("复制报告") { copyReport() })
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(actions)
            },
        )

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(20))
        }
        root.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(body)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)
        // 不自动开测：进页面先给一张待机提示，用户自己决定单测还是对比
        body.addView(TextView(this).apply {
            text = "还没开始检测。\n\n· 勾一台 → 点「开始诊断」单独看这台\n· 两台都勾 → 点「一键对比」，并行实测并给出建议"
            setTextColor(HINT)
            setTextSize(13.5f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(4), dp(20), dp(4), dp(4))
        })
    }

    /** 统一卡片底：白底圆角，可指定描边颜色与宽度（dp） */
    private fun cardBg(fillColor: Int = PANEL, strokeColor: Int = BORDER, strokeW: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(14).toFloat()
            if (strokeW > 0) setStroke(dp(strokeW), strokeColor)
        }

    /* ------------------------------------------------------------ 探测流程 */

    private fun start() {
        val myGen = ++runGen
        running = true
        valueViews.clear()
        detailViews.clear()
        report.setLength(0)
        body.removeAllViews()
        statusMs = 0L
        directImageMs = 0L
        proxiedImageMs = 0L
        tickSec = 0
        tickLabel = "诊断中"
        tickGen = myGen
        startBtn.text = "诊断中… 0s"
        startBtn.alpha = 0.5f
        startBtn.post(tick)

        Thread {
            genTL.set(myGen)
            try {
                report.append("时光影院 网络诊断 · v").append(BuildConfig.VERSION_NAME).append('\n')
                report.append("服务端：").append(serverHost).append('\n')
                report.append("时间：").append(nowText()).append("\n\n")
                probeLocal()
                if (myGen != runGen) return@Thread
                probeLink()
                if (myGen != runGen) return@Thread
                probeApis()
                if (myGen != runGen) return@Thread
                probeImages()
                if (myGen != runGen) return@Thread
                probeSources()
                if (myGen != runGen) return@Thread
                conclude()
            } catch (t: Throwable) {
                uiIf { addDetail("err", "诊断中断：${t.javaClass.simpleName} ${t.message}") }
            } finally {
                if (myGen == runGen) {
                    running = false
                    tickGen = null
                    ui {
                        startBtn.removeCallbacks(tick)
                        startBtn.text = "重新诊断"
                        startBtn.alpha = 1f
                    }
                }
            }
        }.start()
    }

    /** 第一层：本机网络。很多「服务端慢」其实是 WiFi 走了 VPN */
    private fun probeLocal() {
        ui { section("① 本机网络") }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork)
        val known = addRow("net", "网络类型")
        addRow("vpn", "VPN / 代理通道")
        addRow("bw", "系统估算带宽")

        val type = when {
            nc == null -> "无网络"
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        }
        val vpn = nc?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val down = (nc?.linkDownstreamBandwidthKbps ?: 0) / 1024
        val hasInternet = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        ui {
            setRow("net", type + if (hasInternet) "" else "（无外网）", if (nc == null) RED else GREEN)
            setRow("vpn", if (vpn) "有" else "无", if (vpn) AMBER else GREEN)
            setRow("bw", if (down > 0) "≈ $down Mbps" else "未知", GREY)
        }
        appendReport("网络类型", type, "")
        appendReport("VPN", if (vpn) "有" else "无", "")
        if (vpn) {
            ui { addDetail("vpn", "系统报告存在 VPN 通道。如果那是代理软件，测出来的耗时不代表直连真实情况。") }
        }
    }

    /** 第二层：分别测连接阶段；首字节已包含建立连接，不能与前三项相加。 */
    private fun probeLink() {
        ui { section("② 连接测试（DNS 3 次取最好）") }
        if (serverHost.isEmpty()) {
            ui { addDetail("link", "服务端地址解析不出来，跳过") }
            return
        }
        val dns = addRow("dns", "DNS 解析")
        val tcp = addRow("tcp", "TCP 连接")
        val tls = addRow("tls", "TLS 握手")
        val ttfb = addRow("ttfb", "首字节（首页 HTML）")

        // DNS：跑 3 次，同时记录解析到的地址
        val dnsTimes = ArrayList<Long>()
        var addrs: List<String> = emptyList()
        repeat(3) {
            val t = System.nanoTime()
            runCatching {
                val all = InetAddress.getAllByName(serverHost)
                addrs = all.mapNotNull { it.hostAddress }
            }
            dnsTimes.add((System.nanoTime() - t) / 1_000_000L)
        }
        val dnsBest = dnsTimes.minOrNull() ?: -1
        ui {
            setRow("dns", "$dnsBest ms", grade(dnsBest, 80, 300))
            addDetail("dns", "三次：" + dnsTimes.joinToString(" / ") + " ms\n解析到：" +
                (if (addrs.isEmpty()) "失败" else addrs.joinToString(", ")))
        }
        appendReport("DNS", "$dnsBest ms", addrs.joinToString(", "))
        if (addrs.isEmpty()) return

        // DNS 交叉验证：系统解析 vs 公共 DoH。Fake IP 和 CDN 调度都可能
        // 导致两者不同，不能单凭不一致判定污染。
        probeDnsCrossCheck(addrs)

        // TCP：连第一个地址的 443。Fake IP 时测到的是本地代理入口，不是 Cloudflare。
        val ip = addrs.first().substringBefore('%')
        val tcpMs = timeOf {
            Socket().use { s -> s.connect(InetSocketAddress(ip, 443), 8000) }
            true
        }
        ui {
            setRow("tcp", "${tcpMs.first} ms", grade(tcpMs.first, 120, 400))
            if (!tcpMs.second) addDetail("tcp", "连不上 $ip:443（被墙 / 端口被拦 / 地址过期）")
        }
        appendReport("TCP", "${tcpMs.first} ms", if (tcpMs.second) ip else "连接失败")

        // TLS：新建 TCP 后再握手，因此此项自身也包含一次 TCP 连接。
        val tlsMs = timeOf {
            val raw = Socket()
            raw.connect(InetSocketAddress(ip, 443), 8000)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, serverHost, 443, true) as SSLSocket
            ssl.startHandshake()
            ssl.close()
            true
        }
        ui { setRow("tls", "${tlsMs.first} ms", grade(tlsMs.first, 250, 700)) }
        appendReport("TLS", "${tlsMs.first} ms", "")

        // 首字节：一个完整 GET 到响应头，可能包含 DNS/TCP/TLS/重定向。
        val ttfbMs = timeOf { httpHead(api.baseUrl, 12_000) }
        ui {
            setRow("ttfb", "${ttfbMs.first} ms", grade(ttfbMs.first, 600, 1500))
            addDetail(
                "ttfb",
                "这是一次完整首页请求；不代表后续 API 的固定等待，也不能再与 DNS/TCP/TLS 相加",
            )
        }
        appendReport("首页首字节（完整请求）", "${ttfbMs.first} ms", if (ttfbMs.second) "成功" else "失败")
    }

    /**
     * DNS 交叉验证。
     *
     * 用公共 DoH 把同一个域名再解析一遍，和系统解析结果对比。
     * 不一致只是一条线索：CDN 地域调度、不同解析器和 Fake IP 都能造成差异。
     */
    private fun probeDnsCrossCheck(systemAddrs: List<String>) {
        ui { addRow("dnsx", "DNS 交叉验证") }
        if (systemAddrs.any(::isFakeIp)) {
            ui {
                setRow("dnsx", "检测到 Fake IP", AMBER)
                addDetail("dnsx", "系统返回 198.18.0.0/15 地址，常见于代理或路由器的 Fake IP 模式；不能据此判定 DNS 被劫持。TCP/TLS 测到的也可能是本地代理入口。")
            }
            appendReport("DNS 交叉验证", "检测到 Fake IP", "系统解析: ${systemAddrs.joinToString(", ")}；跳过劫持判定")
            return
        }
        val t = System.nanoTime()
        var failed = false
        val dohAddrs = runCatching {
            val conn = URL("https://doh.pub/dns-query?name=$serverHost&type=A")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.setRequestProperty("accept", "application/dns-json")
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            runCatching { conn.disconnect() }
            val arr = org.json.JSONObject(text).optJSONArray("Answer")
            val out = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optInt("type", 0) == 1) {
                        val d = o.optString("data", "")
                        if (d.isNotEmpty()) out.add(d)
                    }
                }
            }
            out
        }.getOrElse {
            failed = true
            emptyList()
        }
        val ms = (System.nanoTime() - t) / 1_000_000L

        // 只比 IPv4：系统解析可能还带 IPv6，混在一起比没意义
        val sysV4 = systemAddrs.filter { it.contains('.') }.map { it.substringBefore('%') }.toSet()
        val dohSet = dohAddrs.toSet()
        val same = sysV4.isNotEmpty() && dohSet.isNotEmpty() && sysV4.intersect(dohSet).isNotEmpty()

        ui {
            when {
                failed || dohSet.isEmpty() -> {
                    setRow("dnsx", "无法验证 · $ms ms", GREY)
                    addDetail("dnsx", "DoH 查询失败（doh.pub 可能被拦），跳过对比")
                }
                same -> {
                    setRow("dnsx", "一致", GREEN)
                    addDetail("dnsx", "DoH 也解析到 ${dohSet.joinToString(", ")}；本次结果一致")
                }
                else -> {
                    setRow("dnsx", "不一致（待核实）", AMBER)
                    addDetail(
                        "dnsx",
                        "系统解析：${sysV4.joinToString(", ").ifEmpty { "无 IPv4" }}\n" +
                            "DoH 解析：${dohSet.joinToString(", ")}\n" +
                            "不同解析器或 CDN 调度也会返回不同地址，不能仅凭这一项断定 DNS 污染。",
                    )
                }
            }
        }
        appendReport(
            "DNS 交叉验证",
            if (failed) "DoH 失败" else if (same) "一致" else "不一致",
            "DoH: ${dohSet.joinToString(", ")}",
        )
    }

    /** 第三层：服务端各接口。扣掉链路成本才是服务端真正干活的时间 */
    private fun probeApis() {
        ui { section("③ 服务端接口") }
        val r0 = addRow("api_status", "/api/status")
        val r1 = addRow("api_douban", "/api/douban")
        val r2 = addRow("api_search", "/api/search")
        val r3 = addRow("api_detail", "/api/detail")

        // status：顺带验证鉴权，后面的接口都靠它
        var authed = false
        val st = timeOf {
            val s = api.status()
            authed = !(s.passwordRequired && !s.verified)
            true
        }
        if (st.second) statusMs = st.first
        ui {
            setRow("api_status", "${st.first} ms", grade(st.first, 1200, 3000))
            addDetail(
                "api_status",
                "端到端耗时，供同次测试对照；无法单独推算服务端处理时间" +
                    if (authed) " · 已登录" else " · 未登录（接口可能直接 401）",
            )
        }
        appendReport("/api/status", "${st.first} ms", if (st.second) "成功" else "失败")

        val db = timeOf {
            api.douban("tv", "热门", 0, 16)
            true
        }
        ui {
            setRow("api_douban", "${db.first} ms", grade(db.first, 2000, 5000))
            addDetail("api_douban", "端到端耗时；服务端还需访问上游推荐服务")
        }
        appendReport("/api/douban", "${db.first} ms", if (db.second) "成功" else "失败")

        // 搜索：用最近搜过的词，没有就随便挑一个常见字
        val kw = prefs.searchHistory().firstOrNull() ?: "海"
        val sources = prefs.sources().filter { it.enabled }
        val se = timeOf {
            api.search(kw, sources)
            true
        }
        ui {
            setRow("api_search", "${se.first} ms", grade(se.first, 4000, 9000))
            addDetail(
                "api_search",
                "端到端耗时 · 关键词「$kw」· ${sources.size} 个源\n" +
                    "服务端会查询采集站；单凭总耗时无法准确拆分网络和采集站时间",
            )
        }
        appendReport("/api/search", "${se.first} ms", "${if (se.second) "成功" else "失败"} · $kw · ${sources.size}源")

        // 详情：拿历史里最近一部有 vodId 的，没有就跳过（不凭空造请求）
        val hist = prefs.history().firstOrNull { it.vodId.isNotEmpty() && it.sourceUrl.isNotEmpty() }
        if (hist == null) {
            ui {
                setRow("api_detail", "跳过", GREY)
                addDetail("api_detail", "观看历史里没有带 vodId 的记录，无法测")
            }
        } else {
            val conf = prefs.sources().firstOrNull { it.url == hist.sourceUrl }
                ?: SourceConfig(hist.sourceName, hist.sourceUrl)
            val dt = timeOf {
                api.detail(hist.vodId, conf)
                true
            }
            ui {
                setRow("api_detail", "${dt.first} ms", grade(dt.first, 3000, 7000))
                addDetail("api_detail", "端到端耗时 · 《${hist.seriesTitle.ifEmpty { hist.title }}》")
            }
            appendReport("/api/detail", "${dt.first} ms", if (dt.second) "成功" else "失败")
        }
    }

    /** 第四层：图片。直连 vs 服务端代理，差多少直接看得出来 */
    private fun probeImages() {
        ui { section("④ 封面图") }
        addRow("img_direct", "豆瓣直连")
        addRow("img_proxy", "服务端代理")
        val sample = "https://img1.doubanio.com/view/photo/s_ratio_poster/public/p2915295906.jpg"

        val direct = timeOf { api.probeDirectImage(sample) != null }
        if (direct.second) directImageMs = direct.first
        ui {
            setRow("img_direct", "${direct.first} ms", grade(direct.first, 800, 2500))
            if (!direct.second) {
                addDetail("img_direct", "直连失败（被墙 / 图床拒绝 / 超时），运行时会回退代理")
            }
        }
        appendReport("图片直连", "${direct.first} ms", if (direct.second) "成功" else "失败")

        val proxied = timeOf { api.probeProxiedImage(sample) != null }
        if (proxied.second) proxiedImageMs = proxied.first
        ui {
            setRow("img_proxy", "${proxied.first} ms", grade(proxied.first, 1500, 4000))
            if (direct.second && proxied.second) {
                val saved = proxied.first - direct.first
                addDetail(
                    "img_proxy",
                    if (saved > 0) "本次样本直连比代理快 $saved ms；图片通常并发加载，不能按海报数量累加时间"
                    else "这次直连反而慢 ${-saved} ms，可能刚好网络抖动",
                )
            }
        }
        appendReport("图片代理", "${proxied.first} ms", if (proxied.second) "成功" else "失败")
    }

    /** 第五层：采集站。逐个并发问一遍，把「谁在拖后腿」摆出来 */
    private fun probeSources() {
        ui { section("⑤ 采集站（逐个，4 并发）") }
        val sources = prefs.sources().filter { it.enabled }
        if (sources.isEmpty()) {
            ui { addDetail("src", "没有启用的片源") }
            return
        }
        ui { addDetail("src", "共 ${sources.size} 个；「超时」的那些每个都会拖慢一次搜索") }

        val pool = Executors.newFixedThreadPool(4)
        var slow = 0
        var dead = 0
        try {
            val futures = sources.map { s ->
                val key = "src_" + s.url.hashCode()
                ui { addRow(key, s.name.ifEmpty { hostOf(s.url) }) }
                s to pool.submit<Pair<Long, Pair<Boolean, Int>>> {
                    val t = System.nanoTime()
                    val r = runCatching { api.testSource(s.url) }
                        .getOrDefault(false to -1)
                    val ms = (System.nanoTime() - t) / 1_000_000L
                    ms to r
                }
            }
            futures.forEach { (s, f) ->
                val data = runCatching { f.get(30, TimeUnit.SECONDS) }.getOrNull()
                if (data == null) {
                    dead++
                    val key = "src_" + s.url.hashCode()
                    ui { setRow(key, "失败/超时", RED) }
                    appendReport("源·" + s.name.ifEmpty { hostOf(s.url) }, "超时", "失败")
                    return@forEach
                }
                val (ms, res) = data
                val key = "src_" + s.url.hashCode()
                val ok = res.first
                if (!ok) dead++
                else if (ms > 4000) slow++
                ui {
                    setRow(
                        key,
                        if (ok) "$ms ms · ${res.second} 条${if (res.second == 0) "（可连接）" else ""}" else "失败/超时 · $ms ms",
                        when {
                            !ok -> RED
                            ms > 4000 -> AMBER
                            else -> GREEN
                        },
                    )
                }
                appendReport(
                    "源·" + s.name.ifEmpty { hostOf(s.url) },
                    "$ms ms",
                    if (ok) "${res.second} 条" else "失败",
                )
            }
        } finally {
            pool.shutdownNow()
        }
        ui {
            if (dead > 0 || slow > 0) {
                addDetail(
                    "src",
                    "有障碍：$dead 个连不上，$slow 个超过 4 秒。\n" +
                        "去「片源管理」把它们关掉，搜索会明显变快 —— 服务端要等最慢的那个。",
                )
            } else {
                addDetail("src", "本次测试没有连接失败或超过 4 秒的源；0 条只代表该源本次未返回结果。")
            }
        }
        appendReport("源小结", "$dead 个失败 / $slow 个慢", "")
    }

    /** 结论只比较端到端观测值，不把完整请求的耗时当作每次请求的固定链路成本。 */
    private fun conclude() {
        ui { section("⑥ 结论") }
        val sb = StringBuilder()
        val tips = ArrayList<String>()

        if (statusMs > 0) {
            sb.append("· 状态接口端到端 $statusMs ms，可作为本次测试的轻量请求参考；不是纯网络时延。\n")
        } else {
            sb.append("· 状态接口未成功，无法建立同次测试的请求参考。\n")
        }

        // 搜索包含采集站耗时，但无法从总时间里准确拆出“网络”和“服务端”各占多少。
        val searchRow = report.toString().lines().firstOrNull { it.startsWith("/api/search") }
        if (searchRow != null) {
            val total = Regex("""(\d+) ms""").find(searchRow)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            if (total > 0) {
                sb.append("· 搜索端到端 $total ms；其中有多少是网络、服务端或采集站耗时，本次测试无法精确拆分。\n")
                if (total > 3000) tips.add("搜索等待较明显；可先减少不常用的源，再用同一关键词复测。")
            }
        }

        if (directImageMs > 0 && proxiedImageMs > directImageMs + 1000) {
            sb.append("· 本次封面样本直连 $directImageMs ms、代理 $proxiedImageMs ms；代理明显更慢，但不能推断所有封面都如此。\n")
            tips.add("若首页海报慢，确认图片加载优先直连，并检查代理回退发生的频率。")
        }
        tips.add("此诊断未测试视频流和缓冲；播放卡顿需要另测播放地址、缓冲与实际带宽。")
        sb.append("\n")
        tips.forEachIndexed { i, t -> sb.append("${i + 1}. $t\n") }

        val text = sb.toString().trim()
        ui { addDetail("concl", text) }
        report.append("\n【结论】\n").append(text).append('\n')
    }

    /* ---------------------------------------------------------------- 工具 */

    private fun httpHead(base: String, timeoutMs: Int): Boolean {
        val conn = URL(base).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            runCatching { conn.inputStream?.close() }
            code in 200..599
        } catch (e: Exception) {
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 跑一段并计时，返回 (毫秒, 是否成功) —— 异常一律算失败，不让诊断自己崩掉 */
    private fun timeOf(block: () -> Boolean): Pair<Long, Boolean> {
        val t = System.nanoTime()
        val ok = try {
            block()
        } catch (e: Throwable) {
            false
        }
        return (System.nanoTime() - t) / 1_000_000L to ok
    }

    private fun isFakeIp(address: String): Boolean =
        address.startsWith("198.18.") || address.startsWith("198.19.")

    private fun grade(ms: Long, good: Long, bad: Long): Int = when {
        ms <= 0 -> GREY
        ms <= good -> GREEN
        ms <= bad -> AMBER
        else -> RED
    }

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrDefault(url.take(24))

    private fun nowText(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    private fun appendReport(key: String, value: String, detail: String) {
        synchronized(report) {
            report.append(key).append(": ").append(value)
            if (detail.isNotEmpty()) report.append(" (").append(detail).append(')')
            report.append('\n')
        }
    }

    private fun copyReport() {
        val text = synchronized(report) { report.toString() }
        if (text.isBlank()) {
            Toast.makeText(this, "还没开始诊断", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("网络诊断", text))
        Toast.makeText(this, "报告已复制，可以直接粘给别人看", Toast.LENGTH_SHORT).show()
    }

    /* ---------------------------------------------------------------- UI */

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun section(title: String) {
        ui {
            body.addView(TextView(this).apply {
                text = "▎$title"
                setTextColor(INK)
                setTextSize(15f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(2), dp(18), dp(2), dp(8))
            })
        }
        report.append('\n').append("== ").append(title).append(" ==\n")
    }

    /** 建行：只允许在主线程真正动视图。探测线程调用时经 runOnUiThread 派发（FIFO 保序）。 */
    private fun addRow(key: String, title: String) {
        ui {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(11), dp(16), dp(11))
                background = cardBg()
            }
            val name = TextView(this).apply {
                text = title
                setTextColor(INK)
                setTextSize(14.5f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val value = TextView(this).apply {
                text = "…"
                setTextColor(GREY)
                setTextSize(14f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.END
                maxLines = 1
            }
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f))
            row.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f))
            body.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) })
            valueViews[key] = value

            // 说明行：默认不占地方，有内容才显示
            val detail = TextView(this).apply {
                setTextColor(HINT)
                setTextSize(12f)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(16), dp(4), dp(16), dp(2))
                visibility = View.GONE
            }
            body.addView(detail)
            detailViews[key] = detail
        }
    }

    private fun setRow(key: String, text: String, color: Int) {
        ui {
            val v = valueViews[key] ?: return@ui
            v.text = text
            v.setTextColor(color)
        }
    }

    private fun addDetail(key: String, text: String) {
        ui {
            val d = detailViews[key] ?: run {
                // 没有对应行（比如全局提示），直接追加一段
                body.addView(TextView(this).apply {
                    setText(text)
                    setTextColor(HINT)
                    setTextSize(12f)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(dp(16), dp(4), dp(16), dp(4))
                })
                return@ui
            }
            d.text = if (d.visibility == View.VISIBLE && d.text.isNotEmpty()) {
                d.text.toString() + "\n" + text
            } else {
                text
            }
            d.visibility = View.VISIBLE
        }
    }

    private fun actionButton(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextSize(14f)
            setTypeface(null, if (primary) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else INK)
            setPadding(dp(20), dp(11), dp(20), dp(11))
            background = if (primary) {
                GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(ACCENT)
                }
            } else {
                cardBg()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f ->
                v.background = if (primary) {
                    GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (f) 0xFF0A544F.toInt() else ACCENT)
                        if (f) setStroke(dp(3), 0xFF0A544F.toInt())
                    }
                } else {
                    cardBg(strokeColor = if (f) ACCENT else BORDER, strokeW = if (f) 2 else 1)
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(10) }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        // 与 MainActivity 浅色卡片风格对齐
        val PAGE_BG = 0xFFF5F7F1.toInt()
        val INK = 0xFF173B35.toInt()
        val HINT = 0xFF617A70.toInt()
        val PANEL = 0xFFFFFFFF.toInt()
        val BORDER = 0xFFDFE8E2.toInt()
        val ACCENT = 0xFF0F766E.toInt()
        // 状态色：浅底上用深一档，保证可读
        val GREEN = 0xFF15803D.toInt()
        val AMBER = 0xFFB45309.toInt()
        val RED = 0xFFDC2626.toInt()
        val GREY = 0xFF7C9188.toInt()
    }

    /** 回主页：回到主界面并落到首页（MainActivity singleTask 会收到 onNewIntent） */
    private fun goHome() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("nav_home", true),
        )
        finish()
    }
}
