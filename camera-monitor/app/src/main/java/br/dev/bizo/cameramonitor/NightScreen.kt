package br.dev.bizo.cameramonitor

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Tela apagada do modo noturno: com a câmera transmitindo e o app aberto, depois de alguns
 * segundos sem toque a tela fica preta, com o brilho no mínimo e sem as barras do sistema.
 * Economiza bateria (na tela AMOLED, preto quase não gasta) e não chama atenção no escuro.
 * Um toque mostra o app de novo. O tempo normal de apagar a tela do Android continua valendo.
 */
class NightScreen(private val activity: Activity, private val enabled: () -> Boolean) {

    private val root = activity.window.decorView as ViewGroup
    private var cover: View? = null
    private var resumed = false

    private val dim = Runnable { show() }

    /** Modo noturno ou câmera mudaram: começa a contar para apagar, ou mostra o app de novo. */
    fun refresh() {
        if (resumed && enabled()) {
            if (cover == null) restartTimer()
        } else {
            root.removeCallbacks(dim)
            hide()
        }
    }

    /** Toque em qualquer lugar do app: recomeça a contagem para apagar. */
    fun onUserInteraction() {
        if (cover == null) refresh()
    }

    fun resume() {
        resumed = true
        refresh()
    }

    /** App saiu da tela: não apaga nada até voltar. */
    fun pause() {
        resumed = false
        refresh()
    }

    private fun restartTimer() {
        root.removeCallbacks(dim)
        root.postDelayed(dim, DIM_AFTER_MS)
    }

    private fun show() {
        if (!resumed || !enabled() || cover != null) return
        val hint = TextView(activity).apply {
            setText(R.string.night_screen_hint)
            setTextColor(Color.rgb(40, 40, 40))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        cover = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            addView(hint, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = (48 * resources.displayMetrics.density).toInt() })
            setOnClickListener {
                hide()
                restartTimer()
            }
        }
        root.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setBrightness(MIN_BRIGHTNESS)
        insets().apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hide() {
        val current = cover ?: return
        cover = null
        root.removeView(current)
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        insets().show(WindowInsetsCompat.Type.systemBars())
    }

    private fun insets() = WindowCompat.getInsetsController(activity.window, root)

    private fun setBrightness(value: Float) {
        val params = activity.window.attributes
        params.screenBrightness = value
        activity.window.attributes = params
    }

    private companion object {
        const val DIM_AFTER_MS = 15_000L
        // Brilho mínimo (0 apagaria a luz de fundo em alguns aparelhos, parecendo travado).
        const val MIN_BRIGHTNESS = 0.01f
    }
}
