package com.example.cameramonitor

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cuida da conta e da conexão com o servidor de retransmissão.
 *
 * Fluxo: o usuário entra com e-mail e senha uma vez, o app registra este
 * aparelho na conta e guarda o segredo da câmera. Depois disso, a câmera
 * conecta sozinha sempre que o app estiver rodando.
 *
 * Todas as chamadas de rede bloqueiam: chame sempre fora da thread principal.
 */
class RelayClient(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val baseUrl = BuildConfig.RELAY_URL.trimEnd('/')
    private val wsBaseUrl = baseUrl.replaceFirst("http", "ws")
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false

    val isSignedIn: Boolean get() = prefs.contains(KEY_SESSION)
    val isRegistered: Boolean get() = prefs.contains(KEY_ID) && prefs.contains(KEY_SECRET)

    /** Página de login com o Google. Abre no navegador; o retorno volta ao app pelo link monitor://. */
    val loginUrl: String get() = "$baseUrl/api/auth/google/start?app=1"

    /** Troca o código de uso único (recebido pelo link monitor://) por uma sessão deste aparelho. */
    fun exchangeCode(code: String): Boolean {
        val body = JSONObject().put("code", code).toString()
        val response = post("/api/auth/app-token", body, session = null) ?: return false
        saveSession(response)
        return true
    }

    private fun saveSession(login: JSONObject) {
        val owner = login.getString("email")
        val editor = prefs.edit()
            .putString(KEY_SESSION, login.getString("token"))
            .putString(KEY_EMAIL, owner)
        // Câmera registrada por outra conta (ou pela versão antiga do app) não pertence a esta.
        if (prefs.getString(KEY_OWNER, null) != owner) editor.remove(KEY_ID).remove(KEY_SECRET)
        editor.apply()
    }

    /** Remove a sessão deste aparelho. A câmera continua registrada na conta. */
    fun signOut() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    /** Registra este aparelho na conta, se ainda não estiver registrado. */
    @Synchronized
    fun registerDevice(): Boolean {
        if (isRegistered) return true
        val session = prefs.getString(KEY_SESSION, null) ?: return false
        val body = JSONObject().put("name", Build.MODEL).toString()
        val registration = post("/api/devices/register", body, session) ?: return false
        prefs.edit()
            .putString(KEY_ID, registration.getString("deviceId"))
            .putString(KEY_SECRET, registration.getString("deviceSecret"))
            .putString(KEY_OWNER, prefs.getString(KEY_EMAIL, null))
            .apply()
        return true
    }

    fun start() {
        stopped = false
        io.execute { attempt() }
    }

    fun stop() {
        stopped = true
        socket?.close(1000, "parado")
        socket = null
        setState(isConnected = false, isStreaming = false)
    }

    /** Envia um quadro JPEG. Chamado só quando o servidor pediu transmissão. */
    fun sendFrame(jpeg: ByteArray) {
        socket?.send(jpeg.toByteString())
    }

    private fun post(path: String, json: String, session: String?): JSONObject? {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .post(json.toRequestBody(JSON_TYPE))
        if (session != null) builder.header("Authorization", "Bearer $session")
        return try {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) null else JSONObject(response.body!!.string())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun attempt() {
        if (stopped || !isRegistered) return
        connect()
    }

    private fun scheduleRetry() {
        io.execute {
            Thread.sleep(RETRY_MS)
            attempt()
        }
    }

    private fun connect() {
        val id = prefs.getString(KEY_ID, null) ?: return
        val secret = prefs.getString(KEY_SECRET, null) ?: return

        val request = Request.Builder()
            .url("$wsBaseUrl/ws/device?id=$id")
            .header("Authorization", "Bearer $secret")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                setState(isConnected = true, isStreaming = false)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleControl(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnected(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 401: o servidor não conhece mais este aparelho. Precisa registrar de novo.
                if (response?.code == 401) {
                    prefs.edit().remove(KEY_ID).remove(KEY_SECRET).apply()
                    stopped = true
                }
                disconnected(webSocket)
            }
        })
    }

    private fun disconnected(ws: WebSocket) {
        if (ws != socket) return
        socket = null
        setState(isConnected = false, isStreaming = false)
        if (!stopped) scheduleRetry()
    }

    private fun handleControl(text: String) {
        val type = runCatching { JSONObject(text).getString("type") }.getOrNull() ?: return
        when (type) {
            "start" -> setState(isConnected = connected, isStreaming = true)
            "stop" -> setState(isConnected = connected, isStreaming = false)
        }
    }

    private fun setState(isConnected: Boolean, isStreaming: Boolean) {
        connected = isConnected
        streaming = isStreaming
        onChange?.invoke()
    }

    companion object {
        private const val PREFS = "relay"
        private const val KEY_SESSION = "session"
        private const val KEY_EMAIL = "email"
        private const val KEY_OWNER = "owner"
        private const val KEY_ID = "deviceId"
        private const val KEY_SECRET = "deviceSecret"
        private const val RETRY_MS = 5_000L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        var connected = false
            private set

        @Volatile
        var streaming = false
            private set

        /** Chamado sempre que a conexão ou o pedido de transmissão muda. */
        @Volatile
        var onChange: (() -> Unit)? = null
    }
}
