package com.integral.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import com.integral.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var accountManager: AccountManager
    private lateinit var configManager: ConfigManager

    // 账号列表与当前选中账号
    private var accounts: MutableList<Account> = mutableListOf()
    private var selectedAccount: Account? = null

    // 防抖：程序化设置输入框时不触发保存
    private var isBinding = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val ADD_ACCOUNT_TAG = "＋ 添加账号"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        accountManager = AccountManager(this)
        configManager = ConfigManager(this)

        loadAccounts()
        setupUI()
        refreshAccountSpinner()
        selectCurrentAccount()
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 接收服务状态变化，刷新选中账号界面
        IntegralService.onStateChanged = { accountId ->
            if (accountId == selectedAccount?.id) renderState()
        }
        IntegralService.selectedAccountId = selectedAccount?.id
        // 刷新一次（服务可能已在后台运行）
        refreshSpinnerSelection()
        renderState()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (IntegralService.onStateChanged != null) {
            IntegralService.onStateChanged = null
        }
    }

    // ---------------- 账号管理 ----------------

    private fun loadAccounts() {
        accounts = accountManager.getAccounts()
        // 首次使用：从旧版共享配置迁移出一个默认账号
        if (accounts.isEmpty()) {
            val loginId = configManager.getLoginId()
            val target = configManager.getTargetScore()
            val mode = configManager.getMode()
            val acc = Account(
                id = "default",
                loginId = loginId,
                mode = mode,
                target = if (target > 0) target else 100
            )
            accounts.add(acc)
            accountManager.saveAccounts(accounts)
            accountManager.setCurrentAccountId(acc.id)
        }
    }

    private fun selectCurrentAccount() {
        val curId = accountManager.getCurrentAccountId()
        selectedAccount = accounts.firstOrNull { it.id == curId } ?: accounts.firstOrNull()
        selectedAccount?.let { accountManager.setCurrentAccountId(it.id) }
        bindInputs()
        renderState()
    }

    private fun refreshAccountSpinner() {
        val labels = accounts.map { acc ->
            val base = if (acc.loginId.isNotEmpty()) acc.loginId else "未设置工号"
            val note = acc.note.orEmpty().trim()
            if (note.isNotEmpty()) "$base（$note）" else base
        }.toMutableList()
        labels.add(ADD_ACCOUNT_TAG)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.accountSpinner.adapter = adapter
        // 选中当前账号
        val index = accounts.indexOfFirst { it.id == selectedAccount?.id }
        if (index >= 0) binding.accountSpinner.setSelection(index)
    }

    private fun refreshSpinnerSelection() {
        val index = accounts.indexOfFirst { it.id == selectedAccount?.id }
        if (index >= 0 && binding.accountSpinner.selectedItemPosition != index) {
            binding.accountSpinner.setSelection(index)
        }
    }

    // ---------------- UI ----------------

    private fun setupUI() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 日志区域可滚动，便于回看长日志
        binding.tvLog.movementMethod = ScrollingMovementMethod()

        // 账号切换
        binding.accountSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    if (position < accounts.size) {
                        val acc = accounts[position]
                        if (acc.id != selectedAccount?.id) {
                            selectedAccount = acc
                            accountManager.setCurrentAccountId(acc.id)
                            IntegralService.selectedAccountId = acc.id
                            IntegralService.serviceInstance?.updateNotification()
                            bindInputs()
                            renderState()
                        }
                    } else {
                        // 选中了“添加账号”
                        showAddAccountDialog()
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        binding.btnAddAccount.setOnClickListener { showAddAccountDialog() }

        binding.btnDelAccount.setOnClickListener { showDeleteAccountDialog() }

        // 工号输入：实时保存到选中账号
        binding.inputLoginId.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isBinding || selectedAccount == null) return
                selectedAccount!!.loginId = s?.toString()?.trim() ?: ""
                accountManager.saveAccounts(accounts)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 目标值输入：实时保存
        binding.inputTargetScore.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isBinding || selectedAccount == null) return
                val v = s?.toString()?.trim()?.toIntOrNull() ?: 0
                selectedAccount!!.target = v
                accountManager.saveAccounts(accounts)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 备注输入：实时保存（空则存 null，便于下拉列表判断）
        binding.inputNote.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isBinding || selectedAccount == null) return
                val note = s?.toString()?.trim()
                selectedAccount!!.note = if (note.isNullOrEmpty()) null else note
                accountManager.saveAccounts(accounts)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 模式切换
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (selectedAccount == null) return@setOnCheckedChangeListener
            selectedAccount!!.mode = if (checkedId == R.id.modeIncrement) "increment" else "reach"
            updateTargetLabel(selectedAccount!!.mode)
            accountManager.saveAccounts(accounts)
        }

        binding.btnStart.setOnClickListener { startSelectedAccount() }
        binding.btnStop.setOnClickListener { stopSelectedAccount() }
    }

    private fun bindInputs() {
        val acc = selectedAccount ?: return
        isBinding = true
        binding.inputLoginId.setText(acc.loginId)
        binding.inputLoginId.setSelection(acc.loginId.length)
        if (acc.mode == "increment") {
            binding.modeIncrement.isChecked = true
        } else {
            binding.modeReach.isChecked = true
        }
        updateTargetLabel(acc.mode)
        binding.inputTargetScore.setText(acc.target.toString())
        binding.inputTargetScore.setSelection(acc.target.toString().length)
        binding.inputNote.setText(acc.note.orEmpty())
        binding.inputNote.setSelection(acc.note.orEmpty().length)
        isBinding = false
    }

    private fun updateTargetLabel(mode: String) {
        if (mode == "increment") {
            binding.tvTargetLabel.text = "增加积分数"
            binding.inputTargetScore.hint = "请输入要增加的分数"
        } else {
            binding.tvTargetLabel.text = "目标积分"
            binding.inputTargetScore.hint = "请输入目标积分"
        }
    }

    // ---------------- 启动 / 停止 ----------------

    private fun startSelectedAccount() {
        val acc = selectedAccount ?: return
        // 确保最新输入已写入账号
        acc.loginId = binding.inputLoginId.text.toString().trim()
        if (acc.loginId.isEmpty()) {
            Toast.makeText(this, "请先输入工号", Toast.LENGTH_SHORT).show()
            return
        }
        val v = binding.inputTargetScore.text.toString().trim().toIntOrNull()
        if (v == null || v <= 0) {
            val msg = if (acc.mode == "increment") "请输入有效的增加分数！" else "请输入有效的目标积分！"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        acc.target = v
        accountManager.saveAccounts(accounts)

        // 提交/查询 URL 为空时先提示，避免空跑报错
        if (configManager.getSubmitUrl().isBlank() || configManager.getQueryUrl().isBlank()) {
            Toast.makeText(this, "请先在设置中填写提交/查询 URL", Toast.LENGTH_SHORT).show()
            return
        }

        // 启动/确保前台服务，再启动该账号任务
        val serviceIntent = Intent(this, IntegralService::class.java).apply {
            action = IntegralService.ACTION_START
            putExtra(IntegralService.EXTRA_ACCOUNT, Gson().toJson(acc))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        renderState()
    }

    private fun stopSelectedAccount() {
        val acc = selectedAccount ?: return
        val serviceIntent = Intent(this, IntegralService::class.java).apply {
            action = IntegralService.ACTION_STOP
            putExtra(IntegralService.EXTRA_ACCOUNT_ID, acc.id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        renderState()
    }

    // ---------------- 状态渲染 ----------------

    private fun renderState() {
        val acc = selectedAccount ?: run {
            updateButtons(running = false)
            return
        }
        val state = IntegralService.tasks[acc.id]
        if (state != null && state.isRunning) {
            if (binding.tvStatus.text.toString() != state.statusText) {
                binding.tvStatus.text = state.statusText
            }
            if (binding.tvLog.text.toString() != state.logContent) {
                binding.tvLog.text = state.logContent
            }
            updateButtons(running = true)
        } else {
            // 停止/完成后保留最终状态文案与日志，便于查看结果
            val status = if (state != null) state.statusText else "⏸ 待命中"
            if (binding.tvStatus.text.toString() != status) {
                binding.tvStatus.text = status
            }
            val logText = if (state != null && state.logContent.isNotEmpty()) state.logContent else "等待执行..."
            if (binding.tvLog.text.toString() != logText) {
                binding.tvLog.text = logText
            }
            updateButtons(running = false)
        }
        // 同步账号切换器选中项（例如其他途径切换了账号）
        refreshSpinnerSelection()
    }

    private fun updateButtons(running: Boolean) {
        runOnUiThread {
            binding.btnStart.isEnabled = !running
            binding.btnStart.alpha = if (running) 0.4f else 1.0f
            binding.btnStop.isEnabled = running
            binding.btnStop.alpha = if (running) 1.0f else 0.4f
            binding.inputLoginId.isEnabled = !running
            binding.inputTargetScore.isEnabled = !running
            binding.modeReach.isEnabled = !running
            binding.modeIncrement.isEnabled = !running
        }
    }

    // ---------------- 添加 / 删除账号 ----------------

    private fun showAddAccountDialog() {
        val loginEdit = android.widget.EditText(this).apply {
            hint = "请输入工号"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val noteEdit = android.widget.EditText(this).apply {
            hint = "备注（选填，便于区分账号）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(loginEdit)
            addView(noteEdit)
        }
        // 打开对话框前选中的真实账号位置（用于关闭后复位）
        val prevIndex = accounts.indexOfFirst { it.id == selectedAccount?.id }
        val dialog = AlertDialog.Builder(this)
            .setTitle("添加账号")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val loginId = loginEdit.text.toString().trim()
                val note = noteEdit.text.toString().trim()
                val acc = Account(
                    id = "acc_${UUID.randomUUID()}",
                    loginId = loginId,
                    mode = "reach",
                    target = 100,
                    note = if (note.isEmpty()) null else note
                )
                accounts.add(acc)
                accountManager.saveAccounts(accounts)
                selectedAccount = acc
                accountManager.setCurrentAccountId(acc.id)
                IntegralService.selectedAccountId = acc.id
                IntegralService.serviceInstance?.updateNotification()
                refreshAccountSpinner()
                binding.accountSpinner.setSelection(accounts.indexOfFirst { it.id == acc.id })
                bindInputs()
                renderState()
            }
            .setNegativeButton("取消", null)
            .create()
        // 对话框关闭后（取消按钮或系统返回键都会触发 dismiss），若 Spinner 仍停在
        // 「添加账号」项，复位回之前的真实账号；否则再次点「添加账号」时选中项
        // 未变化，onItemSelected 不会触发，导致“点了没反应”
        dialog.setOnDismissListener {
            val cur = binding.accountSpinner.selectedItemPosition
            if (cur >= accounts.size && prevIndex >= 0) {
                binding.accountSpinner.setSelection(prevIndex)
            }
        }
        dialog.show()
    }

    private fun showDeleteAccountDialog() {
        val acc = selectedAccount ?: return
        val running = IntegralService.tasks[acc.id]?.isRunning == true
        if (running) {
            Toast.makeText(this, "该账号正在运行，请先停止", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除账号")
            .setMessage("确定删除账号「${run {
                val base = if (acc.loginId.isNotEmpty()) acc.loginId else "未设置工号"
                val note = acc.note.orEmpty().trim()
                if (note.isNotEmpty()) "$base（$note）" else base
            }}」？")
            .setPositiveButton("删除") { _, _ ->
                accountManager.removeAccount(acc.id)
                accounts.removeAll { it.id == acc.id }
                IntegralService.tasks.remove(acc.id)
                selectedAccount = accounts.firstOrNull()
                selectedAccount?.let { accountManager.setCurrentAccountId(it.id) }
                IntegralService.selectedAccountId = selectedAccount?.id
                IntegralService.serviceInstance?.updateNotification()
                refreshAccountSpinner()
                bindInputs()
                renderState()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 权限 ----------------

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
}
