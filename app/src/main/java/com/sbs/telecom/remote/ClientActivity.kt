package com.sbs.telecom.remote

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
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

    // Host 화면의 가로/세로 상태를 추적하여 방향 전환을 1회만 수행합니다
    private var lastHostIsLandscape: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 내비게이션 바 초기 숨김 설정
        binding.navBar.visibility = android.view.View.GONE

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

        // 스와이프 업 제스처 감지하여 내비게이션 바 보이기 설정
        var touchStartX = 0f
        var touchStartY = 0f
        binding.remoteDisplayView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val deltaY = touchStartY - event.y
                    val deltaX = Math.abs(event.x - touchStartX)
                    val viewHeight = v.height
                    
                    // 터치 시작 지점이 화면 하단 20% 이내이고, 위 방향으로 80픽셀 이상 드래그되었을 때
                    if (touchStartY > viewHeight * 0.8f && deltaY > 80f && deltaX < 150f) {
                        showNavBarTemporarily()
                    }
                }
            }
            // false를 반환하여 remoteDisplayView의 onTouchEvent(원격 터치)도 그대로 수행되도록 함
            false
        }

        // 가상 네비게이션 버튼 설정 — Host 기기에 홈/뒤로/최근앱 명령을 전송
        binding.btnNavBack.setOnClickListener {
            touchChannel.trySend("NAV_BACK")
        }
        binding.btnNavHome.setOnClickListener {
            touchChannel.trySend("NAV_HOME")
        }
        binding.btnNavRecent.setOnClickListener {
            touchChannel.trySend("NAV_RECENT")
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

    /**
     * configChanges로 Activity 파괴를 방지하므로, 기기 회전 시 이 콜백이 호출됩니다.
     * RemoteDisplayView를 강제로 재측정/재그리기하여 가로/세로 전환 시 화면이 멈추지 않도록 합니다.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: orientation=${newConfig.orientation}")
        // 뷰 크기가 변경되었으므로 재측정을 요청하고,
        // 레이아웃이 완료된 후 확실하게 다시 그리기를 수행합니다.
        binding.remoteDisplayView.requestLayout()
        binding.remoteDisplayView.postDelayed({
            binding.remoteDisplayView.invalidate()
        }, 150)
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
                        showNavBarTemporarily() // 연결 완료 시 내비게이션 바 3초간 노출 안내
                    }

                    // 오디오 수신 재생 준비
                    initAudioTrack()

                    // 서버에 클라이언트가 준비되었음을 알리고 최초 프레임 전송 요청
                    send(Frame.Text("CLIENT_READY"))

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

                                                // Host 화면 방향이 변경되었을 때만 1회 회전 요청
                                                val isHostLandscape = bitmap.width > bitmap.height
                                                if (isHostLandscape != lastHostIsLandscape) {
                                                    lastHostIsLandscape = isHostLandscape
                                                    requestedOrientation = if (isHostLandscape) {
                                                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                                    } else {
                                                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                                    }
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

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideNavBarRunnable = Runnable {
        binding.navBar.visibility = android.view.View.GONE
    }

    private fun showNavBarTemporarily() {
        uiHandler.removeCallbacks(hideNavBarRunnable)
        binding.navBar.visibility = android.view.View.VISIBLE
        uiHandler.postDelayed(hideNavBarRunnable, 3000) // 3초 후 자동 숨김
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
