package br.dev.bizo.cameramonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import br.dev.bizo.cameramonitor.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

/**
 * Tela única do app. Sem conta, mostra o login ([LoginController]).
 * Com conta, mostra a navegação inferior com três abas:
 * - Início ([HomeFragment]): status, prévia e botão de iniciar/parar;
 * - Câmera ([CameraFragment]): imagem e detecção de movimento;
 * - Ajustes ([SettingsFragment]): tema, PIN, servidor e conta.
 *
 * Com PIN definido, Câmera e Ajustes pedem o PIN; ao sair do app, ele volta a ser pedido.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var login: LoginController
    private val relay by lazy { RelayClient(this) }
    private val io = Executors.newSingleThreadExecutor()

    // PIN já digitado nesta visita ao app (vale até o app sair da tela).
    private var unlocked = false

    // Botão voltar: nas abas Câmera e Ajustes, volta ao Início. No Início (ou no login),
    // o primeiro toque só avisa; sair exige um segundo toque em até 2 segundos.
    private var lastBackAt = 0L
    private val backHandler = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (relay.isSignedIn && binding.bottomNav.selectedItemId != R.id.nav_home) {
                binding.bottomNav.selectedItemId = R.id.nav_home
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt <= EXIT_WINDOW_MS) {
                lastBackAt = 0L
                exitHint?.dismiss()
                // Deixa o Android fazer o voltar padrão (sair do app); a câmera continua no serviço.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
                return
            }
            lastBackAt = now
            // Aviso curto acima da navegação inferior (um Toast fica por cima dela e,
            // a partir do Android 11, não pode ser reposicionado).
            exitHint = Snackbar.make(binding.root, R.string.back_again_to_exit, EXIT_WINDOW_MS.toInt())
                .apply { if (binding.bottomNav.visibility == View.VISIBLE) anchorView = binding.bottomNav }
                .also { it.show() }
        }
    }
    private var exitHint: Snackbar? = null

    // Versão nova encontrada: avisa uma vez por abertura do app, com atalho para a aba Ajustes.
    private var updateHintShown = false
    private val updateListener: () -> Unit = {
        runOnUiThread {
            val state = AppUpdater.state
            if (state is AppUpdater.State.Available && !updateHintShown && relay.isSignedIn &&
                binding.bottomNav.selectedItemId != R.id.nav_settings
            ) {
                updateHintShown = true
                Snackbar.make(binding.root, getString(R.string.update_snackbar, state.release.versionName), Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.bottomNav)
                    .setAction(R.string.update_snackbar_action) { openTab(R.id.nav_settings) }
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Abertura com o logotipo; precisa vir antes do super.onCreate.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        unlocked = savedInstanceState?.getBoolean(STATE_UNLOCKED) ?: false

        login = LoginController(this, binding.login, relay, io) { registered ->
            if (!registered) showMessage(getString(R.string.register_error))
            updateSignedIn()
        }

        binding.bottomNav.setOnItemSelectedListener { item -> selectTab(item.itemId) }
        // Tocar de novo na aba atual não recria a tela.
        binding.bottomNav.setOnItemReselectedListener { }
        onBackPressedDispatcher.addCallback(this, backHandler)

        // Na primeira abertura, começa no Início; ao recriar (ex.: troca de tema), a aba é restaurada.
        if (savedInstanceState == null) showTab(R.id.nav_home)
        updateSignedIn()
        handleIntent(intent)
    }

    // Notificação de atualização: abre a aba Ajustes, onde fica o botão de atualizar.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AppUpdater.EXTRA_OPEN_UPDATE, false) != true) return
        intent.removeExtra(AppUpdater.EXTRA_OPEN_UPDATE)
        if (relay.isSignedIn) binding.root.post { openTab(R.id.nav_settings) }
    }

    override fun onResume() {
        super.onResume()
        // Aqui a aba selecionada já foi restaurada (ela volta depois do onCreate, ao recriar a tela).
        // Voltou ao app com PIN: as abas protegidas fecham e o PIN será pedido de novo.
        if (needsPin() && binding.bottomNav.selectedItemId != R.id.nav_home) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
        updateToolbar(binding.bottomNav.selectedItemId)
        updateSignedIn()
        AppUpdater.addListener(updateListener)
        // Procura versão nova ao abrir o app (no máximo a cada 6 horas).
        if (relay.isSignedIn) AppUpdater.check(this, onlyIfStaleMs = UPDATE_CHECK_MS)
        // A câmera estava ligada (ex.: pausada após reiniciar o celular): abrir o app retoma a transmissão.
        // Na aba Início, quem faz isso é o HomeFragment, que antes libera a prévia.
        if (binding.bottomNav.selectedItemId != R.id.nav_home && shouldResumeStreaming()) {
            CameraStarter.start(this, relay, io) { error -> error?.let(::showMessage) }
        }
    }

    override fun onPause() {
        AppUpdater.removeListener(updateListener)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) unlocked = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_UNLOCKED, unlocked)
    }

    /** Abre uma aba pelo código (ex.: atalhos da aba Início), passando pelo PIN se preciso. */
    fun openTab(@IdRes tab: Int) {
        binding.bottomNav.selectedItemId = tab
    }

    /** Chamado pela aba Ajustes depois de sair da conta. */
    fun onSignedOut() {
        unlocked = false
        binding.bottomNav.selectedItemId = R.id.nav_home
        updateSignedIn()
    }

    fun showMessage(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).setAnchorView(binding.bottomNav).show()
    }

    fun shouldResumeStreaming(): Boolean =
        StreamService.shouldRun(this) && !StreamService.isRunning && relay.isSignedIn &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // Troca de aba pela navegação inferior. Devolve false para não marcar a aba (ex.: PIN pedido antes).
    private fun selectTab(@IdRes tab: Int): Boolean {
        if (tab != R.id.nav_home && needsPin()) {
            PinDialogs.requirePin(this) {
                unlocked = true
                binding.bottomNav.selectedItemId = tab
            }
            return false
        }
        showTab(tab)
        return true
    }

    private fun showTab(@IdRes tab: Int) {
        val tag = "tab-$tab"
        if (supportFragmentManager.findFragmentByTag(tag)?.isVisible == true) return
        val fragment: Fragment = when (tab) {
            R.id.nav_camera -> CameraFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.content, fragment, tag)
            .commit()
        updateToolbar(tab)
    }

    private fun updateToolbar(@IdRes tab: Int) {
        binding.toolbar.setTitle(
            when (tab) {
                R.id.nav_camera -> R.string.nav_camera
                R.id.nav_settings -> R.string.nav_settings
                else -> R.string.app_name
            }
        )
    }

    // Com conta: barra, abas e conteúdo. Sem conta: só o login.
    private fun updateSignedIn() {
        val signedIn = relay.isSignedIn
        binding.loginContainer.visibility = visibleIf(!signedIn)
        binding.content.visibility = visibleIf(signedIn)
        binding.toolbar.visibility = visibleIf(signedIn)
        binding.bottomNav.visibility = visibleIf(signedIn)
    }

    private fun needsPin() = PinLock.isSet(this) && !unlocked

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private companion object {
        const val STATE_UNLOCKED = "unlocked"
        const val EXIT_WINDOW_MS = 2_000L
        const val UPDATE_CHECK_MS = 6 * 60 * 60_000L
    }
}
