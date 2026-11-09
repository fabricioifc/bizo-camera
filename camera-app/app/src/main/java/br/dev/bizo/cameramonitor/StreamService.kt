package br.dev.bizo.cameramonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serviço em primeiro plano: mantém a câmera ativa e a conexão com o servidor
 * funcionando mesmo com a tela apagada.
 */
class StreamService : LifecycleService() {

    private lateinit var relay: RelayClient
    private var analysisExecutor: ExecutorService? = null
    private var cameraStarted = false
    private var lastFrameAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wasConnected = false

    // Qualidade escolhida pelo usuário e o limite imposto pelo calor; vale o mais econômico dos dois.
    @Volatile private var quality = Quality.MEDIUM
    @Volatile private var thermalLimit = ThermalLimit.NONE
    private var cameraState: LiveData<CameraState>? = null
    private var camera: Camera? = null
    // Grande-angular em uso (zoom abaixo de 1×), ou null com a câmera principal.
    private var wideCameraId: String? = null

    // Rotação extra escolhida pelo usuário (ajuste fino), aplicada a cada quadro enviado.
    @Volatile private var rotation = Rotation.R0

    // Posição do celular (em pé, deitado...), lida pelo sensor: a imagem enviada sai sempre
    // "em pé", como na prévia do app, mesmo com o celular preso de lado.
    private var analysis: ImageAnalysis? = null

