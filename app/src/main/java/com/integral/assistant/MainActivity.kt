package com.integral.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.integral.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager
    private lateinit var networkManager: NetworkManager

    @Volatile
    private var isRunning = false
    @Volatile
    private var stopRequested = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SERVICE_START_DELAY_MS = 500L
        private const val MAX_LOG_LINES = 300

        // 指向当前存活（可见）的 Activity 实例。
        // 后台任务的实时更新只打到这个实例，避免打到已销毁的旧实例
        private var currentInstance: MainActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager(this)
        networkManager = NetworkManager()

        setupUI()
        loadSavedData()
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 标记自己为当前存活实例，后续实时更新都打到本实例
        currentInstance = this
        // 同步服务状态
        isRunning = IntegralService.isServiceRunning
        updateButtonState()
        // 恢复服务中的实时状态（倒计时、进度等）
        if (isRunning) {
            restoreServiceState()
            restoreLog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 仅当自己仍是当前实例时才清空，避免误清掉新实例
        if (currentInstance == this) currentInstance = null
    }

    private fun restoreServiceState() {
        val remaining = IntegralService.remainingSeconds
        val attempt = IntegralService.currentAttempt
        val score = IntegralService.currentScore
        if (remaining > 0) {
            updateStatus("⏳ 第 $attempt 次 等待 ${remaining}s ｜ 当前积分 $score")
        } else {
            updateStatus(IntegralService.statusText)
        }
    }

    // 从静态缓存恢复运行日志（Activity 被销毁重建时也能还原）
    private fun restoreLog() {
        if (IntegralService.logContent.isNotEmpty()) {
            binding.tvLog.text = IntegralService.logContent
        }
    }

    private fun setupUI() {
        // 设置按钮 → 跳转到设置页面
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // 开始按钮
        binding.btnStart.setOnClickListener {
            startExecution()
        }

        // 停止按钮
        binding.btnStop.setOnClickListener {
            stopExecution()
        }

        // 工号输入框 - 自动保存
        binding.inputLoginId.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    configManager.saveLoginId(text)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 目标积分输入框 - 自动保存（与应用同生命周期，重建后还原）
        binding.inputTargetScore.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                text.toIntOrNull()?.let { configManager.saveTargetScore(it) }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 模式切换：达标模式 / 增加积分模式
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.modeIncrement) "increment" else "reach"
            configManager.saveMode(mode)
            updateTargetLabel(mode)
        }
    }

    // 根据模式更新输入框标签与提示
    private fun updateTargetLabel(mode: String) {
        if (mode == "increment") {
            binding.tvTargetLabel.text = "增加积分数"
            binding.inputTargetScore.hint = "请输入要增加的分数"
        } else {
            binding.tvTargetLabel.text = "目标积分"
            binding.inputTargetScore.hint = "请输入目标积分"
        }
    }

    private fun loadSavedData() {
        val savedLoginId = configManager.getLoginId()
        if (savedLoginId.isNotEmpty()) {
            binding.inputLoginId.setText(savedLoginId)
            binding.inputLoginId.setSelection(savedLoginId.length)
        }
        val savedTargetScore = configManager.getTargetScore()
        if (savedTargetScore > 0) {
            binding.inputTargetScore.setText(savedTargetScore.toString())
            binding.inputTargetScore.setSelection(savedTargetScore.toString().length)
        }
        // 恢复模式选择
        val savedMode = configManager.getMode()
        if (savedMode == "increment") {
            binding.modeIncrement.isChecked = true
        } else {
            binding.modeReach.isChecked = true
        }
        updateTargetLabel(savedMode)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        // Android 13+ 需要通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun startExecution() {
        val loginId = binding.inputLoginId.text.toString().trim()
        val inputText = binding.inputTargetScore.text.toString().trim()
        val mode = configManager.getMode()
        val incrementMode = mode == "increment"

        // 验证工号
        if (loginId.isEmpty()) {
            appendLog("❌ 请先输入工号！")
            Toast.makeText(this, "请先输入工号", Toast.LENGTH_SHORT).show()
            return
        }

        // 验证目标值（达标模式=目标积分，增加积分模式=要增加的分数）
        val value = inputText.toIntOrNull()
        if (value == null || value <= 0) {
            val msg = if (incrementMode) "请输入有效的增加分数！" else "请输入有效的目标积分！"
            appendLog("❌ $msg")
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        // 保存工号与目标值（与应用同生命周期，Activity 重建后可还原）
        configManager.saveLoginId(loginId)
        configManager.saveTargetScore(value)

        // 启动前台服务
        val serviceIntent = Intent(this, IntegralService::class.java).apply {
            action = IntegralService.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 等待服务启动
        android.os.Handler(mainLooper).postDelayed({
            // 开始执行任务
            isRunning = true
            stopRequested = false
            updateButtonState()
            clearLog()
            updateStatus("🔄 运行中")

            // 通过服务启动任务
            // 达标模式：以输入值作为绝对目标
            // 增加积分模式：输入值作为增量，运行时以「当前分+增量」为目标
            val reachTarget = if (incrementMode) 0 else value
            val increment = if (incrementMode) value else 0
            IntegralService.serviceInstance?.startTask {
                runLoop(loginId, reachTarget, incrementMode, increment)
            }
        }, SERVICE_START_DELAY_MS)
    }

    private fun stopExecution() {
        stopRequested = true
        isRunning = false
        // 立即把服务运行状态置为 false，让按钮/输入框马上恢复可操作
        // （服务真正停止是异步的，不提前置位会导致 UI 仍停留在运行中状态）
        IntegralService.isServiceRunning = false

        // 取消服务中的任务
        IntegralService.serviceInstance?.cancelTask()
        
        // 停止前台服务
        val serviceIntent = Intent(this, IntegralService::class.java).apply {
            action = IntegralService.ACTION_STOP
        }
        startService(serviceIntent)

        updateStatus("⛔ 已手动停止")
        appendLog("⛔ 已手动停止")
        updateButtonState()
    }

    private suspend fun runLoop(loginId: String, reachTarget: Int, incrementMode: Boolean, increment: Int) {
        val config = ConfigManager(this)
        val submitUrl = config.getSubmitUrl()
        val queryUrl = config.getQueryUrl()
        val maxAttempts = config.getMaxAttempts()
        val delayMin = config.getDelayMin()
        val delayMax = config.getDelayMax()
        val integralType = config.getIntegralType()

        appendLog("工号：$loginId")
        appendLog("正在查询当前积分...")

        // 查询当前积分
        var currentScore = try {
            networkManager.queryIntegral(loginId, queryUrl)
        } catch (e: Exception) {
            appendLog("❌ 初始查询失败：${e.message}")
            updateStatus("❌ 查询失败")
            stopService()
            return
        }

        appendLog("✅ 当前积分：$currentScore")

        // 计算有效目标：增加积分模式 = 当前分 + 增量；达标模式 = 输入的绝对值
        val targetScore = if (incrementMode) {
            val t = currentScore + increment
            appendLog("🎯 增加积分模式：目标 = 当前 $currentScore + 增加 $increment = $t")
            t
        } else {
            appendLog("目标积分：$reachTarget")
            reachTarget
        }

        // 检查是否已达标
        if (currentScore >= targetScore) {
            appendLog("✅ 已达标！当前 $currentScore >= 目标 $targetScore")
            updateStatus("✅ 已达标 $currentScore")
            stopService()
            return
        }

        appendLog("开始循环提交（间隔 ${delayMin}-${delayMax} 秒，最多 $maxAttempts 次）\n")

        var attempt = 1

        while (coroutineContext.isActive && currentScore < targetScore && attempt <= maxAttempts && !stopRequested) {
            val delaySeconds = (delayMin..delayMax).random()

            // 更新服务状态（供 Activity 恢复时读取）
            IntegralService.currentAttempt = attempt
            IntegralService.maxAttempts = maxAttempts
            IntegralService.currentScore = currentScore

            appendLog("─── 第 $attempt 次（剩余 ${maxAttempts - attempt} 次）───")
            appendLog("⏳ 等待 $delaySeconds 秒...")
            updateStatus("⏳ 第 $attempt 次 等待 ${delaySeconds}s ｜ 当前积分 $currentScore")

            // 倒计时等待（每秒检查一次停止请求，动态显示剩余秒数）
            var waited = 0
            while (coroutineContext.isActive && waited < delaySeconds && !stopRequested) {
                delay(1000)
                waited++
                val remaining = delaySeconds - waited
                IntegralService.remainingSeconds = remaining
                updateStatus("⏳ 第 $attempt 次 等待 ${remaining}s ｜ 当前积分 $currentScore")
            }

            if (!coroutineContext.isActive || stopRequested) break

            IntegralService.remainingSeconds = 0
            updateStatus("🔄 第 $attempt 次 执行中 ｜ 当前积分 $currentScore")

            try {
                // 检查是否需要跨日重置
                if (config.needDayReset()) {
                    appendLog("📅 日期变更，重置资源ID...")
                    config.initResourceId()
                }

                // 获取当前资源ID
                var resourceId = config.getResourceId()
                if (resourceId == -1) {
                    config.initResourceId()
                    resourceId = config.getResourceId()
                }

                // 提交积分
                val newResourceId = networkManager.submitIntegral(
                    loginId = loginId,
                    integralType = integralType,
                    submitUrl = submitUrl,
                    resourceId = resourceId
                )

                // 查询新积分
                val newScore = networkManager.queryIntegral(loginId, queryUrl)

                // 保存配置
                config.saveResourceId(newResourceId)
                config.saveLastDate(ConfigManager.DATE_FORMAT.format(Date()))

                // 检查积分是否增长
                if (newScore <= currentScore) {
                    appendLog("⚠ 积分未增长 ($newScore)，可能已达上限")
                    currentScore = newScore
                    IntegralService.currentScore = currentScore
                    updateStatus("⚠ 积分未增长 ｜ 当前积分 $currentScore")
                    attempt++
                    continue
                }

                currentScore = newScore
                IntegralService.currentScore = currentScore
                appendLog("📈 当前积分：$currentScore")
                updateStatus("📈 当前积分：$currentScore")

                // 检查是否达标
                if (currentScore >= targetScore) {
                    appendLog("\n🎉 达标！当前 $currentScore >= 目标 $targetScore")
                    updateStatus("🎉 已达标 $currentScore")
                    stopService()
                    return
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("❌ 执行失败：${e.message}")
                attempt++
                continue
            }

            attempt++
        }

        // 循环结束
        when {
            stopRequested -> {
                appendLog("⛔ 已停止")
            }
            attempt > maxAttempts -> {
                appendLog("\n⚠ 已达到最大次数 $maxAttempts，未达标（当前：$currentScore / 目标：$targetScore）")
            }
        }
        updateStatus("⏸ 已停止")
        stopService()
    }

    private fun stopService() {
        isRunning = false
        // 立即把服务运行状态置为 false，让当前可见实例的按钮马上恢复
        IntegralService.isServiceRunning = false
        currentInstance?.updateButtonStateSafe(false)
        updateButtonState()
        
        val serviceIntent = Intent(this, IntegralService::class.java).apply {
            action = IntegralService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun updateButtonState() {
        val running = IntegralService.isServiceRunning
        currentInstance?.updateButtonStateSafe(running)
    }

    private fun updateButtonStateSafe(running: Boolean) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            binding.btnStart.isEnabled = !running
            binding.btnStart.alpha = if (running) 0.4f else 1.0f
            binding.btnStop.isEnabled = running
            binding.btnStop.alpha = if (running) 1.0f else 0.4f
            binding.inputLoginId.isEnabled = !running
            binding.inputTargetScore.isEnabled = !running
        }
    }

    private fun updateStatus(status: String) {
        // 同步更新服务状态（即使界面销毁，也可以保留状态）
        IntegralService.statusText = status
        // 实时刷新只打到当前存活的 Activity 实例，避免打到已销毁的旧实例
        currentInstance?.setStatusTextSafe(status)
    }

    private fun setStatusTextSafe(status: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            binding.tvStatus.text = status
        }
    }

    private fun appendLog(message: String) {
        currentInstance?.appendLogSafe(message)
    }

    private fun appendLogSafe(message: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            val currentText = binding.tvLog.text.toString()
            val newText = if (currentText.isEmpty() || currentText == "等待执行...") {
                message
            } else {
                "$currentText\n$message"
            }
            // 限制日志行数
            val lines = newText.split("\n")
            val limitedLines = if (lines.size > MAX_LOG_LINES) lines.takeLast(MAX_LOG_LINES) else lines
            val limitedText = limitedLines.joinToString("\n")
            // 同步到静态缓存，供 Activity 重建后恢复
            IntegralService.logContent = limitedText
            binding.tvLog.text = limitedText
        }
    }

    private fun clearLog() {
        IntegralService.logContent = ""
        currentInstance?.clearLogSafe()
    }

    private fun clearLogSafe() {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            binding.tvLog.text = ""
        }
    }
}
