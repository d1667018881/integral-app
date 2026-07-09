package com.integral.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * 前台服务 - 支持多个账号并发提交积分
 * 每个账号一个独立协程任务(Job)与独立运行状态(TaskState)，
 * 各自持有日志与进度；统一一条前台通知。
 */
class IntegralService : Service() {

    companion object {
        const val CHANNEL_ID = "integral_service_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val FOREGROUND_SERVICE_TYPE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        // 当前选中的账号（供通知展示其进度）
        var selectedAccountId: String? = null

        // 状态变化回调（由 MainActivity 设置，用于刷新选中账号的界面）
        var onStateChanged: ((accountId: String) -> Unit)? = null

        // 对外暴露各账号运行状态（MainActivity 读取选中账号渲染）
        val tasks = ConcurrentHashMap<String, TaskState>()

        // 供 MainActivity 引用
        var serviceInstance: IntegralService? = null
    }

    /** 单个账号的运行状态 */
    data class TaskState(
        var isRunning: Boolean = false,
        var statusText: String = "⏸ 待命中",
        var currentScore: Int = 0,
        var remainingSeconds: Int = 0,
        var currentAttempt: Int = 0,
        var maxAttempts: Int = 0,
        var logContent: String = ""
    )

    private val MAX_LOG_LINES = 300
    private val WAKE_LOCK_DURATION_MS = 24 * 60 * 60 * 1000L

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val jobs = ConcurrentHashMap<String, Job>()

