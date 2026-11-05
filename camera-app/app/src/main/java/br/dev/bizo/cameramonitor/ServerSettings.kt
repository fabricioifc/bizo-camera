package br.dev.bizo.cameramonitor

import android.content.Context
import java.net.URI

/**
 * Endereço do servidor, salvo no aparelho. Sem endereço salvo, usa o padrão do build
 * (`relayUrl` no Gradle). Assim, trocar de domínio não exige um APK novo.
 */
object ServerSettings {
    private const val PREFS = "server"
    private const val KEY_URL = "url"

    val defaultUrl: String get() = BuildConfig.RELAY_URL.trimEnd('/')

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null) ?: defaultUrl

    /** Salva o endereço; null ou o próprio padrão voltam a usar o padrão do build. */
    fun setUrl(context: Context, url: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (url == null || url == defaultUrl) editor.remove(KEY_URL) else editor.putString(KEY_URL, url)
        editor.apply()
    }

    /**
     * Normaliza o endereço para `https://host[:porta]`, ou devolve null se não for válido.
     * Em builds que permitem HTTP, endereços sem esquema usam `http://` para facilitar testes na rede local.
     */
    fun normalize(input: String, allowHttp: Boolean = false): String? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = if (allowHttp) "http://$text" else "https://$text"
        text = text.trimEnd('/')
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && !(allowHttp && scheme == "http")) return null
        val host = uri.host?.lowercase() ?: return null
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) {
            return null
        }
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }
}
