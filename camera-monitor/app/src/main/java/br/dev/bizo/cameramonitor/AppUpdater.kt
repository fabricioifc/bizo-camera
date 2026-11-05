package br.dev.bizo.cameramonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Atualização pelo próprio app: consulta a versão publicada no servidor, baixa o APK
 * (conferindo o sha256) e entrega ao instalador do Android, que pede a confirmação do usuário.
 * Depois de instalada, o app volta sozinho (BootReceiver, MY_PACKAGE_REPLACED).
 */
object AppUpdater {

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        class Available(val release: AppRelease) : State()
        class Downloading(val release: AppRelease, val percent: Int) : State()
        class Installing(val release: AppRelease) : State()
        class Failed(val message: String) : State()
    }

    private const val TAG = "AppUpdater"
    private const val PREFS = "updates"
    private const val KEY_NOTIFIED = "notifiedVersionCode"
    private const val KEY_CHECKED_AT = "checkedAt"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 10
    const val EXTRA_OPEN_UPDATE = "br.dev.bizo.cameramonitor.OPEN_UPDATE"

    private val io = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var state: State = State.Idle
        private set

    /** Ouvintes chamados (em qualquer thread) quando o estado muda. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun setState(newState: State) {
        state = newState
        for (listener in listeners) listener()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Procura atualização no servidor. Com [onlyIfStaleMs], só procura se a última busca
     * foi há mais tempo que isso. Com [notify], avisa por notificação (uma vez por versão).
     */
    fun check(context: Context, onlyIfStaleMs: Long = 0, notify: Boolean = false) {
        val app = context.applicationContext
        val busy = state is State.Checking || state is State.Downloading || state is State.Installing
        val now = System.currentTimeMillis()
        if (busy || now - prefs(app).getLong(KEY_CHECKED_AT, 0) < onlyIfStaleMs) return
        setState(State.Checking)
        io.execute {
            val release = RelayClient(app).latestRelease()
            prefs(app).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
            val update = release?.takeIf { it.isUpdateFor(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE) }
            setState(if (update != null) State.Available(update) else State.UpToDate)
            if (update != null && notify) notifyAvailable(app, update)
        }
    }

    /** O Android exige que o usuário libere este app a instalar outros apps (só uma vez). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Tela do Android para liberar a instalação de apps por este app. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Baixa e instala a versão disponível. O Android mostra a confirmação da instalação. */
    fun downloadAndInstall(context: Context) {
        val release = (state as? State.Available)?.release ?: return
        val app = context.applicationContext
        setState(State.Downloading(release, 0))
        io.execute {
            val dir = File(app.cacheDir, "updates")
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "app-${release.versionCode}.apk")
            val ok = RelayClient(app).downloadRelease(release, apk) { percent ->
                setState(State.Downloading(release, percent))
            }
            if (!ok) {
                setState(State.Failed(app.getString(R.string.update_download_failed)))
                return@execute
            }
            try {
                install(app, apk)
                setState(State.Installing(release))
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao iniciar a instalação", e)
                setState(State.Failed(app.getString(R.string.update_install_failed)))
            }
        }
    }

    // Entrega o APK ao instalador do Android (PackageInstaller). O resultado chega no InstallResultReceiver.
    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // O instalador preenche o resultado no Intent: no Android 12+ ele precisa ser mutável.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java), flags
            )
            session.commit(result.intentSender)
        }
    }

    /** Resultado da instalação (chamado pelo InstallResultReceiver). */
    fun onInstallResult(context: Context, status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS) return
        // Cancelada pelo usuário ou recusada (ex.: APK assinado com outra chave).
        Log.w(TAG, "Instalação não concluída: $status $message")
        val text = when {
            // Play Protect não liberou (aviso fechado ou análise cancelada).
            message?.contains("VERIFICATION", ignoreCase = true) == true ->
                context.getString(R.string.update_play_protect)
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> context.getString(R.string.update_cancelled)
            else -> context.getString(R.string.update_install_failed)
        }
        setState(State.Failed(text))
        // Permite avisar de novo e tentar outra vez.
        prefs(context).edit().remove(KEY_NOTIFIED).apply()
    }

    // Notificação "versão nova disponível", uma vez por versão. Tocar abre a aba Ajustes.
    private fun notifyAvailable(context: Context, release: AppRelease) {
        if (prefs(context).getInt(KEY_NOTIFIED, 0) == release.versionCode) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, release.versionName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
        prefs(context).edit().putInt(KEY_NOTIFIED, release.versionCode).apply()
    }
}

/** Recebe o resultado do instalador do Android e, se preciso, abre a tela de confirmação. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        AppUpdater.onInstallResult(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
