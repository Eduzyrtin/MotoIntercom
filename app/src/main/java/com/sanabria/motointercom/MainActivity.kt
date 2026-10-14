package com.sanabria.motointercom

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
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
import com.sanabria.motointercom.ui.HelmetPickerDialog
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

    /** Lista de dispositivos emparejados abierta para elegir el casco (null = cerrada). */
    private var helmetPicker by mutableStateOf<List<IntercomViewModel.Bonded>?>(null)

    /** Casco elegido a la espera de que Android confirme la asociación. */
    private var pendingHelmet: IntercomViewModel.Bonded? = null
    private var associationLaunched = false

    private val associateHelmet = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val h = pendingHelmet ?: return@registerForActivityResult
        pendingHelmet = null
        // Sin asociación la autoconexión sigue, pero solo con la notificación (Android no deja abrir la app sola)
        if (r.resultCode != RESULT_OK) toast(R.string.cdm_failed)
        vm.setHelmet(h.address, h.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MotoTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                val helmets by vm.helmets.collectAsStateWithLifecycle()
                val test by vm.helmetTester.state.collectAsStateWithLifecycle()
                val helmetHint by vm.helmetHint.collectAsStateWithLifecycle()
                val actions = ScreenActions(
                    start = { withPermissions(connectPermissions()) { startWithBluetooth() } },
                    stop = vm::stop,
                    update = vm::update,
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
                    shareHelmetResults = { shareText(vm.exportHelmets()) },
                    forgetLinkLimit = vm::forgetLinkLimit,
                    activateGroup = vm::activateGroup,
                    makeDefaultGroup = vm::makeDefaultGroup,
                    deleteGroup = vm::deleteGroup,
                    pickHelmet = { withPermissions(btConnect()) { helmetPicker = vm.bondedDevices() } },
                )
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                BackHandler(enabled = screen != Screen.MAIN) {
                    if (screen == Screen.HELMET_TEST) vm.cancelHelmetTest()
                    screen = if (screen == Screen.HELMET_TEST) Screen.SETTINGS else Screen.MAIN
                }
                when (screen) {
                    Screen.MAIN -> MainScreen(state, settings, permissionsDenied, helmetHint, actions, openSettings = { screen = Screen.SETTINGS })
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
                helmetPicker?.let { devices ->
                    HelmetPickerDialog(
                        devices,
                        onPick = {
                            helmetPicker = null
                            associate(it)
                        },
                        onDismiss = { helmetPicker = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == HelmetReceiver.ACTION_AUTOCONNECT) autoConnect() else handleInvite(intent)
    }

    // --- Autoconexión ---

    /** Se ha encendido el casco: conectar con el grupo por defecto (si no estaba ya conectado). */
    private fun autoConnect() {
        if (vm.state.value.running) return
        vm.useDefaultGroup()
        withPermissions(connectPermissions()) { startWithBluetooth() }
    }

    /**
     * Asocia el casco con Android como dispositivo complementario: con eso Android deja que la app se abra sola cuando
     * el casco se conecta. Android enseña su propio diálogo de confirmación.
     */
    private fun associate(device: IntercomViewModel.Bonded) {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        val old = vm.settings.value.helmetAddress
        if (cdm == null) {
            vm.setHelmet(device.address, device.name)
            return
        }
        @Suppress("DEPRECATION")
        if (old.isNotEmpty() && !old.equals(device.address, true)) runCatching { cdm.disassociate(old) }
        @Suppress("DEPRECATION")
        if (cdm.associations.any { it.equals(device.address, ignoreCase = true) }) {
            vm.setHelmet(device.address, device.name)
            return
        }
        pendingHelmet = device
        associationLaunched = false
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(device.address).build())
            .setSingleDevice(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Android 12 y anteriores")
            override fun onDeviceFound(intentSender: IntentSender) = launchAssociation(intentSender)

            override fun onAssociationPending(intentSender: IntentSender) = launchAssociation(intentSender)

            override fun onFailure(error: CharSequence?) {
                toast(R.string.cdm_failed)
                pendingHelmet = null
                vm.setHelmet(device.address, device.name)
            }
        }
        @Suppress("DEPRECATION")
        cdm.associate(request, callback, null)
    }

    private fun launchAssociation(sender: IntentSender) {
        if (associationLaunched) return
        associationLaunched = true
        associateHelmet.launch(IntentSenderRequest.Builder(sender).build())
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

    private fun shareInvite(uri: String) = shareText(getString(R.string.invite_share_text, vm.settings.value.groupName, uri))

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
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
