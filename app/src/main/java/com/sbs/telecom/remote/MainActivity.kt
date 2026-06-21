package com.sbs.telecom.remote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sbs.telecom.remote.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var autoClickRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHostMode.setOnClickListener {
            cancelAutoClick()
            startActivity(Intent(this, HostActivity::class.java))
        }

        binding.btnClientMode.setOnClickListener {
            cancelAutoClick()
            if (binding.layoutIpInput.visibility == View.VISIBLE) {
                binding.layoutIpInput.visibility = View.GONE
            } else {
                binding.layoutIpInput.visibility = View.VISIBLE
            }
        }

        binding.btnFileTransfer.setOnClickListener {
            cancelAutoClick()
            if (FileTransferSession.activeSession != null) {
                val isHostMode = isServiceRunning(RemoteControlService::class.java)
                val intent = Intent(this, FileTransferActivity::class.java).apply {
                    putExtra("is_client", !isHostMode)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "원격 연결이 수립된 후에 파일 전송 기능을 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnConnect.setOnClickListener {
            cancelAutoClick()
            val code = binding.edtIpAddress.text.toString().trim()
            if (code.length != 6 || !code.all { it.isDigit() }) {
                Toast.makeText(this, "올바른 6자리 연결 ID를 입력해 주세요.", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, ClientActivity::class.java).apply {
                    putExtra("EXTRA_HOST_IP", code)
                }
                startActivity(intent)
            }
        }

        // 릴레이 서버 설정 버튼 동적 추가
        setupSettingsButton()

        // 3초 후 도움 받기(HostMode) 자동 클릭 예약
        val runnable = Runnable {
            if (!isFinishing && !isDestroyed) {
                binding.btnHostMode.performClick()
            }
        }
        autoClickRunnable = runnable
        binding.btnHostMode.postDelayed(runnable, 3000)
    }

    private fun cancelAutoClick() {
        autoClickRunnable?.let {
            binding.btnHostMode.removeCallbacks(it)
            autoClickRunnable = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoClick()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun setupSettingsButton() {
        val prefs = getSharedPreferences(RemoteControlService.PREF_NAME, Context.MODE_PRIVATE)
        val currentHost = prefs.getString(RemoteControlService.PREF_KEY_RELAY_HOST, RemoteControlService.DEFAULT_RELAY_HOST) ?: RemoteControlService.DEFAULT_RELAY_HOST

        val btnRelaySettings = Button(this).apply {
            text = "⚙ 릴레이 서버 IP: $currentHost"
            textSize = 12f
            setBackgroundColor(0xFF1E1E2E.toInt())
            setTextColor(0xFF94A3B8.toInt())
            setOnClickListener { showRelaySettingsDialog() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16)
                bottomMargin = dpToPx(16)
            }
        }

        // Copyright 텍스트 바로 위에 추가
        val rootLinear = binding.root as LinearLayout
        rootLinear.addView(btnRelaySettings, rootLinear.childCount - 1)
    }

    private fun showRelaySettingsDialog() {
        val prefs = getSharedPreferences(RemoteControlService.PREF_NAME, Context.MODE_PRIVATE)
        val currentHost = prefs.getString(RemoteControlService.PREF_KEY_RELAY_HOST, RemoteControlService.DEFAULT_RELAY_HOST) ?: RemoteControlService.DEFAULT_RELAY_HOST

        val editText = EditText(this).apply {
            setText(currentHost)
            hint = "예: 54.123.45.67 또는 127.0.0.1"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        AlertDialog.Builder(this)
            .setTitle("🔧 릴레이 서버 IP 설정")
            .setMessage("AWS EC2 공인 IP 또는 로컬 테스트용 PC IP를 입력하세요.")
            .setView(editText)
            .setPositiveButton("저장") { _, _ ->
                val newHost = editText.text.toString().trim()
                if (newHost.isNotEmpty()) {
                    prefs.edit().putString(RemoteControlService.PREF_KEY_RELAY_HOST, newHost).apply()
                    Toast.makeText(this, "릴레이 서버 IP가 \"$newHost\"로 저장되었습니다.", Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(this, "IP 주소를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
