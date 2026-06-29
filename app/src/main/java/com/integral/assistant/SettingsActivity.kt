package com.integral.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.integral.assistant.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager(this)

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

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
