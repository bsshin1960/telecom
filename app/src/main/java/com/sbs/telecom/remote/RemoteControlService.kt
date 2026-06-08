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
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RemoteControlService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val SERVER_PORT = 8080

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
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var server: ApplicationEngine? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var audioRecord: AudioRecord? = null
    private var audioRecordJob: Job? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var captureWidth = 0
    private var captureHeight = 0

    private val activeSessions = ConcurrentHashMap.newKeySet<WebSocketSession>()

    // 최신 프레임을 보관하여 새 클라이언트에게 즉시 제공
    @Volatile
    private var latestFrame: ByteArray? = null

    // 마지막 프레임 전송 시각 (주기적 캡처에 사용)
    private val lastFrameTime = AtomicLong(0L)

    // 주기적 캡처 Runnable
    private var periodicCaptureRunnable: Runnable? = null

    // 프레임 처리 중 동시 접근 방지
    private val isProcessingFrame = AtomicBoolean(false)

    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastRotation = -1

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service starting")
        isRunning = true
        startForegroundServiceWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }

        Log.d(TAG, "onStartCommand: resultCode=$resultCode, hasData=${resultData != null}")

        if (resultCode != 0 && resultData != null) {
            startScreenCapture(resultCode, resultData)
            startWebSocketServer()
        } else {
            Log.e(TAG, "onStartCommand: Invalid parameters — resultCode=$resultCode, resultData=$resultData. Stopping.")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
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
            .setContentText("포트 $SERVER_PORT 에서 연결 대기 중입니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.d(TAG, "startForegroundServiceWithNotification: Notification started")
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        Log.d(TAG, "startScreenCapture: initializing MediaProjection")
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        // MediaProjection 콜백 등록 (투사가 중단되면 로그 남김)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
            }
        }, null)

        val (realWidth, realHeight) = getRealScreenSize()
        val scale = 0.5f
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

        // 핵심 수정: onImageAvailable에서 이미지 획득과 인코딩을 동기적으로 처리
        // 이전에 코루틴으로 넘기면 acquireLatestImage()와 close() 사이에 갭이 생겨서
        // MaxImagesAcquiredException이 발생하여 프레임 캡처가 멈출 수 있었음
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
        // 연결된 세션이 없으면 프레임 인코딩 스킵 (CPU 절약)
        // 단, 이미지는 반드시 acquire+close 해야 함 (안 하면 버퍼가 가득 참)
        if (activeSessions.isEmpty()) {
            try {
                reader.acquireLatestImage()?.close()
            } catch (_: Exception) {}
            return
        }

        // 동시 처리 방지
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

                // rowPadding이 있으면 GPU 버퍼 정렬 때문에 실제 화면보다 넓은 비트맵이 생성됨
                // 이 패딩 영역에는 쓰레기 값(노이즈 줄무늬)이 포함되므로 크롭 필요
                val fullWidth = captureWidth + rowPadding / pixelStride
                val fullBitmap = Bitmap.createBitmap(
                    fullWidth,
                    captureHeight,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)

                // 이미지를 즉시 close — 버퍼를 빨리 반환해야 다음 프레임을 받을 수 있음
                image.close()
                image = null

                // 패딩이 있는 경우 실제 화면 영역만 크롭, 없으면 그대로 사용
                val bitmap = if (rowPadding > 0) {
                    val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, captureWidth, captureHeight)
                    fullBitmap.recycle()
                    cropped
                } else {
                    fullBitmap
                }

                val outStream = ByteArrayOutputStream(captureWidth * captureHeight / 4)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outStream)
                val jpegBytes = outStream.toByteArray()
                bitmap.recycle()

                broadcastVideo(jpegBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image capture error: ${e.message}")
        } finally {
            // image가 아직 close되지 않은 경우 (에러 시)
            try { image?.close() } catch (_: Exception) {}
            isProcessingFrame.set(false)
        }
    }

    private fun broadcastVideo(jpegBytes: ByteArray) {
        val packet = ByteArray(1 + jpegBytes.size)
        packet[0] = 0 // 0: 비디오 프레임
        System.arraycopy(jpegBytes, 0, packet, 1, jpegBytes.size)

        latestFrame = packet
        lastFrameTime.set(System.currentTimeMillis())
        broadcastFrame(packet)
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
     * 화면이 다른 앱으로 전환될 때 onImageAvailable 콜백만으로는 충분하지 않을 수 있으므로,
     * 주기적으로 최신 이미지를 확인하여 클라이언트에게 전송합니다.
     */
    private fun startPeriodicCapture() {
        periodicCaptureRunnable = object : Runnable {
            override fun run() {
                val handler = backgroundHandler ?: return
                val reader = imageReader ?: return

                // 마지막 프레임 이후 일정 시간이 지났으면 강제 캡처 시도
                val elapsed = System.currentTimeMillis() - lastFrameTime.get()
                if (elapsed >= PERIODIC_CAPTURE_INTERVAL_MS && activeSessions.isNotEmpty()) {
                    processImageFromReader(reader)
                }

                handler.postDelayed(this, PERIODIC_CAPTURE_INTERVAL_MS)
            }
        }
        backgroundHandler?.postDelayed(periodicCaptureRunnable!!, PERIODIC_CAPTURE_INTERVAL_MS)
    }

    private fun startWebSocketServer() {
        Log.d(TAG, "startWebSocketServer: starting CIO WebSocket server on port $SERVER_PORT")
        try {
            server = embeddedServer(CIO, port = SERVER_PORT) {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(30)
                    maxFrameSize = Long.MAX_VALUE // 큰 바이너리 프레임 허용
                }
                routing {
                    webSocket("/control") {
                        val remoteAddr = call.request.local.remoteAddress
                        Log.d(TAG, "New client connected: $remoteAddr")
                        activeSessions.add(this)

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    if (text == "CLIENT_READY") {
                                        // 클라이언트가 준비되었으므로 최신 비디오 프레임을 즉시 전송하여 화면을 띄움
                                        latestFrame?.let { frameData ->
                                            send(Frame.Binary(true, frameData))
                                            Log.d(TAG, "Sent initial frame on CLIENT_READY to $remoteAddr")
                                        }
                                        // 정지 화면에서도 즉각적인 화면 전송을 위해 강제 캡처 트리거
                                        triggerImmediateCapture()
                                    } else if (text.startsWith("NAV_")) {
                                        // 클라이언트의 가상 네비게이션 버튼 명령 처리
                                        handleNavCommand(text)
                                    } else {
                                        parseAndInjectTouch(text)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "WebSocket session error: ${e.message}")
                        } finally {
                            Log.d(TAG, "Client disconnected: $remoteAddr")
                            activeSessions.remove(this)
                        }
                    }
                }
            }.apply { start(wait = false) }
            Log.d(TAG, "startWebSocketServer: Server started successfully on port $SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "startWebSocketServer: FAILED to start server — ${e.message}", e)
        }
    }

    private fun broadcastFrame(bytes: ByteArray) {
        for (session in activeSessions) {
            serviceScope.launch {
                try {
                    if (session.isActive) {
                        session.send(Frame.Binary(true, bytes))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "broadcastFrame send error: ${e.message}")
                    activeSessions.remove(session)
                }
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
                    Log.w(TAG, "parseAndInjectTouch: AccessibilityService instance is null — touch ignored. 접근성 서비스를 활성화하세요.")
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
            val scale = 0.5f
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
                // 안드로이드 14 이상(Android 16 포함) 보안 가이드에 부합하도록
                // VirtualDisplay를 파괴하고 다시 만드는 대신 resize() 및 setSurface()를 사용합니다.
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

        try { server?.stop(1000, 2000) } catch (e: Exception) { Log.e(TAG, "Server stop error: ${e.message}") }
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { mediaProjection?.stop() } catch (e: Exception) {}
        try { imageReader?.close() } catch (e: Exception) {}
        try { backgroundThread?.quitSafely() } catch (e: Exception) {}
        latestFrame = null
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
