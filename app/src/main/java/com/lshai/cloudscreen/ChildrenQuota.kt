package com.lshai.cloudscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
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
    fun parent(activity: Activity, after: () -> Unit) {
        init(activity)
        val random = java.util.Random()
        val a = random.nextInt(8) + 2; val b = random.nextInt(8) + 2
        val digits = arrayOf("零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖")
        val answer = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "输入计算结果" }
        val verify = AlertDialog.Builder(activity).setTitle("家长验证：${digits[a]} × ${digits[b]} = ？")
            .setView(answer).setPositiveButton("验证", null).setNegativeButton("取消", null).create()
        verify.setOnShowListener {
            verify.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (answer.text.toString().toIntOrNull() != a * b) { answer.error = "答案不正确，请重试"; return@setOnClickListener }
                verify.dismiss()
                AlertDialog.Builder(activity).setTitle("儿童每日时长")
                    .setItems(arrayOf("设置每日额度（当前 ${dailyMinutes(activity)} 分钟）", "今天临时增加时间")) { _, which ->
                        val field = EditText(activity).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER
                            hint = if (which == 0) "每日分钟数（1—240）" else "今天增加分钟数（1—240）"
                        }
                        val edit = AlertDialog.Builder(activity).setTitle(if (which == 0) "每日额度" else "今天临时加时")
                            .setView(field).setMessage("每日额度次日恢复；临时加时仅当天有效。")
                            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
                        edit.setOnShowListener { edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val minutes = field.text.toString().toIntOrNull()
                            if (minutes == null || minutes !in 1..240) { field.error = "请输入 1—240 的分钟数"; return@setOnClickListener }
                            settle()
                            store.edit().putInt(if (which == 0) "limit" else "bonus", if (which == 0) minutes else store.getInt("bonus", 0) + minutes).apply()
                            edit.dismiss(); after()
                        } }
                        edit.show()
                    }.setNegativeButton("关闭", null).show()
            }
        }
        verify.show()
    }
    fun locked(activity: Activity, onClose: (() -> Unit)? = null, after: () -> Unit) {
        AlertDialog.Builder(activity).setTitle("今天的儿童时间已用完")
            .setMessage("每日额度 ${dailyMinutes(activity)} 分钟，明天自动恢复。家长可通过计算题临时加时。")
            .setPositiveButton("家长加时") { _, _ -> parent(activity, after) }
            .setNegativeButton(if (onClose == null) "关闭" else "返回", { _, _ -> onClose?.invoke() }).show()
    }
}
