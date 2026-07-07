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

/**
 * 前台服务 - 保持应用在后台运行
 * 解决 Android 10+ 后台执行限制
 */
class IntegralService : Service() {

    companion object {
        const val CHANNEL_ID = "integral_service_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        
        // 静态变量，用于服务与Activity通信
        var isServiceRunning = false
        var serviceInstance: IntegralService? = null
        private const val WAKE_LOCK_DURATION_MS = 24 * 60 * 60 * 1000L

        // 任务状态（Activity 可安全读取）
        @Volatile var statusText: String = "⏸ 待命中"
        @Volatile var remainingSeconds: Int = 0
        @Volatile var currentAttempt: Int = 0
        @Volatile var maxAttempts: Int = 0
        @Volatile var currentScore: Int = 0

        // 运行日志缓存（即使 Activity 被销毁重建也能恢复）
        @Volatile var logContent: String = ""
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopForegroundService()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        // 创建通知
        val notification = createNotification()
        
        // 启动前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 获取唤醒锁，防止CPU休眠
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "IntegralAssistant::WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_DURATION_MS)
        }

        isServiceRunning = true
    }

    private fun stopForegroundService() {
        // 释放唤醒锁
        releaseWakeLock()

        // 取消任务
        currentJob?.cancel()
        currentJob = null

        // 停止服务
        isServiceRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放资源
        releaseWakeLock()
        serviceScope.cancel()
        isServiceRunning = false
        serviceInstance = null
    }

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

    private fun createNotification(): Notification {
        // 复用已存在的 MainActivity 实例，而不是新建一个
        // SINGLE_TOP + CLEAR_TOP：若 Activity 已在栈顶则复用（触发 onNewIntent），
        // 否则将其提到前台并清理其上方的其他页面（如设置页）
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("积分助手运行中")
            .setContentText("正在自动提交积分...")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // 供 Activity 调用的方法
    fun startTask(task: suspend () -> Unit) {
        currentJob = serviceScope.launch {
            try {
                task()
            } finally {
                // 任务结束（成功/失败/取消）时立即释放唤醒锁
                releaseWakeLock()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    fun cancelTask() {
        currentJob?.cancel()
        currentJob = null
    }
}
