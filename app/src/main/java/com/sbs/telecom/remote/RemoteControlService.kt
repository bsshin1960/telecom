package com.sbs.telecom.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.ws
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RemoteControlService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val RELAY_PORT = 80
        // RELAY_HOST는 SharedPreferences에서 동적으로 읽습니다. getRelayHost()를 사용하세요.
        const val PREF_NAME = "TeleControlPrefs"
        const val PREF_KEY_RELAY_HOST = "relay_host"
        const val DEFAULT_RELAY_HOST = "54.242.81.228" // 로컬 테스트용 기본값

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "RemoteControlChannel"
        private const val TAG = "RemoteControlService"

        // 화면 변화가 없을 때에도 주기적으로 프레임을 캡처하는 간격 (ms)
        private const val PERIODIC_CAPTURE_INTERVAL_MS = 300L

        private const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var currentSessionId: String? = null
            internal set

        /**
         * 미래에 서 비스(Service)에서 직접 호출할 수 있도록 static 참조 보유
         */
        @Volatile
        var instance: RemoteControlService? = null
            private set
    }

    /**
     * SharedPreferences에서 릴레이 서버 호스트를 읽어 반환합니다.
     * 설정이 없으면 DEFAULT_RELAY_HOST를 사용합니다.
     */
    fun getRelayHost(): String {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_RELAY_HOST, DEFAULT_RELAY_HOST) ?: DEFAULT_RELAY_HOST
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val client = HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 30_000 // 대역폭 불안정 시 끊김 방지를 위해 핑 주기 연장 (15초 -> 30초)
        }
        engine {
            config {
                connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS) // 연결 제한 시간 연장 (15초 -> 30초)
                readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }
    private var clientJob: Job? = null
    private var webSocketSession: WebSocketSession? = null

    @Volatile
    private var isClientConnected = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var audioRecord: AudioRecord? = null
    private var audioRecordJob: Job? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var captureWidth = 0
    private var captureHeight = 0

    // 최신 프레임을 보관하여 새 클라이언트에게 즉시 제공
    @Volatile
    private var latestFrame: ByteArray? = null

    // 마지막 프레임 전송 시각 (주기적 캡처에 사용)
    private val lastFrameTime = AtomicLong(0L)

    // 주기적 캡처 Runnable
    private var periodicCaptureRunnable: Runnable? = null

    // 프레임 처리 중 동시 접근 방지
    private val isProcessingFrame = AtomicBoolean(false)
    private val lastSentFrameTime = AtomicLong(0L)
    private var reusableFullBitmap: Bitmap? = null
    private var reusableCroppedBitmap: Bitmap? = null

    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastRotation = -1

    private var localCurrentPath = ""

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service starting")
        localCurrentPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        isRunning = true
        instance = this
        // Android 9+: startForegroundService() 후 5초 이내 startForeground() 필수 호출
        // 이 호출이 없으면 OS가 강제로 앱을 종료함 (ForegroundServiceDidNotStartInTimeException)
        startImmediateForeground()
    }

    /**
     * onCreate()에서 즉시 호출되는 기본 포그라운드 알림.
     * Android 9의 FGS 5초 타임아웃 크래시를 방지하기 위해 반드시 존재해야 합니다.
     * 이후 startScreenCapture()에서 MediaProjection 타입으로 업그레이드됩니다.
     */
    private fun startImmediateForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "원격 도움 및 화면 공유",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, HostActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TeleControl — 서비스 실행 중")
            .setContentText("원격 도움 서비스가 시작되는 중입니다...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        // Android 9(Pie, API 28) 이하는 타입 파라미터 없이 단순 호출
        // Android 10(Q, API 29) 이상은 타입 지정 필요하나 여기서는 일단 기본 타입으로 시작
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "startImmediateForeground failed: ${e.message}")
        }
        Log.d(TAG, "startImmediateForeground: basic foreground notification set")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }

        Log.d(TAG, "onStartCommand: resultCode=$resultCode, hasData=${resultData != null}")

        if (resultCode != 0 && resultData != null) {
            startScreenCapture(resultCode, resultData)
            startWebSocketClient()
        } else {
            Log.e(TAG, "onStartCommand: Invalid parameters — resultCode=$resultCode, resultData=$resultData. Stopping.")
            // startImmediateForeground()가 이미 onCreate()에서 호출되었으므로
            // 추가 startForeground() 없이 그냥 stopSelf() 가능
            stopSelf()
        }

        return START_NOT_STICKY
    }



    private fun promoteToMediaProjectionForeground() {
        // Android 9(API 28) 이하는 타입 파라미터 자체가 존재하지 않으므로 업그레이드 불필요
        // Android 10(Q, API 29) 이상만 MediaProjection 타입으로 업그레이드
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "promoteToMediaProjectionForeground: skipped for Android < Q (API 29)")
            return
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, HostActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TeleControl — 서비스 실행 중")
            .setContentText("원격 도움 서비스가 실행 중입니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            startForeground(NOTIFICATION_ID, notification, type)
            Log.d(TAG, "promoteToMediaProjectionForeground: promoted with types=$type")
        } catch (e: Exception) {
            Log.e(TAG, "promoteToMediaProjectionForeground failed: ${e.message}")
        }
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        Log.d(TAG, "startScreenCapture: initializing MediaProjection")
        promoteToMediaProjectionForeground()
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        // MediaProjection 콜백 등록 (투사가 중단되면 로그 남김)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
            }
        }, null)

        val (realWidth, realHeight) = getRealScreenSize()
        val scale = 0.3f // 대역폭 초과 방지를 위해 해상도 비율 축소 (0.5f -> 0.3f)
        captureWidth = (realWidth * scale).toInt()
        captureHeight = (realHeight * scale).toInt()
        val density = resources.displayMetrics.densityDpi

        Log.d(TAG, "startScreenCapture: resolution=${realWidth}x${realHeight}, capture=${captureWidth}x${captureHeight}")

        // ImageReader 버퍼를 4로 증가 — 비동기 처리 시 버퍼 부족 방지
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 4)

        backgroundThread = HandlerThread("ImageReaderThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RemoteCapture",
            captureWidth,
            captureHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            processImageFromReader(reader)
        }, backgroundHandler)

        // 주기적 프레임 캡처 시작 — 화면 전환 시 onImageAvailable이 호출되지 않을 수 있으므로
        startPeriodicCapture()

        // 화면 회전 감지를 위한 DisplayListener 등록
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val defaultDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        lastRotation = defaultDisplay?.rotation ?: -1

        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                    val freshDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                    val currentRotation = freshDisplay?.rotation ?: -1
                    if (currentRotation != lastRotation && currentRotation != -1) {
                        Log.d(TAG, "Display rotation changed: $lastRotation -> $currentRotation. Recreating VirtualDisplay.")
                        lastRotation = currentRotation
                        recreateVirtualDisplay()
                    }
                }
            }
        }
        displayManager.registerDisplayListener(displayListener, backgroundHandler)

        // 시스템 소리(오디오) 캡처 시작
        startAudioCapture()

        Log.d(TAG, "startScreenCapture: VirtualDisplay created successfully")
    }

    /**
     * ImageReader에서 최신 이미지를 획득하여 JPEG로 인코딩하고 브로드캐스트합니다.
     * 이 메서드는 backgroundHandler 스레드에서 동기적으로 실행됩니다.
     */
    private fun processImageFromReader(reader: ImageReader) {
        if (!isClientConnected) {
            try {
                reader.acquireLatestImage()?.close()
            } catch (_: Exception) {}
            return
        }

        // 1. 프레임 시간 간격 스로틀링 (최대 25 FPS 제한)
        val now = System.currentTimeMillis()
        if (now - lastSentFrameTime.get() < 250L) { // 대역폭 초과 방지를 위해 초당 프레임 수 제한 (40ms -> 250ms, 약 4 FPS)
            try {
                reader.acquireLatestImage()?.close()
            } catch (_: Exception) {}
            return
        }

        // 2. 동시 처리 및 전송 방지 (Backpressure)
        if (!isProcessingFrame.compareAndSet(false, true)) {
            try {
                reader.acquireLatestImage()?.close()
            } catch (_: Exception) {}
            return
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * captureWidth

                val fullWidth = captureWidth + rowPadding / pixelStride
                
                // full 비트맵 생성 최소화 및 재활용
                if (reusableFullBitmap == null || reusableFullBitmap!!.width != fullWidth || reusableFullBitmap!!.height != captureHeight) {
                    reusableFullBitmap?.recycle()
                    reusableFullBitmap = Bitmap.createBitmap(fullWidth, captureHeight, Bitmap.Config.ARGB_8888)
                }
                reusableFullBitmap!!.copyPixelsFromBuffer(buffer)

                // 이미지를 즉시 close하여 버퍼 반환
                image.close()
                image = null

                // cropped 비트맵 생성 최소화 및 재활용 (Canvas draw 적용)
                val bitmap = if (rowPadding > 0) {
                    if (reusableCroppedBitmap == null || reusableCroppedBitmap!!.width != captureWidth || reusableCroppedBitmap!!.height != captureHeight) {
                        reusableCroppedBitmap?.recycle()
                        reusableCroppedBitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
                    }
                    val canvas = android.graphics.Canvas(reusableCroppedBitmap!!)
                    val srcRect = android.graphics.Rect(0, 0, captureWidth, captureHeight)
                    val dstRect = android.graphics.Rect(0, 0, captureWidth, captureHeight)
                    canvas.drawBitmap(reusableFullBitmap!!, srcRect, dstRect, null)
                    reusableCroppedBitmap!!
                } else {
                    reusableFullBitmap!!
                }

                val outStream = ByteArrayOutputStream(captureWidth * captureHeight / 4)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 25, outStream) // 대역폭 초과 방지를 위해 화질을 25로 낮춤
                val jpegBytes = outStream.toByteArray()

                broadcastVideo(jpegBytes)
            } else {
                isProcessingFrame.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image capture error: ${e.message}")
            isProcessingFrame.set(false)
        } finally {
            try { image?.close() } catch (_: Exception) {}
        }
    }

    private fun broadcastVideo(jpegBytes: ByteArray) {
        val packet = ByteArray(1 + jpegBytes.size)
        packet[0] = 0 // 0: 비디오 프레임
        System.arraycopy(jpegBytes, 0, packet, 1, jpegBytes.size)

        latestFrame = packet
        lastFrameTime.set(System.currentTimeMillis())

        val session = webSocketSession
        if (session != null) {
            serviceScope.launch {
                try {
                    if (session.isActive && isClientConnected) {
                        session.send(Frame.Binary(true, packet))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Send video frame error: ${e.message}")
                } finally {
                    isProcessingFrame.set(false)
                    lastSentFrameTime.set(System.currentTimeMillis())
                }
            }
        } else {
            isProcessingFrame.set(false)
        }
    }

    private fun startAudioCapture() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted — audio capture skipped")
            return
        }

        try {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AUDIO_CHANNEL_CONFIG)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else 2048

            val builder = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)

            val projection = mediaProjection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) {
                try {
                    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    builder.setAudioPlaybackCaptureConfig(config)
                    Log.d(TAG, "Using AudioPlaybackCaptureConfiguration (System Audio)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set AudioPlaybackCaptureConfig, falling back to MIC: ${e.message}")
                    builder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                }
            } else {
                builder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                Log.d(TAG, "Using MediaRecorder.AudioSource.MIC for audio capture")
            }

            audioRecord = builder.build()

            audioRecord?.startRecording()
            Log.d(TAG, "Audio recording started successfully")

            audioRecordJob = serviceScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        val audioData = buffer.copyOf(readBytes)
                        broadcastAudio(audioData)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording: ${e.message}", e)
        }
    }

    private fun broadcastAudio(audioBytes: ByteArray) {
        val packet = ByteArray(1 + audioBytes.size)
        packet[0] = 1 // 1: 오디오 프레임
        System.arraycopy(audioBytes, 0, packet, 1, audioBytes.size)
        broadcastFrame(packet)
    }

    /**
     * 주기적으로 ImageReader에서 프레임을 캡처합니다.
     */
    private fun startPeriodicCapture() {
        periodicCaptureRunnable = object : Runnable {
            override fun run() {
                val handler = backgroundHandler ?: return
                val reader = imageReader ?: return

                // 마지막 프레임 이후 일정 시간이 지났으면 강제 캡처 시도
                val elapsed = System.currentTimeMillis() - lastFrameTime.get()
                if (elapsed >= PERIODIC_CAPTURE_INTERVAL_MS && isClientConnected) {
                    processImageFromReader(reader)
                }

                handler.postDelayed(this, PERIODIC_CAPTURE_INTERVAL_MS)
            }
        }
        backgroundHandler?.postDelayed(periodicCaptureRunnable!!, PERIODIC_CAPTURE_INTERVAL_MS)
    }

    private fun startWebSocketClient() {
        val relayHost = getRelayHost()
        Log.d(TAG, "startWebSocketClient: connecting to ws://$relayHost:$RELAY_PORT/register")
        clientJob = serviceScope.launch(Dispatchers.IO) {
            var retryCount = 0
            val maxRetries = 3

            while (isActive && retryCount <= maxRetries) {
                if (retryCount > 0) {
                    Log.w(TAG, "릴레이 서버 재연결 시도 $retryCount/$maxRetries ...")
                    kotlinx.coroutines.delay(5_000L)
                    if (!isActive) break
                }

                try {
                    client.ws(host = relayHost, port = RELAY_PORT, path = "/register") {
                        webSocketSession = this
                        FileTransferSession.activeSession = this
                        retryCount = 0 // 연결 성공 시 재시도 카운터 초기화
                        Log.d(TAG, "WebSocket connected to Relay Server")

                        for (frame in incoming) {
                            if (!isActive) break
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                Log.d(TAG, "Relay message: $text")
                                if (text.startsWith("ID=")) {
                                    val session_id = text.substringAfter("ID=").trim()
                                    Log.d(TAG, "Session ID received: $session_id")
                                    currentSessionId = session_id

                                    val broadcastIntent = Intent("com.sbs.telecom.remote.SESSION_ID_RECEIVED").apply {
                                        putExtra("session_id", session_id)
                                    }
                                    sendBroadcast(broadcastIntent)
                                } else if (text == "CLIENT_CONNECTED") {
                                    isClientConnected = true
                                    Log.d(TAG, "Client connected. Sending handshake device=android")
                                    send(Frame.Text("device=android"))

                                    // Send the latest frame immediately if available
                                    latestFrame?.let { frameData ->
                                        send(Frame.Binary(true, frameData))
                                    }
                                    triggerImmediateCapture()
                                } else if (text == "CLIENT_DISCONNECTED") {
                                    isClientConnected = false
                                    Log.d(TAG, "Client disconnected from relay")
                                    // 즉시 stopSelf() 하지 않고 재연결 대기
                                    // PC가 다시 접속하면 CLIENT_CONNECTED 메시지 수신
                                } else if (text == "device=android") {
                                    FileTransferSession.isPeerAndroid = true
                                    Log.d(TAG, "Peer device is Android (Client)")
                                } else {
                                    if (text == "CLIENT_READY") {
                                        // Client ready trigger
                                        latestFrame?.let { frameData ->
                                            send(Frame.Binary(true, frameData))
                                            Log.d(TAG, "Sent initial frame on CLIENT_READY")
                                        }
                                        triggerImmediateCapture()
                                    } else if (text.startsWith("NAV_")) {
                                        handleNavCommand(text)
                                    } else if (text.startsWith("FS_")) {
                                        handleFileCommand(text)
                                    } else {
                                        parseAndInjectTouch(text)
                                    }
                                }
                            }
                        }
                    }
                    // WebSocket 블록이 정상 종료되면 재시도 불필요
                    break
                } catch (e: CancellationException) {
                    Log.d(TAG, "WebSocket connection cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket connection error (attempt ${retryCount + 1}): ${e.message}")
                    retryCount++
                    if (retryCount > maxRetries) {
                        Log.e(TAG, "최대 재연결 횟수 초과. 서비스를 중단합니다.")
                        // UI에 알림 브로드캐스트
                        sendBroadcast(Intent("com.sbs.telecom.remote.SESSION_ID_RECEIVED").apply {
                            putExtra("session_id", "ERROR")
                        })
                    }
                } finally {
                    webSocketSession = null
                    FileTransferSession.activeSession = null
                    FileTransferSession.isPeerAndroid = false
                    isClientConnected = false
                    currentSessionId = null
                    val broadcastIntent = Intent("com.sbs.telecom.remote.SESSION_ID_RECEIVED").apply {
                        putExtra("session_id", "DISCONNECTED")
                    }
                    sendBroadcast(broadcastIntent)
                }
            }
        }
    }

    private fun broadcastFrame(bytes: ByteArray) {
        val session = webSocketSession ?: return
        serviceScope.launch {
            try {
                if (session.isActive && isClientConnected) {
                    session.send(Frame.Binary(true, bytes))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send frame error: ${e.message}")
            }
        }
    }

    /**
     * PC(ViewerClient)에게 텍스트 상태 메시지를 전송합니다.
     * 예) GOING_TO_SETTINGS, RETURNED_FROM_SETTINGS
     */
    fun sendStatusToPC(statusMessage: String) {
        val session = webSocketSession ?: return
        if (!isClientConnected) return
        serviceScope.launch {
            try {
                if (session.isActive) {
                    session.send(Frame.Text(statusMessage))
                    Log.d(TAG, "sendStatusToPC: sent '$statusMessage'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendStatusToPC error: ${e.message}")
            }
        }
    }

    private fun parseAndInjectTouch(text: String) {
        try {
            Log.d(TAG, "parseAndInjectTouch: received '$text'")
            val params = mutableMapOf<String, String>()
            for (token in text.split(",")) {
                val eqIdx = token.indexOf('=')
                if (eqIdx > 0 && eqIdx < token.length - 1) {
                    params[token.substring(0, eqIdx)] = token.substring(eqIdx + 1)
                }
            }
            val action = params["action"]?.toIntOrNull()
            val x = params["x"]?.toFloatOrNull()
            val y = params["y"]?.toFloatOrNull()
            if (action != null && x != null && y != null) {
                val service = RemoteAccessibilityService.instance
                if (service != null) {
                    Log.d(TAG, "parseAndInjectTouch: dispatching action=$action, x=$x, y=$y")
                    service.injectTouch(action, x, y)
                } else {
                    Log.w(TAG, "parseAndInjectTouch: AccessibilityService instance is null — touch ignored.")
                }
            } else {
                Log.w(TAG, "parseAndInjectTouch: invalid params — action=$action, x=$x, y=$y from '$text'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseAndInjectTouch error: ${e.message}")
        }
    }

    /**
     * 클라이언트의 가상 네비게이션 버튼 명령을 처리합니다.
     * AccessibilityService의 Global Action을 통해 홈/뒤로/최근앱을 실행합니다.
     */
    private fun handleNavCommand(command: String) {
        if (command == "NAV_FILE_TRANSFER") {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                Log.d(TAG, "handleNavCommand: FILE_TRANSFER (Downloads folder) opened")
            } catch (e: Exception) {
                Log.e(TAG, "handleNavCommand: Failed to open Downloads folder: ${e.message}")
            }
            return
        }

        val service = RemoteAccessibilityService.instance
        if (service != null) {
            when (command) {
                "NAV_BACK" -> {
                    service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    Log.d(TAG, "handleNavCommand: BACK performed")
                }
                "NAV_HOME" -> {
                    service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    Log.d(TAG, "handleNavCommand: HOME performed")
                }
                "NAV_RECENT" -> {
                    service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                    Log.d(TAG, "handleNavCommand: RECENTS performed")
                }
                else -> Log.w(TAG, "handleNavCommand: unknown command '$command'")
            }
        } else {
            Log.w(TAG, "handleNavCommand: AccessibilityService not available")
        }
    }

    private fun handleFileCommand(command: String) {
        Log.d(TAG, "handleFileCommand: $command")
        
        val activeListener = FileTransferSession.activeListener
        if (activeListener != null) {
            activeListener.onMessageReceived(command)
            return
        }

        try {
            when {
                command.startsWith("FS_OPEN_UI") -> {
                    // Send initial folder file list back to the client immediately
                    sendLocalFileList(localCurrentPath)
                    
                    // Start FileTransferActivity on Host side automatically
                    val intent = Intent(this, FileTransferActivity::class.java).apply {
                        putExtra("is_client", false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                command.startsWith("FS_LIST_REQ") -> {
                    val requestedPath = command.substringAfter("FS_LIST_REQ|", "")
                    val targetPath = if (requestedPath.isNotEmpty()) requestedPath else localCurrentPath
                    localCurrentPath = targetPath
                    sendLocalFileList(targetPath)
                }
                command.startsWith("FS_FILE_REQ|") -> {
                    val requestedPath = command.substringAfter("FS_FILE_REQ|")
                    sendLocalFile(requestedPath)
                }
                command.startsWith("FS_FILE_SEND|") -> {
                    val parts = command.split("|")
                    if (parts.size >= 3) {
                        val targetPath = parts[1]
                        val base64Data = parts[2]
                        val filename = targetPath.substringAfterLast("/").substringAfterLast("\\")
                        val localFile = java.io.File(localCurrentPath, filename).absolutePath
                        saveRemoteFile(localFile, base64Data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in silent background handleFileCommand: ${e.message}")
        }
    }

    private fun sendLocalFileList(requestedPath: String) {
        val currentDir = java.io.File(requestedPath)
        val jsonArray = org.json.JSONArray()
        try {
            val list = currentDir.listFiles()
            if (list != null) {
                val dirs = mutableListOf<java.io.File>()
                val files = mutableListOf<java.io.File>()
                
                for (file in list) {
                    try {
                        if (file.isDirectory) {
                            dirs.add(file)
                        } else if (file.isFile) {
                            files.add(file)
                        }
                    } catch (e: Exception) {}
                }
                dirs.sortBy { it.name }
                files.sortBy { it.name }

                for (d in dirs) {
                    val jsonObject = org.json.JSONObject().apply {
                        put("name", d.name)
                        put("is_dir", true)
                        put("size", 0)
                    }
                    jsonArray.put(jsonObject)
                }
                for (f in files) {
                    val jsonObject = org.json.JSONObject().apply {
                        put("name", f.name)
                        put("is_dir", false)
                        put("size", f.length())
                    }
                    jsonArray.put(jsonObject)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile file list silently: ${e.message}")
        }
        sendTextCommand("FS_LIST_RESP|$requestedPath|$jsonArray")
    }

    private fun sendLocalFile(requestedPath: String) {
        val file = java.io.File(requestedPath)
        if (!file.exists()) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                sendTextCommand("FS_FILE_SEND|$requestedPath|$base64Data")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read requested file silently: ${e.message}")
            }
        }
    }

    private fun saveRemoteFile(targetPath: String, base64Data: String) {
        val file = java.io.File(targetPath)
        val filename = file.name
        if (file.exists()) {
            Log.w(TAG, "File already exists: ${file.absolutePath}")
            sendTextCommand("FS_FILE_EXISTS|$filename")
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
                file.writeBytes(bytes)
                Log.d(TAG, "Silently saved remote file to: ${file.absolutePath}")
                
                // Send success message to PC client
                sendTextCommand("FS_FILE_SEND_OK|$filename|${file.absolutePath}")
                
                // Immediately send the updated file list to PC so the PC UI refreshes!
                sendLocalFileList(localCurrentPath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save remote file silently: ${e.message}")
                sendTextCommand("FS_FILE_SEND_ERR|$filename|${e.message}")
            }
        }
    }

    private fun sendTextCommand(cmd: String) {
        val session = webSocketSession
        if (session != null && session.isActive) {
            serviceScope.launch {
                try {
                    session.send(Frame.Text(cmd))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send text command silently: ${e.message}")
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: orientation=${newConfig.orientation}")
        recreateVirtualDisplay()
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: run {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION") windowManager.defaultDisplay
        }

        val realMetrics = android.util.DisplayMetrics()
        display.getRealMetrics(realMetrics)

        var realWidth = realMetrics.widthPixels
        var realHeight = realMetrics.heightPixels

        val rotation = display.rotation
        val isLandscape = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270

        if (isLandscape && realWidth < realHeight) {
            realWidth = realMetrics.heightPixels
            realHeight = realMetrics.widthPixels
        } else if (!isLandscape && realWidth > realHeight) {
            realWidth = realMetrics.heightPixels
            realHeight = realMetrics.widthPixels
        }

        return Pair(realWidth, realHeight)
    }

    private fun recreateVirtualDisplay() {
        val mp = mediaProjection ?: return
        Log.d(TAG, "recreateVirtualDisplay: updating resolution")
        
        try {
            val (realWidth, realHeight) = getRealScreenSize()
            val scale = 0.3f // 대역폭 초과 방지를 위해 해상도 비율 축소 유지 (0.5f -> 0.3f)
            captureWidth = (realWidth * scale).toInt()
            captureHeight = (realHeight * scale).toInt()
            val density = resources.displayMetrics.densityDpi
            
            Log.d(TAG, "recreateVirtualDisplay: new resolution=${realWidth}x${realHeight}, capture=${captureWidth}x${captureHeight}")
            
            // 새 해상도의 ImageReader 생성
            val newImageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 4)
            newImageReader.setOnImageAvailableListener({ reader ->
                processImageFromReader(reader)
            }, backgroundHandler)
            
            val vd = virtualDisplay
            if (vd != null) {
                Log.d(TAG, "recreateVirtualDisplay: resizing existing VirtualDisplay")
                vd.resize(captureWidth, captureHeight, density)
                vd.setSurface(newImageReader.surface)
                
                // 기존 ImageReader 자원 해제
                imageReader?.close()
                imageReader = newImageReader
            } else {
                Log.d(TAG, "recreateVirtualDisplay: creating new VirtualDisplay")
                imageReader = newImageReader
                virtualDisplay = mp.createVirtualDisplay(
                    "RemoteCapture",
                    captureWidth,
                    captureHeight,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface,
                    null,
                    backgroundHandler
                )
            }
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Error in recreateVirtualDisplay: ${e.message}", e)
        }
    }

    private fun triggerImmediateCapture() {
        backgroundHandler?.post {
            try {
                val vd = virtualDisplay
                val ir = imageReader
                if (vd != null && ir != null) {
                    Log.d(TAG, "triggerImmediateCapture: forcing redraw by resetting surface")
                    vd.setSurface(null)
                    vd.setSurface(ir.surface)
                } else {
                    Log.w(TAG, "triggerImmediateCapture: virtualDisplay or imageReader is null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger immediate capture: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: cleaning up resources")
        isRunning = false
        instance = null
        currentSessionId = null

        // DisplayListener 해제
        displayListener?.let {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(it)
        }
        displayListener = null

        // 주기적 캡처 중지
        periodicCaptureRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        periodicCaptureRunnable = null

        // 오디오 레코더 정리
        try {
            audioRecordJob?.cancel()
            audioRecordJob = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord cleanup error: ${e.message}")
        }

        try { clientJob?.cancel() } catch (e: Exception) { Log.e(TAG, "Client job cancel error: ${e.message}") }
        try { client.close() } catch (e: Exception) { Log.e(TAG, "Client close error: ${e.message}") }
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { mediaProjection?.stop() } catch (e: Exception) {}
        try { imageReader?.close() } catch (e: Exception) {}
        try { backgroundThread?.quitSafely() } catch (e: Exception) {}
        
        reusableFullBitmap?.recycle()
        reusableFullBitmap = null
        reusableCroppedBitmap?.recycle()
        reusableCroppedBitmap = null

        latestFrame = null
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
