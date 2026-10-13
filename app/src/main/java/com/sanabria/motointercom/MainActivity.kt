package com.sanabria.motointercom

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sanabria.motointercom.ui.MainScreen
import com.sanabria.motointercom.ui.MotoTheme
import com.sanabria.motointercom.ui.ScreenActions
import com.sanabria.motointercom.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: IntercomViewModel by viewModels()
    private var permissionsDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionsDenied = !result.values.all { it }
        if (!permissionsDenied) startWithBluetooth()
    }

    // Si el usuario no lo enciende, el motor avisa de que el Bluetooth está apagado
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotoTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                // Sin servicio en primer plano (bloque 2), con la pantalla apagada Android corta el micro
                LaunchedEffect(state.running) {
                    if (state.running) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                val actions = ScreenActions(
                    start = ::requestStart,
                    stop = vm::stop,
                    update = vm::update,
                    ptt = vm::setPtt,
                    masterVolume = vm::setMasterVolume,
                    voiceVolume = vm::setVoiceVolume,
                    mediaVolume = vm::setMediaVolume,
                    mediaKey = vm::mediaKey,
                )
                var inSettings by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = inSettings) { inSettings = false }
                // Conectado, "atrás" no cuelga: la app pasa al fondo sin destruir el motor (el bloque 2 lo lleva a un servicio)
                BackHandler(enabled = !inSettings && state.running) { moveTaskToBack(true) }
                if (inSettings) {
                    SettingsScreen(state, settings, actions, back = { inSettings = false })
                } else {
                    MainScreen(state, settings, permissionsDenied, actions, openSettings = { inSettings = true })
                }
            }
        }
    }

    private fun requestStart() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startWithBluetooth() else permissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startWithBluetooth() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter != null && !adapter.isEnabled) {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            vm.start()
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
