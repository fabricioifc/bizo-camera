package br.dev.bizo.cameramonitor

/** Versão do app publicada no servidor para atualização. */
data class AppRelease(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    val sha256: String,
) {
    /** É uma atualização para este app: mesmo pacote e versão maior que a instalada. */
    fun isUpdateFor(installedPackage: String, installedVersionCode: Int): Boolean =
        packageName == installedPackage && versionCode > installedVersionCode
}
