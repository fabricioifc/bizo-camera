package br.dev.bizo.cameramonitor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.File
import java.util.concurrent.Executors

/**
 * Vídeo curto do movimento: guarda o último segundo de imagem e, quando há movimento,
 * grava mais alguns segundos num MP4 (H.264), usando o encoder de vídeo do próprio celular.
 *
 * Os quadros chegam da análise da câmera ([onFrame], thread de análise); a codificação roda
 * numa thread própria. Ao terminar, [onClip] recebe o identificador e o arquivo MP4.
 */
class ClipRecorder(
    private val workDir: File,
    private val onClip: (clipId: String, mp4: ByteArray) -> Unit,
) {
    class Frame(val i420: ByteArray, val width: Int, val height: Int, val atMs: Long)

    private class Session(val clipId: String, val startedAtMs: Long, val firstFrameAtMs: Long, val file: File) {
        var encoder: Mp4Encoder? = null
        var failed = false
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val preroll = ArrayDeque<Frame>()
    private var lastFrameAt = 0L
    private var session: Session? = null

    val isRecording: Boolean get() = session != null

    /** Quadro da câmera (thread de análise). [rotationDegrees] é a rotação total da imagem. */
    fun onFrame(image: ImageProxy, rotationDegrees: Int, nowMs: Long) {
        if (nowMs - lastFrameAt < FRAME_INTERVAL_MS) return
        lastFrameAt = nowMs
        val frame = runCatching { image.toClipFrame(rotationDegrees, nowMs) }.getOrNull() ?: return
        val current = session
        if (current == null) {
            preroll.addLast(frame)
            while (preroll.isNotEmpty() && nowMs - preroll.first().atMs > PREROLL_MS) preroll.removeFirst()
            return
        }
        encode(current, frame)
        if (nowMs - current.startedAtMs >= AFTER_MOTION_MS) {
            session = null
            worker.execute { finish(current) }
        }
    }

    /** Movimento detectado: começa um vídeo com o último segundo guardado. Ignora se já estiver gravando. */
    fun start(clipId: String, nowMs: Long) {
        if (session != null) return
        val first = preroll.firstOrNull()?.atMs ?: nowMs
        val current = Session(clipId, nowMs, first, File(workDir, "clip-$clipId.mp4"))
        session = current
        for (frame in preroll) encode(current, frame)
        preroll.clear()
    }

    /** Descarta o que estiver em andamento (câmera parando). */
    fun cancel() {
        val current = session ?: return
        session = null
        worker.execute {
            runCatching { current.encoder?.release() }
            current.file.delete()
        }
    }

    private fun encode(current: Session, frame: Frame) {
        worker.execute {
            if (current.failed) return@execute
            try {
                val encoder = current.encoder ?: Mp4Encoder(frame.width, frame.height, current.file).also {
                    current.encoder = it
                }
                // Quadro com outro tamanho (o celular girou no meio do vídeo): fica de fora.
                if (frame.width != encoder.width || frame.height != encoder.height) return@execute
                encoder.encode(frame.i420, (frame.atMs - current.firstFrameAtMs) * 1000)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao gravar o vídeo do movimento", e)
                current.failed = true
            }
        }
    }

    private fun finish(current: Session) {
        try {
            val encoder = current.encoder ?: return
            encoder.finish()
            if (!current.failed && current.file.length() > 0) onClip(current.clipId, current.file.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao fechar o vídeo do movimento", e)
        } finally {
            runCatching { current.encoder?.release() }
            current.file.delete()
        }
    }

    fun shutdown() {
        cancel()
        worker.shutdown()
    }

    companion object {
        private const val TAG = "ClipRecorder"
        const val FPS = 8
        private const val FRAME_INTERVAL_MS = 1000L / FPS
        const val PREROLL_MS = 1_000L
        // Vídeo de 30 segundos no total: 1 antes e 29 depois do movimento.
        const val AFTER_MOTION_MS = 29_000L
        const val MAX_SIDE = 480
    }
}

/** Quadro RGBA da câmera → girado, reduzido (lado maior até 480, medidas múltiplas de 16) e em YUV 4:2:0. */
private fun ImageProxy.toClipFrame(rotationDegrees: Int, atMs: Long): ClipRecorder.Frame {
    val plane = planes[0]
    val raw = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
    plane.buffer.rewind()
    raw.copyPixelsFromBuffer(plane.buffer)
    // Tira as colunas de sobra do fim de cada linha (espaçamento do buffer da câmera).
    val visible = if (raw.width != width) Bitmap.createBitmap(raw, 0, 0, width, height) else raw

    val degrees = ((rotationDegrees % 360) + 360) % 360
    val sideways = degrees == 90 || degrees == 270
    val (outWidth, outHeight) = ClipFrames.size(
        if (sideways) height else width,
        if (sideways) width else height,
        ClipRecorder.MAX_SIDE,
    )
    // Gira em volta do centro e encaixa no tamanho final.
    val matrix = Matrix().apply {
        postTranslate(-width / 2f, -height / 2f)
        postRotate(degrees.toFloat())
        val scaleX = outWidth.toFloat() / (if (sideways) height else width)
        val scaleY = outHeight.toFloat() / (if (sideways) width else height)
        postScale(scaleX, scaleY)
        postTranslate(outWidth / 2f, outHeight / 2f)
    }
    val frame = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(frame).drawBitmap(visible, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    if (visible !== raw) visible.recycle()
    raw.recycle()

    val pixels = IntArray(outWidth * outHeight)
    frame.getPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)
    frame.recycle()
    return ClipRecorder.Frame(ClipFrames.argbToI420(pixels, outWidth, outHeight), outWidth, outHeight, atMs)
}

/**
 * Codificador H.264 → MP4. Recebe quadros YUV 4:2:0 (I420) com o horário de cada um,
 * então a velocidade do vídeo fica certa mesmo se algum quadro se perder.
 */
private class Mp4Encoder(val width: Int, val height: Int, file: File) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var lastPtsUs = -1L

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, ClipRecorder.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(i420: ByteArray, ptsUs: Long) {
        if (ptsUs <= lastPtsUs) return
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return // encoder ocupado: perde este quadro
        val image = codec.getInputImage(index) ?: error("encoder sem imagem de entrada")
        writeI420(image, i420)
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
        lastPtsUs = ptsUs
        drain(endOfStream = false)
    }

    fun finish() {
        val index = codec.dequeueInputBuffer(TIMEOUT_US * 10)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(endOfStream = true)
        if (muxing) muxer.stop()
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }

    private fun drain(endOfStream: Boolean) {
        var idleTries = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleTries > 50) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && muxing && !isConfig) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // Copia o I420 para os planos da imagem do encoder, respeitando o espaçamento de cada plano.
    private fun writeI420(image: android.media.Image, i420: ByteArray) {
        val planes = image.planes
        var offset = 0
        for ((planeIndex, plane) in planes.withIndex()) {
            val planeWidth = if (planeIndex == 0) width else width / 2
            val planeHeight = if (planeIndex == 0) height else height / 2
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            for (row in 0 until planeHeight) {
                val rowStart = row * rowStride
                for (col in 0 until planeWidth) {
                    buffer.put(rowStart + col * pixelStride, i420[offset + row * planeWidth + col])
                }
            }
            offset += planeWidth * planeHeight
        }
    }

    companion object {
        private const val BIT_RATE = 700_000
        private const val TIMEOUT_US = 10_000L
    }
}
