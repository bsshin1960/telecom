package com.sbs.telecom.remote

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun isRestrictedSettingActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                "android:access_restricted_settings",
                android.os.Process.myUid(),
                packageName
            )
            mode == AppOpsManager.MODE_ERRORED
        } catch (e: Exception) {
            false
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "$label 복사 완료!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "복사 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "애플리케이션 정보를 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * WRITE_SECURE_SETTINGS 권한이 없거나 제한된 설정 상태일 때 해결 방법을 안내하는 다이얼로그.
     * ADB 명령어 및 원버튼 복사 기능, 앱 설정 바로가기 단축 버튼을 제공합니다.
     */
    private fun showSetupGuideDialog() {
        val pmCommand = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val appopsCommand = "adb shell appops set $packageName android:access_restricted_settings allow"

        val container = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(layout)

        val introText = TextView(this).apply {
            text = "원격 터치 제어 입력을 위해 접근성 권한이 필요합니다. 접근성 설정에서 이 앱이 희미하게 보여 설정을 켤 수 없다면 아래 방법을 참조해 주세요."
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
        }
        layout.addView(introText)

        // Section 1: ADB
        val section1Title = TextView(this).apply {
            text = "방법 1. PC에서 ADB 명령 실행 (권장)"
            setTextColor(0xFF03DAC6.toInt()) // Teal
            textSize = 15f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
        layout.addView(section1Title)

        val section1Desc = TextView(this).apply {
            text = "아래 명령어를 PC 명령 프롬프트에 실행하면 앱이 시스템에 등록되어 접근성 권한이 자동으로 켜집니다."
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
        }
        layout.addView(section1Desc)

        // Command 1
        val cmd1Text = TextView(this).apply {
            text = pmCommand
            setBackgroundColor(0xFF2A2A2A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            textSize = 12f
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(cmd1Text)

        val btnCopyCmd1 = Button(this).apply {
            text = "자동 권한 부여 명령어 복사"
            setBackgroundColor(0xFF3700B3.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                copyToClipboard(pmCommand, "자동 권한 부여 명령어")
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
                bottomMargin = dpToPx(12)
            }
        }
        layout.addView(btnCopyCmd1)

        val section1Desc2 = TextView(this).apply {
            text = "수동으로 접근성 스위치만 활성화하려면 아래 명령으로 제한된 설정을 즉시 풀 수 있습니다."
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
        }
        layout.addView(section1Desc2)

        // Command 2
        val cmd2Text = TextView(this).apply {
            text = appopsCommand
            setBackgroundColor(0xFF2A2A2A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            textSize = 12f
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(cmd2Text)

        val btnCopyCmd2 = Button(this).apply {
            text = "제한 해제 명령어 복사"
            setBackgroundColor(0xFF3700B3.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                copyToClipboard(appopsCommand, "제한 해제 명령어")
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
                bottomMargin = dpToPx(20)
            }
        }
        layout.addView(btnCopyCmd2)

        // Section 2: Manual
        val section2Title = TextView(this).apply {
            text = "방법 2. 스마트폰 단독 수동 설정 (3단계 순서대로 진행)"
            setTextColor(0xFF03DAC6.toInt()) // Teal
            textSize = 15f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
        layout.addView(section2Title)

        val section2Desc = TextView(this).apply {
            text = "PC가 없거나 ADB 사용이 어려운 경우 아래 순서대로 제한을 풀어보세요:\n\n" +
                    "1. **[1단계]** 아래 `[1단계: 접근성 설정 열기]`를 누르고 '설치된 앱 > Telecom'으로 들어간 뒤 제한 경고 팝업이 뜨면 [확인]을 누르고 뒤로 가기로 돌아옵니다.\n\n" +
                    "2. **[2단계]** 아래 `[2단계: 애플리케이션 정보 열기]`를 눌러 이동한 후, 우측 상단의 [점 3개 메뉴] -> [제한된 설정 허용]을 선택합니다. (생체 인식/패턴 인증 필요)\n\n" +
                    "3. **[3단계]** 아래 `[3단계: 접근성 설정 열기 (최종 활성화)]`를 눌러 다시 '설치된 앱 > Telecom'으로 이동하여 접근성 스위치를 활성화합니다.\n\n" +
                    "※ 반드시 1단계를 거쳐 차단 경고창을 먼저 띄우셔야만 2단계의 점 3개 메뉴가 나타납니다!"
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
        }
        layout.addView(section2Desc)

        val btnOpenAccessibility1 = Button(this).apply {
            text = "1단계: 접근성 설정 열기 (차단 팝업 띄우기)"
            setBackgroundColor(0xFF6200EE.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                showAccessibilityDisclosure()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
        layout.addView(btnOpenAccessibility1)

        val btnOpenAppInfo = Button(this).apply {
            text = "2단계: 애플리케이션 정보 열기 (제한 해제)"
            setBackgroundColor(0xFF018786.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                openAppInfo()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
        layout.addView(btnOpenAppInfo)

        val btnOpenAccessibility2 = Button(this).apply {
            text = "3단계: 접근성 설정 열기 (최종 활성화)"
            setBackgroundColor(0xFF6200EE.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                showAccessibilityDisclosure()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
        layout.addView(btnOpenAccessibility2)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("⚙️ 접근성 및 제한된 설정 해결")
            .setView(container)
            .setNegativeButton("닫기") { dialog, _ -> dialog.dismiss() }
            .show()
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
        val restricted = !enabled && isRestrictedSettingActive()

        binding.txtAccessibilityStatus.text = when {
            enabled && hasAutoPermission -> "상태: 활성화됨 ✓ (자동 관리)"
            enabled -> "상태: 활성화됨 ✓"
            hasAutoPermission -> "상태: 비활성화됨 (자동 복구 가능)"
            restricted -> "상태: 제한된 설정으로 제어됨 ⚠"
            else -> "상태: 비활성화됨 — 버튼을 눌러 설정하세요"
        }
        binding.txtAccessibilityStatus.setTextColor(
            if (enabled) getColor(android.R.color.holo_green_light)
            else if (hasAutoPermission) getColor(android.R.color.holo_orange_light)
            else if (restricted) getColor(android.R.color.holo_orange_light)
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
