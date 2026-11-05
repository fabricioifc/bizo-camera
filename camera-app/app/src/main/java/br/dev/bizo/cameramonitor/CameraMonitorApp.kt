package br.dev.bizo.cameramonitor

import android.app.Application

/** Aplica o tema escolhido em Ajustes antes de qualquer tela abrir. */
class CameraMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppTheme.apply(this)
    }
}
