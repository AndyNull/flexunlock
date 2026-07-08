package com.flexunlock.simple

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.flexunlock.simple.accessibility.SideKeyAccessibilityService
import com.flexunlock.simple.databinding.ActivityMainBinding
import com.flexunlock.simple.root.RootA11yEnabler
import com.flexunlock.simple.service.AppRedirectService
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHomeMode.setOnClickListener { performAction(Action.HOME_MODE) }
        binding.btnRecentsMode.setOnClickListener { performAction(Action.RECENTS_MODE) }
        binding.btnEnable.setOnClickListener { performAction(Action.ENABLE) }
        binding.btnReset.setOnClickListener { performAction(Action.RESET) }
        binding.btnCheckState.setOnClickListener { performAction(Action.CHECK) }
        binding.btnPrintStates.setOnClickListener { performAction(Action.PRINT_STATES) }

        binding.btnEnableGuard.setOnClickListener { startGuard() }
        binding.btnDisableGuard.setOnClickListener { stopGuard() }
        binding.btnOpenAccessibility.setOnClickListener { openAccessibilitySettings() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private enum class Action { HOME_MODE, RECENTS_MODE, ENABLE, RESET, CHECK, PRINT_STATES }

    private fun performAction(action: Action) {
        lifecycleScope.launch {
            setButtonsEnabled(false)
            when (action) {
                Action.HOME_MODE -> {
                    val r = DeviceStateSwitcher.enableHomeMode()
                    showSnack(if (r is DeviceStateSwitcher.Result.Success) r.message else "失败：${(r as DeviceStateSwitcher.Result.Failure).error}")
                }
                Action.RECENTS_MODE -> {
                    val r = DeviceStateSwitcher.enableRecentsMode()
                    showSnack(if (r is DeviceStateSwitcher.Result.Success) r.message else "失败：${(r as DeviceStateSwitcher.Result.Failure).error}")
                }
                Action.ENABLE -> {
                    val r = DeviceStateSwitcher.enableConcurrent()
                    showSnack(if (r is DeviceStateSwitcher.Result.Success) "已切换 state 4" else "失败")
                }
                Action.RESET -> {
                    val r = DeviceStateSwitcher.reset()
                    showSnack(if (r is DeviceStateSwitcher.Result.Success) "已还原" else "还原失败")
                }
                Action.CHECK -> {
                    val state = DeviceStateSwitcher.getCurrentState()
                    val id = state.toIntOrNull()
                    showDiagnostic(if (id != null) "当前状态 ID: $id\n${DeviceStateSwitcher.stateName(id)}" else "查询结果: $state")
                }
                Action.PRINT_STATES -> {
                    showDiagnostic("设备支持的状态:\n${DeviceStateSwitcher.getSupportedStates()}")
                }
            }
            setButtonsEnabled(true)
            refreshStatus()
        }
    }

    private fun startGuard() {
        lifecycleScope.launch {
            val r = DeviceStateSwitcher.enableHomeMode()
            showSnack(if (r is DeviceStateSwitcher.Result.Success) "已启动桌面到外屏" else "失败")
            refreshStatus()
        }
    }

    private fun stopGuard() {
        lifecycleScope.launch {
            val r = DeviceStateSwitcher.reset()
            showSnack(if (r is DeviceStateSwitcher.Result.Success) "已还原" else "还原失败")
            refreshStatus()
        }
    }

    private fun openAccessibilitySettings() {
        lifecycleScope.launch {
            binding.btnOpenAccessibility.isEnabled = false
            if (isAccessibilityEnabled()) {
                showSnack("双击音量键已启用")
            } else {
                showSnack("正在用 root 启用...")
                val ok = RootA11yEnabler.enable()
                showSnack(if (ok) "已自动启用（root）" else "启用失败")
            }
            binding.btnOpenAccessibility.isEnabled = true
            refreshStatus()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, SideKeyAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(expected.flattenToString())
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnHomeMode.isEnabled = enabled
        binding.btnRecentsMode.isEnabled = enabled
        binding.btnEnable.isEnabled = enabled
        binding.btnReset.isEnabled = enabled
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val rootOk = DeviceStateSwitcher.isRootAvailable()
            binding.tvRootStatus.text = if (rootOk) "Root：已授权" else "Root：未授权"
            binding.tvRootStatus.setTextColor(getColor(if (rootOk) R.color.success else R.color.error))

            if (rootOk) {
                val state = DeviceStateSwitcher.getCurrentState()
                val id = state.toIntOrNull()
                binding.tvCurrentState.text = if (id != null) {
                    "当前状态: ${DeviceStateSwitcher.stateName(id)}"
                } else getString(R.string.status_idle) + "（查询失败: $state）"
            } else {
                binding.tvCurrentState.text = getString(R.string.status_no_root)
            }

            val redirectRunning = getSharedPreferences("flexunlock", MODE_PRIVATE).getBoolean("redirect_enabled", false)
            binding.tvGuardStatus.text = if (redirectRunning) "App重定向：运行中" else "App重定向：未启动"

            binding.tvA11yStatus.text = if (isAccessibilityEnabled()) "双击音量键：已启用" else "双击音量键：未启用"
        }
    }

    private fun showSnack(msg: String) { Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show() }
    private fun showDiagnostic(text: String) {
        Timber.i("Diagnostic: %s", text)
        binding.tvDiagnostic.text = text
        binding.tvDiagnostic.visibility = View.VISIBLE
    }
}
