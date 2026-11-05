package br.dev.bizo.cameramonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
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

    // Atualiza a notificação quando a conexão ou o pedido de transmissão mudam.
    private val relayListener: () -> Unit = { updateNotification() }

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            // Parada pelo usuário: não volta sozinha depois de reiniciar o celular.
            setShouldRun(this, false)
            stopSelf()
            return START_NOT_STICKY
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
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        cameraStarted = false
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

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor) { image -> onFrame(image) } }

            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            // Erro grave (ex.: câmera bloqueada pelo sistema por ter sido aberta em segundo plano).
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
            // Sem visualizador, não gasta CPU nem dados convertendo quadros.
            if (!RelayClient.streaming || now - lastFrameAt < MIN_FRAME_INTERVAL_MS) return
            lastFrameAt = now
            relay.sendFrame(image.toJpeg())
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao converter quadro", e)
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toJpeg(): ByteArray {
        val plane = planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val raw = Bitmap.createBitmap(
            width + rowPadding / plane.pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        plane.buffer.rewind()
        raw.copyPixelsFromBuffer(plane.buffer)

        val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
        val frame = Bitmap.createBitmap(raw, 0, 0, width, height, matrix, true)

        val out = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(status))
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
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
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
        private const val RESUME_NOTIFICATION_ID = 2
        private const val JPEG_QUALITY = 60
        private const val MIN_FRAME_INTERVAL_MS = 100L // ~10 fps, para caber bem no LTE
        private const val PREFS = "stream"
        private const val KEY_SHOULD_RUN = "shouldRun"

        @Volatile
        var isRunning = false
            private set

        /** Se o usuário deixou a câmera ligada (para voltar a transmitir depois de reiniciar). */
        fun shouldRun(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, false)

        private fun setShouldRun(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOULD_RUN, value).apply()
        }
    }
}
