package br.dev.bizo.cameramonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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

    // Rotação extra escolhida pelo usuário (celular de lado), aplicada a cada quadro enviado.
    @Volatile private var rotation = Rotation.R0

    // Detecção de movimento (roda na thread de análise dos quadros).
    private val motionDetector = MotionDetector()
    @Volatile private var motionEnabled = true
    @Volatile private var sensitivity = Sensitivity.MEDIUM
    private var lastMotionCheckAt = 0L
    private var lastMotionSentAt = 0L

    // Configuração alterada no app ou pelo site. Lente ou qualidade: troca a câmera sem parar a transmissão.
    // Movimento, rotação e lanterna: valem na hora. Em todos os casos, o site recebe o estado novo.
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (CameraSettings.affectsCamera(key)) {
            handler.post { if (cameraStarted) startCamera() }
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
        loadMotionSettings()
    }

    private fun loadMotionSettings() {
        motionEnabled = CameraSettings.motionEnabled(this)
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
        handler.removeCallbacks(updateTicker)
        RelayClient.configureHandler = null
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
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!cameraStarted) return@addListener
            val provider = providerFuture.get()
            val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
            quality = CameraSettings.quality(this)

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(quality.resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor) { image -> onFrame(image) } }

            // Também libera a prévia do app, se estiver aberta: a câmera só pode ter um dono.
            provider.unbindAll()
            val lens = CameraSettings.lens(this).selector
            val available = runCatching { provider.hasCamera(lens) }.getOrDefault(false)
            val selector = if (available) lens else CameraSelector.DEFAULT_BACK_CAMERA
            val camera = provider.bindToLifecycle(this, selector, analysis)
            this.camera = camera
            // Câmera ou enquadramento novos: a próxima comparação de movimento recomeça do zero.
            motionDetector.reset()
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
            // Movimento: confere duas vezes por segundo, mesmo sem ninguém assistindo.
            if (motionEnabled && RelayClient.connected && now - lastMotionCheckAt >= MOTION_CHECK_MS) {
                lastMotionCheckAt = now
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
        relay.sendMessage(JSONObject().put("type", "motion").put("snapshot", snapshot))
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
        private const val UPDATE_FIRST_CHECK_MS = 2 * 60_000L
        private const val UPDATE_CHECK_MS = 6 * 60 * 60_000L

        // Detecção de movimento: grade de 32x24 pontos conferida duas vezes por segundo e
        // intervalo mínimo entre alertas (igual ao do servidor).
        private const val MOTION_CHECK_MS = 500L
        private const val MOTION_COOLDOWN_MS = 30_000L
        private const val GRID_COLUMNS = 32
        private const val GRID_ROWS = 24

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