    private lateinit var configManager: ConfigManager
    private lateinit var networkManager: NetworkManager
    private lateinit var accountManager: AccountManager

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        configManager = ConfigManager(this)
        networkManager = NetworkManager()
        accountManager = AccountManager(this)
        Companion.serviceInstance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> ensureForeground()
            ACTION_STOP -> stopAll()
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        foregroundStarted = true
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
    }

    /** 启动（或重启）某个账号的任务 */
    fun startAccount(account: Account) {
        // 若已在运行，先取消旧任务（不在此删除 tasks/jobs，避免误删新任务）
        stopAccount(account.id)
        ensureForeground()

        val state = TaskState(isRunning = true, statusText = "🔄 运行中", logContent = "")
        tasks[account.id] = state

        val job = serviceScope.launch {
            try {
                runAccountLoop(account, state)
            } catch (e: CancellationException) {
                // 正常取消，忽略
            } finally {
                state.isRunning = false
                // 仅当本协程仍是该账号的当前任务时才清理，避免重启时误删新任务
                val selfJob = coroutineContext[Job]
                if (jobs[account.id] == selfJob) {
                    tasks.remove(account.id)
                    jobs.remove(account.id)
                }
                onStateChanged?.invoke(account.id)
                updateNotification()
                // 没有任何账号运行时，停止服务释放资源
                if (tasks.isEmpty()) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        jobs[account.id] = job
        updateNotification()
    }

    /** 停止某个账号的任务 */
    fun stopAccount(accountId: String) {
        jobs[accountId]?.cancel()
    }

    /** 停止全部账号并结束服务 */
    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        tasks.clear()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runAccountLoop(account: Account, state: TaskState) {
        val config = configManager
        val net = networkManager
        val am = accountManager

        val submitUrl = config.getSubmitUrl()
        val queryUrl = config.getQueryUrl()
        val maxAttempts = config.getMaxAttempts()
        val delayMin = config.getDelayMin()
        val delayMax = config.getDelayMax()
        val integralType = config.getIntegralType()
        val loginId = account.loginId

        fun log(msg: String) {
            val cur = state.logContent
            val newText = if (cur.isEmpty() || cur == "等待执行...") msg else "$cur\n$msg"
            val lines = newText.split("\n")
            val limited = if (lines.size > MAX_LOG_LINES) lines.takeLast(MAX_LOG_LINES) else lines
            state.logContent = limited.joinToString("\n")
            onStateChanged?.invoke(account.id)
        }

        fun status(msg: String) {
            state.statusText = msg
            onStateChanged?.invoke(account.id)
        }

        log("工号：$loginId")
        log("正在查询当前积分...")

        var currentScore = try {
            net.queryIntegral(loginId, queryUrl)
        } catch (e: Exception) {
            log("❌ 初始查询失败：${e.message}")
            status("❌ 查询失败")
            return
        }
        log("✅ 当前积分：$currentScore")

        // 计算有效目标：增加积分模式 = 当前分 + 增量；达标模式 = 输入绝对值
        val targetScore = if (account.mode == "increment") {
            val t = currentScore + account.target
            log("🎯 增加积分模式：目标 = 当前 $currentScore + 增加 ${account.target} = $t")
            t
        } else {
            log("目标积分：${account.target}")
            account.target
        }

        if (currentScore >= targetScore) {
            log("✅ 已达标！当前 $currentScore >= 目标 $targetScore")
            status("✅ 已达标 $currentScore")
            return
        }

        log("开始循环提交（间隔 ${delayMin}-${delayMax} 秒，最多 $maxAttempts 次）")
        var attempt = 1

        while (coroutineContext.isActive && currentScore < targetScore && attempt <= maxAttempts) {
            val delaySeconds = (delayMin..delayMax).random()

            state.currentAttempt = attempt
            state.maxAttempts = maxAttempts
            state.currentScore = currentScore
            log("─── 第 $attempt 次（剩余 ${maxAttempts - attempt} 次）───")
            log("⏳ 等待 $delaySeconds 秒...")
            status("⏳ 第 $attempt 次 等待 ${delaySeconds}s ｜ 当前积分 $currentScore")

            var waited = 0
            while (coroutineContext.isActive && waited < delaySeconds) {
                delay(1000)
                waited++
                val remaining = delaySeconds - waited
                state.remainingSeconds = remaining
                status("⏳ 第 $attempt 次 等待 ${remaining}s ｜ 当前积分 $currentScore")
            }
            if (!coroutineContext.isActive) break

            state.remainingSeconds = 0
            status("🔄 第 $attempt 次 执行中 ｜ 当前积分 $currentScore")

            try {
                if (am.needDayReset(account.id)) {
                    log("📅 日期变更，重置资源ID...")
                    am.initResourceId(account.id)
                }
                var resourceId = am.getResourceId(account.id)
                if (resourceId == -1) {
                    am.initResourceId(account.id)
                    resourceId = am.getResourceId(account.id)
                }

                val newResourceId = net.submitIntegral(loginId, integralType, submitUrl, resourceId)
                val newScore = net.queryIntegral(loginId, queryUrl)

                am.saveResourceId(account.id, newResourceId)
                am.saveLastDate(account.id, ConfigManager.DATE_FORMAT.format(Date()))

                if (newScore <= currentScore) {
                    currentScore = newScore
                    state.currentScore = currentScore
                    status("⚠ 积分未增长 ｜ 当前积分 $currentScore")
                    log("⚠ 积分未增长 ($newScore)，可能已达上限")
                    attempt++
                    continue
                }

                currentScore = newScore
                state.currentScore = currentScore
                log("📈 当前积分：$currentScore")
                status("📈 当前积分：$currentScore")

                if (currentScore >= targetScore) {
                    log("\n🎉 达标！当前 $currentScore >= 目标 $targetScore")
                    status("🎉 已达标 $currentScore")
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("❌ 执行失败：${e.message}")
                attempt++
                continue
            }
            attempt++
        }

        when {
            !coroutineContext.isActive -> log("⛔ 已停止")
            attempt > maxAttempts -> log("\n⚠ 已达到最大次数 $maxAttempts，未达标（当前：$currentScore / 目标：$targetScore）")
        }
        status("⏸ 已停止")
    }

    // ---------------- 通知 ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "积分助手服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "积分自动提交后台服务"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val runningCount = tasks.values.count { it.isRunning }
        val selected = selectedAccountId?.let { tasks[it] }
        val content = if (runningCount == 0) {
            "积分助手待命"
        } else if (selected != null && selected.isRunning) {
            "运行中 · $runningCount 个账号 ｜ ${selected.statusText}"
        } else {
            "运行中 · $runningCount 个账号"
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("积分助手")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ---------------- 唤醒锁 ----------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "IntegralAssistant::WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_DURATION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
        Companion.serviceInstance = null
    }
}
