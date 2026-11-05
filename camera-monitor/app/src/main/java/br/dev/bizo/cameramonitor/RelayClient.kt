package br.dev.bizo.cameramonitor

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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cuida da conta e da conexão com o servidor de retransmissão.
 *
 * Fluxo: o usuário entra com a conta Google uma vez (e confirma o 2FA), o app
 * registra este aparelho na conta e guarda o segredo da câmera. Depois disso,
 * a câmera conecta sozinha sempre que o app estiver rodando.
 *
 * Todas as chamadas de rede bloqueiam: chame sempre fora da thread principal.
 */
class RelayClient(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Lido a cada chamada: trocar o servidor nas configurações vale na próxima requisição/conexão.
    private val baseUrl: String get() = ServerSettings.url(appContext)
    private val wsBaseUrl: String get() = baseUrl.replaceFirst("http", "ws")
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false

    val isSignedIn: Boolean get() = prefs.contains(KEY_SESSION)
    val isRegistered: Boolean get() = prefs.contains(KEY_ID) && prefs.contains(KEY_SECRET)
    val email: String? get() = prefs.getString(KEY_EMAIL, null)

    sealed class LoginResult {
        object Done : LoginResult()
        class NeedsCode(val mfaToken: String) : LoginResult()
        object NeedsSetup : LoginResult()
        object Failed : LoginResult()
    }

    // Motivo da última chamada de rede que falhou, para mostrar ao usuário.
    @Volatile var lastError: String? = null
        private set

    // Sessão da conta sem 2FA ativo: só serve para configurar o 2FA. Fica só na memória.
    private var setupToken: String? = null
    private var setupEmail: String? = null

    /** Entra com o ID token do Google (obtido pelo Credential Manager). */
    fun signInWithGoogle(idToken: String): LoginResult {
        val body = JSONObject().put("idToken", idToken).toString()
        val response = post("/api/auth/google/app", body, session = null) ?: return LoginResult.Failed
        if (response.optBoolean("mfaRequired")) return LoginResult.NeedsCode(response.getString("mfaToken"))
        if (response.optBoolean("pending")) {
            setupToken = response.getString("token")
            setupEmail = response.getString("email")
            return LoginResult.NeedsSetup
        }
        saveSession(response.getString("token"), response.getString("email"))
        return LoginResult.Done
    }

    /** Segundo passo do login: o código do app autenticador ou um código de recuperação. */
    fun verifyCode(mfaToken: String, code: String): Boolean {
        val body = JSONObject().put("mfaToken", mfaToken).put("code", code).toString()
        val response = post("/api/auth/mfa", body, session = null) ?: return false
        saveSession(response.getString("token"), response.getString("email"))
        return true
    }

    /** Gera o segredo do 2FA. Devolve o QR code (data URL PNG) e a chave em texto. */
    fun startTwoFactorSetup(): Pair<String, String>? {
        val token = setupToken ?: return null
        val response = post("/api/account/2fa/setup", "{}", token) ?: return null
        return response.getString("qr") to response.getString("secret")
    }

    /** Confirma o código do app e ativa o 2FA. Devolve os códigos de recuperação, ou null se o código estiver errado. */
    fun confirmTwoFactor(code: String): List<String>? {
        val token = setupToken ?: return null
        val body = JSONObject().put("code", code).toString()
        val response = post("/api/account/2fa/enable", body, token) ?: return null
        saveSession(token, setupEmail ?: return null)
        setupToken = null
        setupEmail = null
        val array = response.getJSONArray("recoveryCodes")
        return List(array.length()) { array.getString(it) }
    }

    private fun saveSession(token: String, owner: String) {
        val editor = prefs.edit()
            .putString(KEY_SESSION, token)
            .putString(KEY_EMAIL, owner)
        // Câmera registrada por outra conta (ou pela versão antiga do app) não pertence a esta.
        if (prefs.getString(KEY_OWNER, null) != owner) editor.remove(KEY_ID).remove(KEY_SECRET)
        editor.apply()
    }

    /** Remove a sessão deste aparelho. A câmera continua registrada na conta. */
    fun signOut() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    /** A sessão e as credenciais da câmera pertencem ao relay anterior. */
    fun resetForServerChange() {
        clearAccountCredentials()
        setupToken = null
        setupEmail = null
        socket?.cancel()
        socket = null
        stopped = true
        setState(isConnected = false, isStreaming = false)
    }

    private fun clearAccountCredentials() {
        prefs.edit()
            .remove(KEY_SESSION)
            .remove(KEY_EMAIL)
            .remove(KEY_OWNER)
            .remove(KEY_ID)
            .remove(KEY_SECRET)
            .apply()
    }

    /** Registra este aparelho na conta, se ainda não estiver registrado. */
    @Synchronized
    fun registerDevice(): Boolean {
        if (isRegistered) return true
        val session = prefs.getString(KEY_SESSION, null) ?: return false
        val body = JSONObject().put("name", Build.MODEL).toString()
        val registration = post("/api/devices/register", body, session)
        if (registration == null) {
            if (lastError?.startsWith("HTTP 401 ") == true) clearAccountCredentials()
            return false
        }
        prefs.edit()
            .putString(KEY_ID, registration.getString("deviceId"))
            .putString(KEY_SECRET, registration.getString("deviceSecret"))
            .putString(KEY_OWNER, prefs.getString(KEY_EMAIL, null))
            .apply()
        return true
    }

    fun start() {
        stopped = false
        drops = 0
        io.execute { attempt() }
    }

    /** Derruba a conexão atual; ela volta sozinha em alguns segundos (com o endereço atual do servidor). */
    fun reconnect() {
        socket?.cancel()
    }

    /** Versão do app publicada no servidor, ou null (sem versão, sem sessão ou sem conexão). Bloqueia. */
    fun latestRelease(): AppRelease? {
        val session = prefs.getString(KEY_SESSION, null) ?: return null
        val request = Request.Builder()
            .url("$baseUrl/api/app/latest")
            .header("Authorization", "Bearer $session")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body!!.string())
                AppRelease(
                    packageName = json.getString("packageName"),
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    size = json.getLong("size"),
                    sha256 = json.getString("sha256"),
                )
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName} em /api/app/latest: ${e.message}"
            null
        }
    }

    /**
     * Baixa o APK publicado em [target], conferindo tamanho e sha256 com [release].
     * [onProgress] recebe de 0 a 100. Devolve false se falhar ou se o arquivo não bater. Bloqueia.
     */
    fun downloadRelease(release: AppRelease, target: File, onProgress: (Int) -> Unit): Boolean {
        val session = prefs.getString(KEY_SESSION, null) ?: return false
        val request = Request.Builder()
            .url("$baseUrl/api/app/download")
            .header("Authorization", "Bearer $session")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastPercent = -1
                target.parentFile?.mkdirs()
                response.body!!.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            received += read
                            val percent = (received * 100 / maxOf(release.size, 1)).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val ok = received == release.size && hash == release.sha256
                if (!ok) target.delete()
                ok
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName} em /api/app/download: ${e.message}"
            target.delete()
            false
        }
    }

    /** Confere se [url] responde como um servidor do Monitor de Câmera. Bloqueia: chame fora da thread principal. */
    fun checkServer(url: String): Boolean {
        val request = Request.Builder().url("$url/api/config").get().build()
        return try {
            http.newCall(request).execute().use { response ->
                response.isSuccessful && JSONObject(response.body!!.string()).has("googleEnabled")
            }
        } catch (e: Exception) {
            false
        }
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

    /** Envia uma mensagem de controle (JSON) ao servidor, como a saúde do aparelho. */
    fun sendMessage(json: JSONObject) {
        socket?.send(json.toString())
    }

    private fun post(path: String, json: String, session: String?): JSONObject? {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .post(json.toRequestBody(JSON_TYPE))
        if (session != null) builder.header("Authorization", "Bearer $session")
        lastError = null
        return try {
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body!!.string()
                if (!response.isSuccessful) {
                    lastError = "HTTP ${response.code} $path: $body"
                    null
                } else {
                    JSONObject(body)
                }
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName} em $path: ${e.message}"
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
            // Mudança de configuração pedida pelo site (controle remoto); quem aplica é o StreamService.
            "configure" -> configureHandler?.invoke(text)
            // "Foto agora" pedida pelo site; quem tira a foto é o StreamService.
            "capture" -> captureHandler?.invoke(text)
        }
    }

    private fun setState(isConnected: Boolean, isStreaming: Boolean) {
        if (isConnected && !connected) connectedSince = System.currentTimeMillis()
        // Queda: estava conectada e perdeu a conexão sem o usuário ter parado a câmera.
        if (!isConnected && connected && !stopped) drops++
        if (!isConnected) connectedSince = 0L
        connected = isConnected
        streaming = isStreaming
        for (listener in listeners) listener()
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

        /** Momento em que a conexão atual começou (0 se desconectada). */
        @Volatile
        var connectedSince = 0L
            private set

        /** Quedas de conexão desde que a câmera foi iniciada. */
        @Volatile
        var drops = 0
            private set

        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        /** Recebe as mensagens "configure" do site (JSON em texto), em qualquer thread. */
        @Volatile
        var configureHandler: ((String) -> Unit)? = null

        /** Recebe os pedidos "capture" (foto agora) do site, em qualquer thread. */
        @Volatile
        var captureHandler: ((String) -> Unit)? = null

        /** Ouvintes chamados sempre que a conexão ou o pedido de transmissão muda (em qualquer thread). */
        fun addListener(listener: () -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: () -> Unit) {
            listeners.remove(listener)
        }
    }
}
