package com.example.cameramonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        isRunning = true

        if (!cameraStarted) {
            cameraStarted = true
            relay.start()
            startCamera()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        cameraStarted = false
        relay.stop()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
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

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "StreamService"
        const val ACTION_STOP = "com.example.cameramonitor.STOP"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
        private const val JPEG_QUALITY = 60
        private const val MIN_FRAME_INTERVAL_MS = 100L // ~10 fps, para caber bem no LTE

        @Volatile
        var isRunning = false
            private set
    }
}