    // "Foto agora": modo de foto da câmera (qualidade alta). Se o celular não aceitar foto e
    // análise juntas, a foto sai do próximo quadro do vídeo, com qualidade alta.
    private var imageCapture: ImageCapture? = null
    @Volatile private var frameCaptureRequest: String? = null
    private val photoExecutor = Executors.newSingleThreadExecutor()
    private var deviceRotation = DeviceOrientation.ROTATION_0
    @Volatile private var resetMotion = false
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val next = DeviceOrientation.rotationFor(orientation, deviceRotation)
                if (next == deviceRotation) return
                deviceRotation = next
                analysis?.targetRotation = next
                imageCapture?.targetRotation = next
                // Enquadramento novo: a comparação de movimento recomeça do zero (na thread dos quadros).
                resetMotion = true
            }
        }
    }

    // Detecção de movimento (roda na thread de análise dos quadros).
    private val motionDetector = MotionDetector()
    @Volatile private var motionEnabled = true
    @Volatile private var videoEnabled = true

    // Vídeo curto do movimento (MP4), enviado ao servidor depois da foto do movimento.
    private val clipRecorder by lazy {
        ClipRecorder(cacheDir) { clipId, mp4 ->
            if (RelayClient.connected) {
                val video = Base64.encodeToString(mp4, Base64.NO_WRAP)
                relay.sendMessage(JSONObject().put("type", "clip").put("clipId", clipId).put("video", video))
            }
        }
    }
    @Volatile private var sensitivity = Sensitivity.MEDIUM
    private var lastMotionCheckAt = 0L
    private var lastMotionSentAt = 0L
    // Logo depois de ligar a câmera a exposição ainda está se ajustando (principalmente no modo
    // noturno): a imagem clareando não conta como movimento.
    @Volatile private var motionGraceUntil = 0L

    // Foco, mostrado no site: "auto", "focusing", "locked", "far", "failed" ou "unavailable" (lente sem ajuste).
    private var focusState = FOCUS_AUTO

    // Configuração alterada no app ou pelo site. Lente ou qualidade: troca a câmera sem parar a transmissão.
    // Movimento, rotação e lanterna: valem na hora. Em todos os casos, o site recebe o estado novo.
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (CameraSettings.affectsCamera(key)) {
            handler.post { if (cameraStarted) startCamera() }
        } else if (key == CameraSettings.KEY_ZOOM) {
            handler.post { if (cameraStarted) applyZoom() }
        } else {
            loadMotionSettings()
            handler.post {
                applyTorch()
                reportSettings()
            }
        }
    }

    // Atualiza a notificação quando a conexão muda e envia a saúde logo após conectar.
    private val relayListener: () -> Unit = {
        val connected = RelayClient.connected
        if (connected && !wasConnected) handler.post {
            reportHealth()
            reportSettings()
        }
        wasConnected = connected
        updateNotification()
    }

    // Com a câmera ligada (o app quase nunca fica aberto), procura versão nova a cada 6 horas
    // e avisa por notificação.
    private val updateTicker = object : Runnable {
        override fun run() {
            AppUpdater.check(this@StreamService, onlyIfStaleMs = UPDATE_CHECK_MS, notify = true)
            handler.postDelayed(this, UPDATE_CHECK_MS)
        }
    }

    private val healthTicker = object : Runnable {
        override fun run() {
            reportHealth()
            handler.postDelayed(this, HEALTH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        relay = RelayClient(this)
        RelayClient.addListener(relayListener)
        CameraSettings.prefs(this).registerOnSharedPreferenceChangeListener(settingsListener)
        RelayClient.configureHandler = { text -> handler.post { applyRemoteSettings(text) } }
        RelayClient.captureHandler = { text ->
            runCatching { JSONObject(text).getString("requestId") }.getOrNull()?.let { id -> handler.post { capturePhoto(id) } }
        }
        RelayClient.focusHandler = { text ->
            when (runCatching { JSONObject(text).getString("mode") }.getOrNull()) {
                "lock" -> handler.post { focusNow() }
                "far" -> handler.post { focusFar() }
                "auto" -> handler.post { focusAutomatic() }
            }
        }
        loadMotionSettings()
    }

    private fun loadMotionSettings() {
        motionEnabled = CameraSettings.motionEnabled(this)
        videoEnabled = CameraSettings.motionVideo(this)
        sensitivity = CameraSettings.sensitivity(this)
        rotation = CameraSettings.rotation(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            // Parada pelo usuário: não volta sozinha depois de reiniciar o celular.
            setShouldRun(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT) {
            // Servidor trocado nas configurações: reconecta no endereço novo, sem parar a câmera.
            if (cameraStarted) relay.reconnect() else stopSelf()
            return START_STICKY
        }

        setShouldRun(this, true)
        if (!startForegroundCompat()) {
            // Sem permissão de câmera em segundo plano (ex.: iniciado no boot): pede ao usuário para abrir o app.
            showResumeNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        getSystemService(NotificationManager::class.java).cancel(RESUME_NOTIFICATION_ID)

        if (!cameraStarted) {
            cameraStarted = true
            acquireLocks()
            relay.start()
            if (orientationListener.canDetectOrientation()) orientationListener.enable()
            startCamera()
            healthTicker.run()
            handler.postDelayed(updateTicker, UPDATE_FIRST_CHECK_MS)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        isThrottled = false
        cameraStarted = false
        handler.removeCallbacks(healthTicker)
        orientationListener.disable()
        analysis = null
        handler.removeCallbacks(updateTicker)
        RelayClient.configureHandler = null
        RelayClient.captureHandler = null
        RelayClient.focusHandler = null
        imageCapture = null
        photoExecutor.shutdown()
        clipRecorder.shutdown()
        camera = null
        CameraSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        RelayClient.removeListener(relayListener)
        relay.stop()
        releaseLocks()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para iniciar a câmera em segundo plano", e)
            false
        }
    }

    // CPU e Wi-Fi ativos com a tela apagada; sem isso, alguns aparelhos derrubam a rede.
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraMonitor:stream")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CameraMonitor:stream")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    // Liga (ou religa) a câmera com a lente e a qualidade salvas no app.
    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!cameraStarted) return@addListener
            val provider = providerFuture.get()
            val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
            quality = CameraSettings.quality(this)

            // Também libera a prévia do app, se estiver aberta: a câmera só pode ter um dono.
            provider.unbindAll()
            // Lente e zoom: abaixo de 1×, a grande-angular (se o celular deixar usar).
            val choice = CameraZoom.choose(provider, CameraSettings.lens(this), CameraSettings.zoom(this))
            CameraSettings.setZoomRange(this, choice.minZoom, choice.maxZoom)
            wideCameraId = choice.wideCameraId
            val selector = choice.selector
            val night = CameraSettings.night(this)

            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(quality.resolution)
                .setTargetRotation(deviceRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // Modo noturno: deixa os quadros por segundo caírem, para cada um ficar exposto por mais tempo.
            if (night) nightFpsRange(provider, selector)?.let { range ->
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
            val analysis = analysisBuilder.build()
                .also { it.setAnalyzer(executor) { image -> onFrame(image) } }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(PHOTO_RESOLUTION)
                .setTargetRotation(deviceRotation)
                .build()
            val camera = try {
                provider.bindToLifecycle(this, selector, analysis, capture).also { imageCapture = capture }
            } catch (e: Exception) {
                Log.w(TAG, "Câmera sem modo de foto junto com a análise; a foto sai do vídeo", e)
                imageCapture = null
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            }
            this.camera = camera
            this.analysis = analysis
            // Câmera religada: o foco fixado no centro se perde; o foco longe fica salvo e volta.
            focusState = if (CameraSettings.focusFar(this) && supportsFarFocus(camera)) {
                applyFarFocus(camera)
                FOCUS_FAR
            } else {
                FOCUS_AUTO
            }
            camera.cameraControl.setZoomRatio(choice.ratio)
            applyExposure(camera, night)
            // Câmera ou enquadramento novos: a próxima comparação de movimento recomeça do zero.
            motionDetector.reset()
            motionGraceUntil = System.currentTimeMillis() + EXPOSURE_SETTLE_MS
            // A lanterna apaga ao trocar de câmera; acende de novo se estiver pedida.
            applyTorch()
            reportSettings()
            // Erro grave (ex.: câmera bloqueada pelo sistema por ter sido aberta em segundo plano).
            cameraState?.removeObservers(this)
            cameraState = camera.cameraInfo.cameraState
            camera.cameraInfo.cameraState.observe(this) { state ->
                if (state.error?.type == CameraState.ErrorType.CRITICAL) {
                    Log.w(TAG, "Câmera indisponível: código ${state.error?.code}")
                    showResumeNotification()
                    stopSelf()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (resetMotion) {
                resetMotion = false
                motionDetector.reset()
            }
            // "Foto agora" sem o modo de foto: usa este quadro, com qualidade alta.
            frameCaptureRequest?.let { requestId ->
                frameCaptureRequest = null
                sendPhoto(requestId, image.toJpeg(PHOTO_JPEG_QUALITY, rotation.degrees))
            }
            // Vídeo do movimento: guarda o último segundo e grava enquanto houver um vídeo em andamento.
            if (motionEnabled && videoEnabled && RelayClient.connected) {
                clipRecorder.onFrame(image, totalRotation(image.imageInfo.rotationDegrees, rotation.degrees), now)
            }
            // Movimento: confere duas vezes por segundo, mesmo sem ninguém assistindo.
            if (motionEnabled && RelayClient.connected && now - lastMotionCheckAt >= MOTION_CHECK_MS) {
                lastMotionCheckAt = now
                // Exposição se ajustando: só guarda a imagem como referência.
                if (now < motionGraceUntil) motionDetector.reset()
                val moved = motionDetector.update(image.lumaGrid(GRID_COLUMNS, GRID_ROWS), sensitivity.changedFraction)
                if (moved && now - lastMotionSentAt >= MOTION_COOLDOWN_MS) {
                    lastMotionSentAt = now
                    sendMotion(image)
                }
            }
            // Sem visualizador, não gasta CPU nem dados convertendo quadros.
            val interval = maxOf(quality.frameIntervalMs, thermalLimit.minFrameIntervalMs)
            if (!RelayClient.streaming || now - lastFrameAt < interval) return
            lastFrameAt = now
            relay.sendFrame(image.toJpeg(jpegQuality(), rotation.degrees))
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao converter quadro", e)
        } finally {
            image.close()
        }
    }

    private fun jpegQuality() = minOf(quality.jpegQuality, thermalLimit.maxJpegQuality)

    // Avisa o servidor com uma foto do momento; ele guarda o evento e manda a notificação.
    private fun sendMotion(image: ImageProxy) {
        val snapshot = Base64.encodeToString(image.toJpeg(jpegQuality(), rotation.degrees), Base64.NO_WRAP)
        val message = JSONObject().put("type", "motion").put("snapshot", snapshot)
        // Com um vídeo ainda em gravação, este movimento fica só com a foto.
        if (videoEnabled && !clipRecorder.isRecording) {
            // O vídeo chega depois; o servidor liga ao evento por este identificador.
            val clipId = (1..16).map { "0123456789abcdef".random() }.joinToString("")
            clipRecorder.start(clipId, System.currentTimeMillis())
            message.put("clipId", clipId)
        }
        relay.sendMessage(message)
    }

    // "Foto agora": foto em qualidade alta, já na posição certa, enviada ao site pelo servidor.
    private fun capturePhoto(requestId: String) {
        val capture = imageCapture
        if (capture == null) {
            frameCaptureRequest = requestId
            return
        }
        capture.takePicture(photoExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val degrees = image.imageInfo.rotationDegrees + rotation.degrees
                    sendPhoto(requestId, rotateAndScaleJpeg(image.jpegBytes(), degrees, PHOTO_MAX_SIDE, PHOTO_JPEG_QUALITY))
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao preparar a foto", e)
                    frameCaptureRequest = requestId
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "Falha ao tirar a foto; usando o vídeo", exception)
                frameCaptureRequest = requestId
            }
        })
    }

    private fun sendPhoto(requestId: String, jpeg: ByteArray) {
        val snapshot = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        relay.sendMessage(JSONObject().put("type", "photo").put("requestId", requestId).put("snapshot", snapshot))
    }

    // Zoom mudou: na mesma câmera, só ajusta; se precisar trocar (principal ↔ grande-angular), religa.
    private fun applyZoom() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val choice = CameraZoom.choose(providerFuture.get(), CameraSettings.lens(this), CameraSettings.zoom(this))
            val current = camera
            if (current == null || choice.wideCameraId != wideCameraId) {
                startCamera()
            } else {
                current.cameraControl.setZoomRatio(choice.ratio)
                reportSettings()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // "Focar agora": foca no centro da imagem e mantém esse foco, sem o autofoco ficar procurando.
    private fun focusNow() {
        val current = camera ?: return
        if (focusState == FOCUS_FOCUSING) return
        clearFarFocus(current)
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
        if (!current.cameraInfo.isFocusMeteringSupported(action)) {
            setFocusState(FOCUS_UNAVAILABLE)
            return
        }
        setFocusState(FOCUS_FOCUSING)
        // A imagem muda durante o foco: isso não conta como movimento.
        motionGraceUntil = System.currentTimeMillis() + FOCUS_MAX_MS
        val target = current
        val result = target.cameraControl.startFocusAndMetering(action)
        result.addListener({
            if (camera !== target) return@addListener
            val focused = runCatching { result.get().isFocusSuccessful }.getOrDefault(false)
            motionGraceUntil = System.currentTimeMillis() + EXPOSURE_SETTLE_MS
            // Sem sucesso, não deixa um foco ruim preso: volta ao automático.
            if (!focused) target.cameraControl.cancelFocusAndMetering()
            setFocusState(if (focused) FOCUS_LOCKED else FOCUS_FAILED)
        }, ContextCompat.getMainExecutor(this))
    }

    // "Focar longe": deixa a lente no infinito, sem autofoco. É o que funciona à noite e atrás de
    // vidro, onde o autofoco não enxerga contraste ou mira o reflexo. Fica salvo no aparelho.
    private fun focusFar() {
        val current = camera ?: return
        if (focusState == FOCUS_FOCUSING) return
        if (!supportsFarFocus(current)) {
            setFocusState(FOCUS_UNAVAILABLE)
            return
        }
        current.cameraControl.cancelFocusAndMetering()
        CameraSettings.setFocusFar(this, true)
        applyFarFocus(current)
        setFocusState(FOCUS_FAR)
    }

    private fun focusAutomatic() {
        val current = camera ?: return
        if (focusState == FOCUS_FOCUSING) return
        current.cameraControl.cancelFocusAndMetering()
        clearFarFocus(current)
        setFocusState(FOCUS_AUTO)
    }

    // A lente precisa ter ajuste de foco (distância mínima acima de 0) e aceitar autofoco desligado.
    @OptIn(ExperimentalCamera2Interop::class)
    private fun supportsFarFocus(target: Camera): Boolean {
        val info = Camera2CameraInfo.from(target.cameraInfo)
        val minimum = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val modes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        return minimum > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in modes
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyFarFocus(target: Camera) {
        motionGraceUntil = System.currentTimeMillis() + EXPOSURE_SETTLE_MS
        // Na Camera2, a distância é medida em dioptrias: 0 é o infinito.
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            .build()
        Camera2CameraControl.from(target.cameraControl).setCaptureRequestOptions(options)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun clearFarFocus(target: Camera) {
        CameraSettings.setFocusFar(this, false)
        Camera2CameraControl.from(target.cameraControl).clearCaptureRequestOptions()
    }

    private fun setFocusState(state: String) {
        focusState = state
        reportSettings()
    }

    // Faixa de quadros por segundo do escuro, entre as que a câmera aceita.
    @OptIn(ExperimentalCamera2Interop::class)
    private fun nightFpsRange(provider: ProcessCameraProvider, selector: CameraSelector): Range<Int>? {
        val info = runCatching { selector.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull() ?: return null
        val ranges = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower to it.upper } ?: return null
        return NightMode.fpsRange(ranges)?.let { (min, max) -> Range(min, max) }
    }

    // Modo noturno: compensa a exposição para cima; fora dele, volta ao normal.
    private fun applyExposure(camera: Camera, night: Boolean) {
        val state = camera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val index = if (night) NightMode.exposureIndex(range.lower, range.upper) else 0
        camera.cameraControl.setExposureCompensationIndex(index)
    }

    // Lanterna: acende só se pedida e se a lente atual tiver flash (a frontal normalmente não tem).
    private fun applyTorch() {
        val current = camera ?: return
        if (!current.cameraInfo.hasFlashUnit()) return
        current.cameraControl.enableTorch(CameraSettings.torch(this))
    }

    // Controle remoto: o site pediu mudanças. Valores desconhecidos são ignorados.
    // Salvar dispara o settingsListener, que aplica e responde ao site com o estado novo.
    private fun applyRemoteSettings(text: String) {
        val settings = runCatching { JSONObject(text).getJSONObject("settings") }.getOrNull() ?: return
        fun value(key: String): Any? = if (settings.has(key)) settings.get(key) else null
        RemoteSettings.parseLens(value("lens"))?.let { CameraSettings.setLens(this, it) }
        RemoteSettings.parseQuality(value("quality"))?.let { CameraSettings.setQuality(this, it) }
        RemoteSettings.parseRotation(value("rotation"))?.let { CameraSettings.setRotation(this, it) }
        RemoteSettings.parseFlag(value("motion"))?.let { CameraSettings.setMotionEnabled(this, it) }
        RemoteSettings.parseSensitivity(value("sensitivity"))?.let { CameraSettings.setSensitivity(this, it) }
        RemoteSettings.parseFlag(value("torch"))?.let { CameraSettings.setTorch(this, it) }
        RemoteSettings.parseFlag(value("video"))?.let { CameraSettings.setMotionVideo(this, it) }
        RemoteSettings.parseFlag(value("night"))?.let { CameraSettings.setNight(this, it) }
        RemoteSettings.parseZoom(value("zoom"))?.let { CameraSettings.setZoom(this, it) }
        // Sem mudança de fato (ex.: valor igual ao atual), responde mesmo assim para o site se acertar.
        reportSettings()
    }

    // Estado atual das configurações, para o site mostrar nos controles.
    private fun reportSettings() {
        if (!RelayClient.connected) return
        val settings = JSONObject()
            .put("lens", RemoteSettings.name(CameraSettings.lens(this)))
            .put("quality", RemoteSettings.name(CameraSettings.quality(this)))
            .put("rotation", CameraSettings.rotation(this).degrees)
            .put("motion", CameraSettings.motionEnabled(this))
            .put("sensitivity", RemoteSettings.name(CameraSettings.sensitivity(this)))
            .put("torch", CameraSettings.torch(this))
            .put("video", CameraSettings.motionVideo(this))
            .put("night", CameraSettings.night(this))
            .put("focus", focusState)
            .put("zoom", CameraSettings.zoom(this).toDouble())
            .put("zoomMin", CameraSettings.zoomRange(this).first.toDouble())
            .put("zoomMax", CameraSettings.zoomRange(this).second.toDouble())
            .put("torchAvailable", camera?.cameraInfo?.hasFlashUnit() ?: false)
        relay.sendMessage(JSONObject().put("type", "settings").put("settings", settings))
    }

    // Lê bateria e temperatura, ajusta a qualidade conforme o calor e avisa o servidor.
    private fun reportHealth() {
        val health = DeviceHealth.read(this) ?: return
        thermalLimit = ThermalLimit.forTemperature(health.temperatureCelsius)
        isThrottled = thermalLimit.throttled
        if (RelayClient.connected) relay.sendMessage(health.toMessage(isThrottled))
    }

    private fun updateNotification() {
        if (!isRunning) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val status = when {
            !RelayClient.connected -> R.string.notif_connecting
            RelayClient.streaming -> R.string.notif_live
            else -> R.string.notif_ready
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(status))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // Com PIN, só dá para parar pelo app, que pede o PIN.
        if (!PinLock.isSet(this)) builder.addAction(0, getString(R.string.notif_stop), stop)
        return builder.build()
    }

    // Aviso para retomar: ao abrir o app, a câmera volta a transmitir sozinha.
    private fun showResumeNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_resume_title))
            .setContentText(getString(R.string.notif_resume_text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESUME_NOTIFICATION_ID, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val TAG = "StreamService"
        const val ACTION_STOP = "br.dev.bizo.cameramonitor.STOP"
        const val ACTION_RECONNECT = "br.dev.bizo.cameramonitor.RECONNECT"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
        private const val RESUME_NOTIFICATION_ID = 2
        private const val PREFS = "stream"
        private const val KEY_SHOULD_RUN = "shouldRun"
        private const val HEALTH_INTERVAL_MS = 60_000L

        // "Foto agora": até 1920 px no lado maior, JPEG com qualidade 85.
        private val PHOTO_RESOLUTION = android.util.Size(1920, 1440)
        private const val PHOTO_MAX_SIDE = 1920
        private const val PHOTO_JPEG_QUALITY = 85
        private const val UPDATE_FIRST_CHECK_MS = 2 * 60_000L
        private const val UPDATE_CHECK_MS = 6 * 60 * 60_000L

        // Detecção de movimento: grade de 32x24 pontos conferida duas vezes por segundo e
        // intervalo mínimo entre alertas (igual ao do servidor).
        private const val MOTION_CHECK_MS = 500L
        private const val MOTION_COOLDOWN_MS = 30_000L
        private const val GRID_COLUMNS = 32
        private const val GRID_ROWS = 24
        private const val EXPOSURE_SETTLE_MS = 3_000L

        // Foco: estados informados ao site e limite da operação.
        private const val FOCUS_AUTO = "auto"
        private const val FOCUS_FOCUSING = "focusing"
        private const val FOCUS_LOCKED = "locked"
        private const val FOCUS_FAR = "far"
        private const val FOCUS_FAILED = "failed"
        private const val FOCUS_UNAVAILABLE = "unavailable"
        private const val FOCUS_MAX_MS = 8_000L

        @Volatile
        var isRunning = false
            private set

        /** Qualidade reduzida porque o celular está quente. */
        @Volatile
        var isThrottled = false
            private set

        /** Se o usuário deixou a câmera ligada (para voltar a transmitir depois de reiniciar). */
        fun shouldRun(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, false)

        private fun setShouldRun(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOULD_RUN, value).apply()
        }
    }
}
