package com.giadinhsk.app

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.giadinhsk.app.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    // Màn hình khoá mã PIN
    private val main = Handler(Looper.getMainLooper())
    private val pinInput = StringBuilder()
    private val pinDots = mutableListOf<View>()
    private var unlocked = false
    private var failedAttempts = 0
    private var lockedUntil = 0L
    // Đang mở màn hình hệ thống (cấp quyền, tối ưu pin) thì quay lại không cần nhập PIN.
    private var expectingReturn = false
    private val lockoutTick = Runnable { updateLockout() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_STATUS || key == Prefs.KEY_ENABLED) runOnUiThread { renderState() }
    }

    private val foregroundPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) askBackgroundPermission() else toast(getString(R.string.err_permission))
    }

    private val backgroundPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startSharing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        unlocked = savedInstanceState?.getBoolean(STATE_UNLOCKED) ?: false
        setupLock()

        binding.inputName.setText(prefs.name)
        binding.inputServer.setText(prefs.server)

        binding.buttonToggle.setOnClickListener {
            if (prefs.enabled) stopSharing() else onStartClicked()
        }

        // Nếu đang bật mà service bị hệ thống tắt, mở app sẽ chạy lại.
        if (prefs.enabled && hasForegroundPermission()) LocationService.start(this)
    }

    override fun onStart() {
        super.onStart()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        renderState()
        renderLock()
    }

    override fun onResume() {
        super.onResume()
        expectingReturn = false
    }

    override fun onStop() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        // Rời khỏi app thì khoá lại, lần sau mở phải nhập PIN.
        if (!isChangingConfigurations && !expectingReturn) unlocked = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_UNLOCKED, unlocked)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setupLock() {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        repeat(PIN.length) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { setMargins(dp(10), 0, dp(10), 0) }
                setBackgroundResource(R.drawable.pin_dot_empty)
            }
            binding.lock.pinDots.addView(dot)
            pinDots += dot
        }

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        for (k in keys) {
            val key = TextView(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(76)
                    height = dp(76)
                    setMargins(dp(12), dp(8), dp(12), dp(8))
                }
                gravity = Gravity.CENTER
                text = k
                setTextColor(ContextCompat.getColor(context, R.color.white))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k == "⌫") 24f else 30f)
                if (k.isNotEmpty()) {
                    setBackgroundResource(R.drawable.pin_key_bg)
                    isClickable = true
                    if (k == "⌫") contentDescription = getString(R.string.lock_delete)
                    setOnClickListener { onPinKey(k) }
                }
            }
            binding.lock.keypad.addView(key)
        }
    }

    private fun renderLock() {
        binding.lock.root.visibility = if (unlocked) View.GONE else View.VISIBLE
        if (!unlocked) {
            pinInput.clear()
            renderPinDots()
            updateLockout()
        }
    }

    private fun renderPinDots() {
        pinDots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(if (i < pinInput.length) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty)
        }
    }

    private fun onPinKey(k: String) {
        if (SystemClock.elapsedRealtime() < lockedUntil) return
        if (k == "⌫") {
            if (pinInput.isNotEmpty()) pinInput.deleteCharAt(pinInput.length - 1)
        } else if (pinInput.length < PIN.length) {
            pinInput.append(k)
            binding.lock.lockError.text = ""
        }
        renderPinDots()
        if (pinInput.length == PIN.length) main.postDelayed({ checkPin() }, 120)
    }

    private fun checkPin() {
        if (pinInput.toString() == PIN) {
            unlocked = true
            failedAttempts = 0
            binding.lock.lockError.text = ""
            binding.lock.root.animate().alpha(0f).setDuration(200).withEndAction {
                binding.lock.root.visibility = View.GONE
                binding.lock.root.alpha = 1f
            }.start()
            return
        }

        failedAttempts++
        pinInput.clear()
        renderPinDots()
        binding.lock.root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        )
        ObjectAnimator.ofFloat(binding.lock.pinDots, "translationX", 0f, 24f, -24f, 16f, -16f, 8f, -8f, 0f)
            .setDuration(400)
            .start()

        if (failedAttempts >= MAX_ATTEMPTS) {
            failedAttempts = 0
            lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS
            updateLockout()
        } else {
            binding.lock.lockError.setText(R.string.lock_wrong)
        }
    }

    /** Hiện đếm ngược khi bị khoá tạm vì nhập sai nhiều lần. */
    private fun updateLockout() {
        main.removeCallbacks(lockoutTick)
        val remaining = lockedUntil - SystemClock.elapsedRealtime()
        if (remaining <= 0) {
            if (lockedUntil != 0L) {
                lockedUntil = 0L
                binding.lock.lockError.text = ""
            }
            return
        }
        binding.lock.lockError.text = getString(R.string.lock_wait, ((remaining + 999) / 1000).toInt())
        main.postDelayed(lockoutTick, 1000)
    }

    private fun onStartClicked() {
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
        val server = Prefs.normalizeServer(binding.inputServer.text?.toString().orEmpty())

        if (name.isEmpty() || server.isEmpty()) {
            toast(getString(R.string.err_fields))
            return
        }
        prefs.name = name
        prefs.server = server
        binding.inputServer.setText(server)

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        expectingReturn = true
        foregroundPermissions.launch(perms.toTypedArray())
    }

    private fun askBackgroundPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            startSharing()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bg_title)
            .setMessage(R.string.bg_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                expectingReturn = true
                backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton(R.string.later) { _, _ -> startSharing() }
            .setCancelable(false)
            .show()
    }

    private fun startSharing() {
        prefs.enabled = true
        prefs.status = getString(R.string.status_connecting)
        LocationService.start(this)
        renderState()
        askIgnoreBatteryOptimizations()
    }

    private fun stopSharing() {
        prefs.enabled = false
        prefs.status = ""
        LocationService.stop(this)
        renderState()
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                expectingReturn = true
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun renderState() {
        val on = prefs.enabled
        binding.textStatus.setText(if (on) R.string.status_on else R.string.status_off)
        binding.textStatus.setTextColor(ContextCompat.getColor(this, if (on) R.color.green else R.color.gray))
        binding.textDetail.text = if (on) prefs.status else getString(R.string.status_off_detail)
        binding.buttonToggle.setText(if (on) R.string.stop else R.string.start)
        binding.buttonToggle.setBackgroundColor(ContextCompat.getColor(this, if (on) R.color.gray else R.color.pink))
        binding.inputName.isEnabled = !on
        binding.inputServer.isEnabled = !on
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasForegroundPermission() =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val PIN = "5176"
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MS = 30_000L
        private const val STATE_UNLOCKED = "unlocked"
    }
}
