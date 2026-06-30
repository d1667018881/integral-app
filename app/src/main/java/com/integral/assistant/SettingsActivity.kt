package com.integral.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.integral.assistant.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var configManager: ConfigManager
    private lateinit var networkManager: NetworkManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager(this)
        networkManager = NetworkManager()

        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 保存按钮
        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        // 重置按钮
        binding.btnReset.setOnClickListener {
            resetToDefault()
        }

        // 从网络导入按钮
        binding.btnImportFromUrl.setOnClickListener {
            importFromUrl()
        }

        // 备份按钮 — 导出配置到剪贴板
        binding.btnBackup.setOnClickListener {
            backupConfig()
        }

        // 恢复按钮 — 从输入框导入配置
        binding.btnRestore.setOnClickListener {
            restoreConfig()
        }
    }

    private fun loadSettings() {
        binding.inputSubmitUrl.setText(configManager.getSubmitUrl())
        binding.inputQueryUrl.setText(configManager.getQueryUrl())
        binding.inputIntegralType.setText(configManager.getIntegralType())
        binding.inputMaxAttempts.setText(configManager.getMaxAttempts().toString())
        binding.inputDelayMin.setText(configManager.getDelayMin().toString())
        binding.inputDelayMax.setText(configManager.getDelayMax().toString())
    }

    private fun saveSettings() {
        val submitUrl = binding.inputSubmitUrl.text.toString().trim()
        val queryUrl = binding.inputQueryUrl.text.toString().trim()
        val integralType = binding.inputIntegralType.text.toString().trim()
        val maxAttemptsText = binding.inputMaxAttempts.text.toString().trim()
        val delayMinText = binding.inputDelayMin.text.toString().trim()
        val delayMaxText = binding.inputDelayMax.text.toString().trim()

        // 保存配置
        configManager.saveSubmitUrl(submitUrl)
        configManager.saveQueryUrl(queryUrl)
        configManager.saveIntegralType(integralType)

        // 解析最大次数
        val maxAttempts = maxAttemptsText.toIntOrNull() ?: ConfigManager.DEFAULT_MAX_ATTEMPTS
        configManager.saveMaxAttempts(maxAttempts)

        // 解析最小延迟
        val delayMin = delayMinText.toIntOrNull() ?: ConfigManager.DEFAULT_DELAY_MIN
        configManager.saveDelayMin(delayMin)

        // 解析最大延迟
        val delayMax = delayMaxText.toIntOrNull() ?: ConfigManager.DEFAULT_DELAY_MAX
        configManager.saveDelayMax(delayMax)

        // 如果最小延迟大于最大延迟，自动交换
        if (delayMin > delayMax) {
            configManager.saveDelayMin(delayMax)
            configManager.saveDelayMax(delayMin)
            Toast.makeText(this, "⚠ 最小延迟大于最大延迟，已自动交换", Toast.LENGTH_SHORT).show()
        }

        showToast("✅ 设置已保存，返回主页即可生效")
    }

    private fun resetToDefault() {
        configManager.resetToDefault()
        loadSettings()
        showToast("🔄 已恢复默认设置（工号保留）")
    }

    /**
     * 从网络链接导入配置
     */
    private fun importFromUrl() {
        val url = binding.inputImportUrl.text.toString().trim()

        if (url.isEmpty()) {
            showToast("❌ 请先输入分享链接")
            return
        }

        showToast("🔄 正在获取配置...")

        lifecycleScope.launch {
            try {
                // 从网页获取 JSON
                val jsonString = networkManager.fetchConfigFromUrl(url)

                if (jsonString == null) {
                    showToast("❌ 无法从链接获取配置")
                    return@launch
                }

                // 验证 JSON 是否有效
                if (!networkManager.validateConfigJson(jsonString)) {
                    showToast("❌ 链接内容不包含有效配置")
                    return@launch
                }

                // 导入配置
                val success = configManager.importConfig(jsonString)
                if (success) {
                    loadSettings()
                    // 将获取到的 JSON 显示在输入框中
                    binding.inputBackupData.setText(jsonString)
                    showToast("✅ 从网络导入成功")
                } else {
                    showToast("❌ 配置解析失败")
                }
            } catch (e: Exception) {
                showToast("❌ 网络请求失败：${e.message}")
            }
        }
    }

    /**
     * 备份配置 — 导出为 JSON 并复制到剪贴板
     */
    private fun backupConfig() {
        val json = configManager.exportConfig()

        // 复制到剪贴板
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("积分助手配置", json)
        clipboard.setPrimaryClip(clip)

        // 显示配置数据在输入框中（方便用户查看）
        binding.inputBackupData.setText(json)

        showToast("✅ 配置已复制到剪贴板，可粘贴保存")
    }

    /**
     * 恢复配置 — 从输入框解析 JSON 并导入
     */
    private fun restoreConfig() {
        val jsonString = binding.inputBackupData.text.toString().trim()

        if (jsonString.isEmpty()) {
            showToast("❌ 请先粘贴配置数据")
            return
        }

        val success = configManager.importConfig(jsonString)
        if (success) {
            loadSettings()
            showToast("✅ 配置已恢复")
        } else {
            showToast("❌ 配置格式错误，恢复失败")
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
