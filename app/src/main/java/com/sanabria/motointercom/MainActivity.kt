package com.sanabria.motointercom

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sanabria.motointercom.core.crypto.Group
import com.sanabria.motointercom.core.crypto.GroupInvite
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.ui.HelmetTestScreen
import com.sanabria.motointercom.ui.JoinDialog
import com.sanabria.motointercom.ui.MainScreen
import com.sanabria.motointercom.ui.MotoTheme
import com.sanabria.motointercom.ui.ScreenActions
import com.sanabria.motointercom.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private enum class Screen { MAIN, SETTINGS, HELMET_TEST }

    private val vm: IntercomViewModel by viewModels()
    private var permissionsDenied by mutableStateOf(false)

    /** Invitación pendiente de confirmar (de un QR, del portapapeles o de un enlace motointercom://). */
    private var pendingInvite by mutableStateOf<Group?>(null)

    private var afterPermissions: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Las notificaciones no son imprescindibles: sin ellas el servicio funciona igual, solo no se ve su aviso
        val required = result.filterKeys { it != NOTIFICATION_PERMISSION }
        permissionsDenied = !required.values.all { it }
        if (!permissionsDenied) afterPermissions?.invoke()
        afterPermissions = null
    }

    // Si el usuario no lo enciende, el motor avisa de que el Bluetooth está apagado
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleInvite(intent)
        setContent {
            MotoTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                val helmets by vm.helmets.collectAsStateWithLifecycle()
                val test by vm.helmetTester.state.collectAsStateWithLifecycle()
                val actions = ScreenActions(
                    start = { withPermissions(connectPermissions()) { startWithBluetooth() } },
                    stop = vm::stop,
                    update = vm::update,
                    ptt = vm::setPtt,
                    toggleMic = vm::toggleMic,
                    masterVolume = vm::setMasterVolume,
                    voiceVolume = vm::setVoiceVolume,
                    mediaVolume = vm::setMediaVolume,
                    mediaKey = vm::mediaKey,
                    newGroup = vm::newGroup,
                    scanInvite = ::scanInvite,
                    pasteInvite = ::pasteInvite,
                    shareInvite = ::shareInvite,
                    openBatterySettings = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    testHelmet = { mode: AudioMode -> withPermissions(listOf(Manifest.permission.RECORD_AUDIO) + btConnect()) { vm.testHelmet(mode) } },
                    answerHelmetTest = vm::answerHelmetTest,
                    cancelHelmetTest = vm::cancelHelmetTest,
                )
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                BackHandler(enabled = screen != Screen.MAIN) {
                    if (screen == Screen.HELMET_TEST) vm.cancelHelmetTest()
                    screen = if (screen == Screen.HELMET_TEST) Screen.SETTINGS else Screen.MAIN
                }
                when (screen) {
                    Screen.MAIN -> MainScreen(state, settings, permissionsDenied, actions, openSettings = { screen = Screen.SETTINGS })
                    Screen.SETTINGS -> SettingsScreen(
                        state, settings, helmets, actions,
                        openHelmetTest = { screen = Screen.HELMET_TEST },
                        back = { screen = Screen.MAIN },
                    )
                    Screen.HELMET_TEST -> HelmetTestScreen(test, settings, actions, back = { screen = Screen.SETTINGS })
                }
                pendingInvite?.let { g ->
                    JoinDialog(
                        g,
                        onJoin = {
                            if (!state.running) vm.joinGroup(g) else toast(R.string.group_locked_hint)
                            pendingInvite = null
                        },
                        onDismiss = { pendingInvite = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    // --- Invitaciones ---

    private fun handleInvite(intent: Intent?) {
        val data = intent?.data ?: return
        pendingInvite = GroupInvite.parse(data.toString()) ?: run {
            toast(R.string.invite_invalid)
            null
        }
    }

    private fun scanInvite() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> onInviteText(code.rawValue) }
            .addOnFailureListener { toast(R.string.scan_failed) }
    }

    private fun pasteInvite() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        onInviteText(text)
    }

    private fun onInviteText(text: String?) {
        val g = text?.let { GroupInvite.parse(it) }
        if (g == null) toast(R.string.invite_invalid) else pendingInvite = g
    }

    private fun shareInvite(uri: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.invite_share_text, vm.settings.value.groupName, uri))
        startActivity(Intent.createChooser(send, null))
    }

    // --- Conexión y permisos ---

    private fun withPermissions(perms: List<String>, then: () -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            then()
        } else {
            afterPermissions = then
            permissionLauncher.launch(missing.toTypedArray())
        }
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

    private fun btConnect() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    private fun connectPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(NOTIFICATION_PERMISSION)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private companion object {
        const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
