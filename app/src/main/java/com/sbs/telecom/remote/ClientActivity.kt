package com.sbs.telecom.remote

import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sbs.telecom.remote.databinding.ActivityClientBinding
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.ws
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ClientActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ClientActivity"
    }

    private lateinit var binding: ActivityClientBinding
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 15_000 // 15초 핑 간격
        }
        engine {
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(10, TimeUnit.SECONDS)
                writeTimeout(10, TimeUnit.SECONDS)
            }
        }
    }

    private var webSocketSession: WebSocketSession? = null
    private var connectionJob: Job? = null
    private var isFullScreenMode = false

    // 터치 이벤트 직렬 전송을 위한 채널 — 순서 보장
    private val touchChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private var touchSenderJob: Job? = null
    
    private var audioTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener {
            if (connectionJob?.isActive == true) {
                disconnect()
            } else {
                val ip = binding.edtIpAddress.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "IP 주소를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                } else {
                    connectToHost(ip)
                }
            }
        }

        binding.remoteDisplayView.touchEventListener = { action, xRatio, yRatio ->
            val session = webSocketSession
            if (session != null) {
                // Channel에 넣으면 별도 코루틴이 순서대로 전송합니다.
                touchChannel.trySend("action=$action,x=$xRatio,y=$yRatio")
            }
        }

        // 자동 연결 및 전체 화면(몰입 모드) 처리
        val ip = intent.getStringExtra("EXTRA_HOST_IP")
        if (ip != null) {
            isFullScreenMode = true
            binding.connectionPanel.visibility = android.view.View.GONE

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }

            connectToHost(ip)
        }
    }

    private fun connectToHost(ip: String) {
        binding.btnConnect.text = "연결 중..."
        binding.btnConnect.isEnabled = false

        // 핵심 수정: WebSocket 연결을 IO 디스패처에서 수행
        // Main 디스패처에서 실행하면 네트워크 작업으로 ANR 발생
        connectionJob = activityScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to ws://$ip:8080/control")
                client.ws(host = ip, port = 8080, path = "/control") {
                    webSocketSession = this
                    Log.d(TAG, "WebSocket connected successfully")

                    // 터치 이벤트 직렬 전송 코루틴 시작 — Channel에서 순서대로 하나씩 꺼내 전송
                    touchSenderJob = activityScope.launch(Dispatchers.IO) {
                        for (message in touchChannel) {
                            try {
                                val session = webSocketSession
                                if (session != null) {
                                    session.send(Frame.Text(message))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Touch send error: ${e.message}")
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        binding.btnConnect.text = "연결 끊기"
                        binding.btnConnect.isEnabled = true
                        Toast.makeText(this@ClientActivity, "서버에 연결되었습니다.", Toast.LENGTH_SHORT).show()
                    }

                    // 오디오 수신 재생 준비
                    initAudioTrack()

                    for (frame in incoming) {
                        if (!isActive) break

                        when (frame) {
                            is Frame.Binary -> {
                                val bytes = frame.readBytes()
                                if (bytes.size > 1) {
                                    val type = bytes[0].toInt()
                                    if (type == 0) { // 비디오
                                        val bitmap = BitmapFactory.decodeByteArray(bytes, 1, bytes.size - 1)
                                        if (bitmap != null) {
                                            withContext(Dispatchers.Main) {
                                                binding.remoteDisplayView.updateFrame(bitmap)

                                                // 상대방 화면 종횡비에 맞게 내 화면 방향을 자동 전환 (사용자 경험 개선)
                                                val isBmpLandscape = bitmap.width > bitmap.height
                                                val currentOrientation = resources.configuration.orientation
                                                
                                                if (isBmpLandscape && currentOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                                                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                } else if (!isBmpLandscape && currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                                                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                                }
                                            }
                                        } else {
                                            Log.w(TAG, "Failed to decode video frame (${bytes.size} bytes)")
                                        }
                                    } else if (type == 1) { // 오디오
                                        audioTrack?.write(bytes, 1, bytes.size - 1)
                                    }
                                }
                            }
                            is Frame.Text -> {
                                // 서버에서 텍스트 메시지가 올 경우 (향후 확장용)
                                Log.d(TAG, "Received text frame")
                            }
                            else -> { /* ping/pong 등은 라이브러리가 자동 처리 */ }
                        }
                    }
                    Log.d(TAG, "WebSocket incoming channel closed")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Connection cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ClientActivity, "연결 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                webSocketSession = null
                touchSenderJob?.cancel()
                touchSenderJob = null
                releaseAudioTrack()
                try {
                    withContext(Dispatchers.Main) {
                        resetConnectionState()
                        if (isFullScreenMode) {
                            finish()
                        }
                    }
                } catch (_: CancellationException) {
                    // 이미 취소된 스코프에서 Main 전환 실패 시 무시
                }
            }
        }
    }

    private fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        webSocketSession = null
        resetConnectionState()
        Toast.makeText(this, "연결이 해제되었습니다.", Toast.LENGTH_SHORT).show()
        if (isFullScreenMode) {
            finish()
        }
    }

    private fun resetConnectionState() {
        binding.btnConnect.text = "연결"
        binding.btnConnect.isEnabled = true
    }

    private fun initAudioTrack() {
        try {
            val sampleRate = 16000
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else 4096
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized and started playing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Log.d(TAG, "AudioTrack released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        touchSenderJob?.cancel()
        connectionJob?.cancel()
        touchChannel.close()
        releaseAudioTrack()
        activityScope.cancel()
        client.close()
    }
}
