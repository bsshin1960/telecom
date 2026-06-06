package com.sbs.telecom.remote

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sbs.telecom.remote.databinding.ActivityHostBinding
import androidx.appcompat.app.AlertDialog
import java.net.NetworkInterface
import java.util.Collections

class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, RemoteControlService::class.java).apply {
                putExtra(RemoteControlService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RemoteControlService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(serviceIntent)
            binding.root.postDelayed({ updateServerStatus() }, 1000)
        } else {
            Toast.makeText(this, "화면 캡처 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtIpAddress.text = getLocalIpAddress()

        binding.btnAccessibilitySetting.setOnClickListener {
            handleAccessibilitySetup()
        }

        binding.btnToggleServer.setOnClickListener {
            if (isServiceRunning(RemoteControlService::class.java)) {
                stopRemoteControlService()
            } else {
                startRemoteControlService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.txtIpAddress.text = getLocalIpAddress()

        // WRITE_SECURE_SETTINGS 권한이 있으면 접근성 서비스를 자동 활성화 시도
        if (hasWriteSecureSettingsPermission()) {
            autoEnableAccessibilityService()
        }

        checkAccessibilityStatus()
        updateServerStatus()
    }

    // ──────────────────────────────────────────────
    // 핵심: WRITE_SECURE_SETTINGS 권한 보유 여부 확인
    // ──────────────────────────────────────────────
    private fun hasWriteSecureSettingsPermission(): Boolean {
        return packageManager.checkPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS,
            packageName
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * WRITE_SECURE_SETTINGS 권한이 있을 때 앱이 직접 접근성 서비스를 활성화합니다.
     * 이미 활성화된 기존 접근성 서비스는 유지하고, 우리 서비스만 추가합니다.
     */
    private fun autoEnableAccessibilityService() {
        val targetService = "$packageName/${RemoteAccessibilityService::class.java.canonicalName}"

        // 이미 활성화된 경우 스킵
        if (isAccessibilityServiceEnabled(this, RemoteAccessibilityService::class.java)) return

        try {
            val currentServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            // 기존 서비스 목록에 우리 서비스 추가
            val newServices = if (currentServices.isEmpty()) {
                targetService
            } else {
                "$currentServices:$targetService"
            }

            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newServices
            )
            Settings.Secure.putInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )

            // UI 즉시 갱신
            checkAccessibilityStatus()
            Toast.makeText(this, "접근성 서비스가 자동으로 활성화되었습니다.", Toast.LENGTH_SHORT).show()

        } catch (e: SecurityException) {
            // 권한이 실제로 부여되지 않은 경우 (가능성 낮음)
        }
    }

    /**
     * 접근성 설정 버튼을 눌렀을 때의 처리:
     * - WRITE_SECURE_SETTINGS 있으면 → 즉시 자동 활성화
     * - 없으면 → 어떻게 설정해야 하는지 안내 다이얼로그 표시
     */
    private fun handleAccessibilitySetup() {
        if (isAccessibilityServiceEnabled(this, RemoteAccessibilityService::class.java)) {
            Toast.makeText(this, "이미 접근성 서비스가 활성화되어 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (hasWriteSecureSettingsPermission()) {
            // 권한 있으면 즉시 자동 활성화
            autoEnableAccessibilityService()
        } else {
            // 권한 없으면 설정 방법 안내
            showSetupGuideDialog()
        }
    }

    /**
     * WRITE_SECURE_SETTINGS 권한이 없을 때 설정 방법을 안내하는 다이얼로그.
     * 아래 ADB 명령을 PC에서 1회 실행하면, 이후 앱이 자동으로 관리합니다.
     */
    private fun showSetupGuideDialog() {
        val adbCommand = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ 권한 설정 방법 안내")
            .setMessage(
                "PC 명령 프롬프트에 아래 명령을 실행해 주세요:\n\n" +
                adbCommand + "\n\n" +
                "※ 이 창은 5초 후에 자동으로 닫힙니다."
            )
            .setPositiveButton("수동 설정") { _, _ ->
                showAccessibilityDisclosure()
            }
            .setNegativeButton("닫기") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.show()

        // 5초(5000ms) 후 자동으로 다이얼로그 닫기
        Handler(mainLooper).postDelayed({
            if (!isFinishing && dialog.isShowing) {
                dialog.dismiss()
            }
        }, 5000)
    }

    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.accessibility_disclosure_title))
            .setMessage(getString(R.string.accessibility_disclosure_message))
            .setPositiveButton("접근성 설정 열기") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ──────────────────────────────────────────────
    // 서비스 실행 여부를 OS에서 직접 확인
    // ──────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (serviceInfo in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == serviceInfo.service.className) return true
        }
        return false
    }

    private fun checkAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled(this, RemoteAccessibilityService::class.java)
        val hasAutoPermission = hasWriteSecureSettingsPermission()

        binding.txtAccessibilityStatus.text = when {
            enabled && hasAutoPermission -> "상태: 활성화됨 ✓ (자동 관리)"
            enabled -> "상태: 활성화됨 ✓"
            hasAutoPermission -> "상태: 비활성화됨 (자동 복구 가능)"
            else -> "상태: 비활성화됨 — 버튼을 눌러 설정하세요"
        }
        binding.txtAccessibilityStatus.setTextColor(
            if (enabled) getColor(android.R.color.holo_green_light)
            else if (hasAutoPermission) getColor(android.R.color.holo_orange_light)
            else getColor(android.R.color.holo_red_light)
        )
    }

    private fun updateServerStatus() {
        val running = isServiceRunning(RemoteControlService::class.java)
        if (running) {
            binding.txtServerStatus.text = "상태: 서비스 실행 중 (포트: 8080)"
            binding.txtServerStatus.setTextColor(getColor(android.R.color.holo_green_light))
            binding.btnToggleServer.text = "원격 도움 중단"
            binding.btnToggleServer.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(android.R.color.holo_red_dark))
        } else {
            binding.txtServerStatus.text = "상태: 서비스 정지됨"
            binding.txtServerStatus.setTextColor(getColor(android.R.color.holo_red_light))
            binding.btnToggleServer.text = "원격 도움 요청"
            binding.btnToggleServer.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF6200EE.toInt())
        }
    }

    private fun startRemoteControlService() {
        if (!isAccessibilityServiceEnabled(this, RemoteAccessibilityService::class.java)) {
            // 권한 설정이 아예 안 되어 있다면 안내 창 띄우기
            if (!hasWriteSecureSettingsPermission()) {
                showSetupGuideDialog()
            } else {
                Toast.makeText(
                    this,
                    "접근성 서비스가 비활성화 상태입니다.\n화면 공유만 진행됩니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopRemoteControlService() {
        stopService(Intent(this, RemoteControlService::class.java))
        binding.root.postDelayed({ updateServerStatus() }, 500)
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedId = "${context.packageName}/${serviceClass.canonicalName}"
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equals(expectedId, ignoreCase = true)) return true
        }
        return false
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // wlan 인터페이스 우선 탐색
            for (networkInterface in interfaces) {
                if (!networkInterface.name.startsWith("wlan")) continue
                for (address in Collections.list(networkInterface.inetAddresses)) {
                    if (!address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        if (ip.indexOf(':') < 0) return ip  // IPv4
                    }
                }
            }
            // wlan 없으면 전체 탐색
            for (networkInterface in interfaces) {
                for (address in Collections.list(networkInterface.inetAddresses)) {
                    if (!address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        if (ip.indexOf(':') < 0) return ip
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "127.0.0.1"
    }
}
