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
import java.util.concurrent.Executors

/** Aba Ajustes: tema, proteção por PIN, servidor, conta e versão. */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val relay by lazy { RelayClient(requireContext()) }
    private val io = Executors.newSingleThreadExecutor()
    private val main get() = requireActivity() as MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.versionText.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
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
