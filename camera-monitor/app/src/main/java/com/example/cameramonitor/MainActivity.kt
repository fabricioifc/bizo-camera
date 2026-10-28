package com.example.cameramonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.cameramonitor.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val relay by lazy { RelayClient(this) }
    private val io = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasCameraPermission()) {
            startStreaming()
        } else {
            binding.infoText.text = getString(R.string.need_camera)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            when {
                StreamService.isRunning -> stopStreaming()
                !relay.isSignedIn -> binding.loginMessage.text = getString(R.string.need_login)
                else -> requestPermissionsAndStart()
            }
        }
        binding.loginButton.setOnClickListener { signIn() }
        binding.signOutButton.setOnClickListener {
            relay.signOut()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        RelayClient.onChange = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        RelayClient.onChange = null
        super.onPause()
    }

    private fun signIn() {
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        if (email.isEmpty() || password.isEmpty()) {
            binding.loginMessage.text = getString(R.string.login_fill)
            return
        }
        binding.loginButton.isEnabled = false
        binding.loginMessage.text = getString(R.string.login_loading)
        io.execute {
            val loggedIn = relay.signIn(email, password)
            val registered = loggedIn && relay.registerDevice()
            runOnUiThread {
                binding.loginButton.isEnabled = true
                binding.loginMessage.text = when {
                    !loggedIn -> getString(R.string.login_error)
                    !registered -> getString(R.string.register_error)
                    else -> ""
                }
                if (loggedIn) binding.passwordInput.text?.clear()
                refresh()
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val missing = buildList {
            if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) startStreaming() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startStreaming() {
        // Registra o aparelho antes de subir o serviço, para ele já conectar.
        io.execute {
            val registered = relay.registerDevice()
            runOnUiThread {
                if (registered) {
                    ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java))
                    binding.infoText.postDelayed({ refresh() }, 800)
                } else {
                    binding.infoText.text = getString(R.string.register_error)
                }
            }
        }
    }

    private fun stopStreaming() {
        startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        binding.infoText.postDelayed({ refresh() }, 500)
    }

    private fun refresh() {
        val running = StreamService.isRunning
        val signedIn = relay.isSignedIn

        binding.toggleButton.setText(if (running) R.string.toggle_stop else R.string.toggle_start)
        binding.loginPanel.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.signOutButton.visibility = if (signedIn && !running) View.VISIBLE else View.GONE
        binding.infoText.text = when {
            running && !RelayClient.connected -> getString(R.string.status_connecting)
            running && RelayClient.streaming -> getString(R.string.status_live)
            running -> getString(R.string.status_connected)
            !signedIn -> getString(R.string.need_login)
            else -> getString(R.string.stopped)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
