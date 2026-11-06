package com.lshai.cloudscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Main-thread owner of one shared browsing/playback allowance. */
internal object ChildrenQuota {
    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var last = 0L
    private var day = ""
    private var used = 0L
    private var browsing = false
    private var player: ExoPlayer? = null
    private var childPlayback = false
    private var wasActive = false
    private var warnedDay = ""
    private val catalogKeys by lazy { ChildrenCatalog.preloadOrder().map(::normalizeSeries).toSet() }
    private val store get() = app.getSharedPreferences("children_daily_quota", Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private val tick = object : Runnable {
        override fun run() {
            settle(); enforce()
            if (active() && remaining() in 1..60_000L && warnedDay != day) {
                warnedDay = day
                android.widget.Toast.makeText(app, "今天的儿童时间还剩 1 分钟", android.widget.Toast.LENGTH_LONG).show()
            }
            handler.postDelayed(this, 1000)
        }
    }
    fun init(context: Context) {
        if (started) return
        app = context.applicationContext
        day = store.getString("day", "").orEmpty()
        used = store.getLong("used", 0L)
        last = SystemClock.elapsedRealtime()
        started = true
        settle()
        handler.post(tick)
    }
    private fun active() = browsing || (childPlayback && player?.isPlaying == true)
    private fun settle() {
        if (!started) return
        val now = SystemClock.elapsedRealtime()
        val currentDay = today()
        if (day != currentDay) {
            val calendar = java.util.Calendar.getInstance()
            val midnight = (calendar.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            used = quotaUsage(false, used, now - last, wasActive, calendar.timeInMillis - midnight.timeInMillis)
            day = currentDay
            store.edit().putString("day", day).putLong("used", used).putInt("bonus", 0).apply()
        } else if (wasActive && now > last) {
            used = quotaUsage(true, used, now - last, true, 0)
            store.edit().putLong("used", used).apply()
        }
        last = now
        wasActive = active()
    }
    fun browse(value: Boolean) { settle(); browsing = value; wasActive = active() }
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { settle(); enforce() }
    }
    fun attach(context: Context, target: ExoPlayer, child: Boolean) {
        init(context); settle()
        player?.removeListener(listener)
        player = target; childPlayback = child
        target.addListener(listener)
        wasActive = active(); enforce()
    }
    fun detach(target: ExoPlayer?) {
        if (player !== target) return
        settle(); player?.removeListener(listener); player = null; childPlayback = false; wasActive = active()
    }
    private fun enforce() {
        val target = player
        if (childPlayback && remaining() <= 0 && target?.isPlaying == true) {
            target.pause()
            if (BackgroundPlayback.player === target) BackgroundPlayback.persist(app)
        }
    }
    fun dailyMinutes(context: Context): Int { init(context); return store.getInt("limit", 15) }
    fun remaining(): Long {
        settle()
        return ((store.getInt("limit", 15) + store.getInt("bonus", 0)) * 60_000L - used).coerceAtLeast(0L)
    }
    fun remaining(context: Context): Long { init(context); return remaining() }
    fun label(context: Context): String {
        val left = remaining(context)
        return if (left <= 0) "今日已用完" else "今日剩余 ${(left + 59_999) / 60_000} 分钟"
    }
    fun isChild(context: Context, title: String): Boolean {
        init(context)
        val key = normalizeSeries(title.substringBefore(" · 第"))
        return key.isNotEmpty() && (key in catalogKeys || key in store.getStringSet("titles", emptySet()).orEmpty())
    }
    fun mark(context: Context, title: String) {
        init(context)
        val key = normalizeSeries(title.substringBefore(" · 第"))
        if (key.isEmpty()) return
        val titles = store.getStringSet("titles", emptySet()).orEmpty().toMutableSet()
        if (titles.add(key)) store.edit().putStringSet("titles", titles).apply()
    }
    private fun panelTitle(activity: Activity, title: String): android.view.View {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        return android.widget.LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
            addView(ChildrenArtworkView(activity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFD9F1E7.toInt()); cornerRadius = dp(12).toFloat()
                }
                clipToOutline = true
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.LinearLayout.LayoutParams(dp(72), dp(58)))
            addView(android.widget.TextView(activity).apply {
                text = title; textSize = 20f; setTextColor(0xFF173B35.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(12), 0, 0, 0)
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
    private fun stylePanel(activity: Activity, dialog: AlertDialog) {
        val density = activity.resources.displayMetrics.density
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFDF6.toInt()); cornerRadius = 22 * density
            setStroke((density * 1).toInt().coerceAtLeast(1), 0xFFDFE8E2.toInt())
        })
        val available = activity.resources.displayMetrics.widthPixels
        dialog.window?.setLayout(minOf((480 * density).toInt(), (available * .94f).toInt()), -2)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE).forEach { which ->
            dialog.getButton(which)?.setTextColor(0xFF0F766E.toInt())
        }
    }

    private fun numberPanel(
        activity: Activity, title: String, explanation: String, maxDigits: Int,
        preview: (String) -> String = { "" },
        validate: (Int?) -> String?, accepted: (Int) -> Unit,
    ) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(8))
        }
        content.addView(android.widget.TextView(activity).apply { text = explanation; textSize = 15f; setTextColor(0xFF40645A.toInt()) })
        val display = android.widget.TextView(activity).apply {
            textSize = 28f; gravity = android.view.Gravity.CENTER
            setTextColor(0xFF173B35.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFE5F3ED.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFFBDDCCD.toInt())
            }
            setPadding(0, dp(8), 0, dp(8)); contentDescription = "尚未输入数字"
        }
        content.addView(display)
        val detail = android.widget.TextView(activity).apply { textSize = 14f; minLines = 2; setTextColor(0xFF40645A.toInt()); setPadding(0, dp(6), 0, 0) }
        content.addView(detail)
        val error = android.widget.TextView(activity).apply { setTextColor(0xFFAE3B32.toInt()); minLines = 1 }
        content.addView(error)
        val dialog = AlertDialog.Builder(android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material_Light_Dialog_Alert)).setCustomTitle(panelTitle(activity, title))
            .setView(android.widget.ScrollView(activity).apply { addView(content) })
            .setNegativeButton("取消", null).create()
        var input = ""
        fun refresh() {
            display.text = input.ifEmpty { "—" }
            display.contentDescription = "已输入：${input.ifEmpty { "空" }}"
            detail.text = preview(input)
            error.text = ""
        }
        fun append(digit: String) {
            if (input.length >= maxDigits) return
            input = if (input == "0") digit else input + digit
            refresh()
        }
        fun submit() {
            val number = input.toIntOrNull()
            val message = validate(number)
            if (message != null) { error.text = message; return }
            dialog.dismiss()
            accepted(number!!)
        }
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "确认")
        val buttons = mutableListOf<android.widget.Button>()
        labels.chunked(3).forEach { keys ->
            val row = android.widget.LinearLayout(activity)
            keys.forEach { key ->
                val button = android.widget.Button(activity).apply {
                    text = key; textSize = 18f
                    isAllCaps = false
                    background = android.graphics.drawable.StateListDrawable().apply {
                        fun shape(color: Int, focused: Boolean = false) = android.graphics.drawable.GradientDrawable().apply {
                            setColor(color); cornerRadius = dp(12).toFloat()
                            setStroke(dp(if (focused) 3 else 1), if (focused) 0xFF0F766E.toInt() else 0xFFDFE8E2.toInt())
                        }
                        addState(intArrayOf(android.R.attr.state_focused), shape(if (key == "确认") 0xFF155C53.toInt() else 0xFFC5E9D7.toInt(), true))
                        addState(intArrayOf(android.R.attr.state_pressed), shape(if (key == "确认") 0xFF155C53.toInt() else 0xFFC5E9D7.toInt(), true))
                        addState(intArrayOf(), shape(when (key) { "确认" -> 0xFF0F766E.toInt(); "删除" -> 0xFFFFE8CE.toInt(); else -> 0xFFFFFFFF.toInt() }))
                    }
                    setTextColor(if (key == "确认") android.graphics.Color.WHITE else 0xFF173B35.toInt())
                    stateListAnimator = null
                    backgroundTintList = null
                    setOnClickListener {
                        when (key) {
                            "删除" -> { input = input.dropLast(1); refresh() }
                            "确认" -> submit()
                            else -> append(key)
                        }
                    }
                }
                buttons.add(button)
                row.addView(button, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            content.addView(row)
        }
        // Explicit neighbours keep D-pad movement inside the number pad predictable.
        buttons.forEach { button ->
            button.id = android.view.View.generateViewId()
        }
        buttons.forEachIndexed { index, button ->
            if (index % 3 > 0) button.nextFocusLeftId = buttons[index - 1].id
            if (index % 3 < 2) button.nextFocusRightId = buttons[index + 1].id
            if (index >= 3) button.nextFocusUpId = buttons[index - 3].id
            if (index + 3 < buttons.size) button.nextFocusDownId = buttons[index + 3].id
        }
        dialog.setOnKeyListener { _, code, event ->
            val digit = when (code) {
                in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> code - android.view.KeyEvent.KEYCODE_0
                in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> code - android.view.KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            if (digit >= 0 || code == android.view.KeyEvent.KEYCODE_DEL) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (digit >= 0) append(digit.toString()) else { input = input.dropLast(1); refresh() }
                }
                true
            } else false
        }
        refresh()
        dialog.setOnShowListener {
            stylePanel(activity, dialog)
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            buttons.first().requestFocus()
        }
        dialog.show()
    }

    fun parent(activity: Activity, after: () -> Unit) {
        init(activity)
        val random = java.util.Random()
        val a = random.nextInt(8) + 2; val b = random.nextInt(8) + 2
        val digits = arrayOf("零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖")
        numberPanel(activity, "家长验证：${digits[a]} × ${digits[b]} = ？", "点击数字输入答案，输入 10 请依次按 1、0。", 2,
            validate = { if (it == a * b) null else "答案不正确，请删除后重新输入" }, accepted = {
                val daily = dailyMinutes(activity)
                AlertDialog.Builder(android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material_Light_Dialog_Alert))
                    .setCustomTitle(panelTitle(activity, "每日固定 $daily 分钟"))
                    .setItems(arrayOf("修改每日固定额度（以后每天生效）", "仅今天加时（明天仍为 $daily 分钟）")) { _, which ->
                        val fixed = which == 0
                        numberPanel(activity, if (fixed) "修改每日固定额度" else "仅今天加时",
                            if (fixed) "当前每天 $daily 分钟。修改后今天立即生效，以后每天沿用新额度；已用时间不清零。"
                            else "每日固定 $daily 分钟。这里只增加今天的时间，不改变每日固定额度。", 3,
                            preview = { input ->
                                val minutes = input.toIntOrNull()
                                if (minutes == null || minutes !in 1..240) "请输入 1—240 分钟"
                                else if (fixed) "确认后：每天 $minutes 分钟，明天也是 $minutes 分钟。"
                                else "今天额外增加 $minutes 分钟；明天恢复为 $daily 分钟。"
                            },
                            validate = { if (it != null && it in 1..240) null else "请输入 1—240 的分钟数" },
                            accepted = { minutes ->
                                settle()
                                store.edit().putInt(if (fixed) "limit" else "bonus",
                                    if (fixed) minutes else store.getInt("bonus", 0) + minutes).apply()
                                android.widget.Toast.makeText(activity,
                                    if (fixed) "每日固定额度已改为 $minutes 分钟，明天同样生效"
                                    else "今天已加 $minutes 分钟，明天仍为 $daily 分钟",
                                    android.widget.Toast.LENGTH_LONG).show()
                                after()
                            })
                    }.setNegativeButton("关闭", null).create().also { menu -> menu.setOnShowListener { stylePanel(activity, menu) }; menu.show() }
            })
    }
    fun locked(activity: Activity, onClose: (() -> Unit)? = null, after: () -> Unit) {
        AlertDialog.Builder(android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material_Light_Dialog_Alert))
            .setCustomTitle(panelTitle(activity, "今天的儿童时间已用完"))
            .setMessage("每日固定 ${dailyMinutes(activity)} 分钟，明天恢复为 ${dailyMinutes(activity)} 分钟。家长可通过计算题仅给今天加时。")
            .setPositiveButton("家长加时") { _, _ -> parent(activity, after) }
            .setNegativeButton(if (onClose == null) "关闭" else "返回", { _, _ -> onClose?.invoke() }).create().also { dialog ->
                dialog.setOnShowListener { stylePanel(activity, dialog) }; dialog.show()
            }
    }
}
