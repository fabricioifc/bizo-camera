package br.dev.bizo.cameramonitor

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import br.dev.bizo.cameramonitor.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Aba Ajustes: tema, proteção por PIN, servidor, conta e atualização do app. */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val relay by lazy { RelayClient(requireContext()) }
    private val io = Executors.newSingleThreadExecutor()
    private val main get() = requireActivity() as MainActivity

    private val updateListener: () -> Unit = { activity?.runOnUiThread { if (_binding != null) showUpdate() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.updateInstalled.text = getString(R.string.update_installed, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        binding.updateButton.setOnClickListener { onUpdateClicked() }
        setUpTheme()

        val activity = requireActivity() as AppCompatActivity
        binding.pinSetButton.setOnClickListener {
            PinDialogs.chooseNewPin(activity) { pinChanged(R.string.pin_saved) }
        }
        binding.pinRemoveButton.setOnClickListener {
            PinLock.clear(requireContext())
            pinChanged(R.string.pin_removed)
        }
        binding.serverButton.setOnClickListener {
            ServerDialog.show(activity, relay, io) {
                main.showMessage(getString(R.string.server_saved))
                refresh()
            }
        }
        binding.signOutButton.setOnClickListener {
            relay.signOut()
            main.onSignedOut()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        AppUpdater.addListener(updateListener)
        // Ao abrir os Ajustes, procura versão nova (no máximo a cada 5 minutos).
        AppUpdater.check(requireContext(), onlyIfStaleMs = 5 * 60_000L)
        showUpdate()
    }

    override fun onPause() {
        AppUpdater.removeListener(updateListener)
        super.onPause()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // Tema: aplica na hora; o app é recriado já com as cores novas, nesta mesma aba.
    private fun setUpTheme() {
        val context = requireContext()
        binding.themeGroup.check(
            when (AppTheme.mode(context)) {
                ThemeMode.SYSTEM -> R.id.themeSystem
                ThemeMode.LIGHT -> R.id.themeLight
                ThemeMode.DARK -> R.id.themeDark
            }
        )
        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLight -> ThemeMode.LIGHT
                R.id.themeDark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            if (mode != AppTheme.mode(context)) AppTheme.setMode(context, mode)
        }
    }

    // Cartão Atualização: estado da busca, do download e da instalação.
    private fun showUpdate() {
        val state = AppUpdater.state
        binding.updateStatus.text = when (state) {
            AppUpdater.State.Idle -> ""
            AppUpdater.State.Checking -> getString(R.string.update_checking)
            AppUpdater.State.UpToDate -> getString(R.string.update_up_to_date)
            is AppUpdater.State.Available -> getString(R.string.update_available, state.release.versionName)
            is AppUpdater.State.Downloading -> getString(R.string.update_downloading, state.release.versionName, state.percent)
            is AppUpdater.State.Installing -> getString(R.string.update_installing)
            is AppUpdater.State.Failed -> state.message
        }
        binding.updateProgress.visibility = if (state is AppUpdater.State.Downloading) View.VISIBLE else View.GONE
        if (state is AppUpdater.State.Downloading) binding.updateProgress.setProgressCompat(state.percent, true)
        binding.updateButton.setText(
            if (state is AppUpdater.State.Available) R.string.update_install_button else R.string.update_check_button
        )
        binding.updateButton.isEnabled = state !is AppUpdater.State.Checking && state !is AppUpdater.State.Downloading
    }

    private fun onUpdateClicked() {
        val context = requireContext()
        if (AppUpdater.state !is AppUpdater.State.Available) {
            AppUpdater.check(context)
            return
        }
        if (!AppUpdater.canInstall(context)) {
            // Primeira vez: o Android precisa liberar este app a instalar atualizações.
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_text)
                .setPositiveButton(R.string.update_permission_button) { _, _ ->
                    startActivity(AppUpdater.installPermissionIntent(context))
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }
        AppUpdater.downloadAndInstall(context)
    }

    private fun refresh() {
        val context = requireContext()
        val running = StreamService.isRunning

        val pinSet = PinLock.isSet(context)
        binding.protectionTitle.setText(if (pinSet) R.string.protection_on_title else R.string.protection_off_title)
        binding.protectionStatus.setText(if (pinSet) R.string.protection_on else R.string.protection_off)
        binding.pinSetButton.setText(if (pinSet) R.string.pin_change else R.string.pin_set)
        binding.pinRemoveButton.visibility = if (pinSet) View.VISIBLE else View.GONE

        binding.serverText.text = ServerDialog.displayName(ServerSettings.url(context))
        binding.accountText.text = relay.email ?: ""
        // Sair da conta com a câmera ligada deixaria o serviço sem sessão: pede para parar antes.
        binding.signOutButton.isEnabled = !running
        binding.signOutHint.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun pinChanged(@StringRes message: Int) {
        main.showMessage(getString(message))
        // Atualiza a notificação (com ou sem o botão Parar) se a câmera estiver ligada.
        val context = requireContext()
        if (StreamService.isRunning) context.startService(Intent(context, StreamService::class.java))
        refresh()
    }
}
