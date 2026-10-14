package com.sanabria.motointercom.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.sanabria.motointercom.core.crypto.CryptoProvider
import com.sanabria.motointercom.core.crypto.GroupKeys
import com.sanabria.motointercom.core.mesh.LinkLimitLearner
import com.sanabria.motointercom.core.mesh.LinkPolicy
import com.sanabria.motointercom.core.mesh.LinkSession
import com.sanabria.motointercom.core.mesh.LinkTransport
import com.sanabria.motointercom.core.protocol.AdvInfo
import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.FrameCodec
import com.sanabria.motointercom.engine.PhyPreference
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Transporte BLE de la malla. Cada móvil a la vez:
 * - escucha en un canal L2CAP (PSM dinámico) y lo anuncia con el código de su grupo y su id (anuncio normal y, si el
 *   chip puede, otro en Coded PHY para que le encuentren más lejos);
 * - escanea buscando su grupo y, según [LinkPolicy], conecta: primero GATT (para poder pedir Coded PHY, prioridad alta y
 *   leer el RSSI) y sobre esa misma conexión el canal L2CAP;
 * - saluda y cifra con [LinkSession]: el enlace solo vale cuando el otro demuestra tener la clave del grupo. Si dos
 *   móviles se conectan a la vez en los dos sentidos, ambos se quedan con el que inició el de id menor.
 * - Escaneo adaptativo: rápido mientras falta alguien o la malla acaba de cambiar; en ahorro cuando lleva un rato
 *   estable (el escaneo compite por la radio con los enlaces). Si el escaneo o el anuncio fallan, se reintentan.
 *
 * Los avisos al [listener] se hacen siempre fuera del cerrojo interno (el motor llama a [send] con el suyo cogido).
 */
@SuppressLint("MissingPermission")
class BleMesh(
    private val context: Context,
    private val selfId: Int,
    private val crypto: CryptoProvider,
    private val keys: GroupKeys,
    private val options: Options,
    private val listener: LinkTransport.Listener,
    private val clock: () -> Long,
) : LinkTransport {

    /** [learnedLimit] = límite de enlaces del chip aprendido en conexiones anteriores (0 = no se sabe). */
    data class Options(val maxLinks: Int, val longRange: Boolean, val phy: PhyPreference, val learnedLimit: Int = 0)

    data class Caps(val codedPhy: Boolean, val le2M: Boolean, val extendedAdvertising: Boolean, val maxAdvertisingData: Int)

    enum class Problem { NO_ADVERTISER, ADVERTISE_FAILED, CODED_ADVERTISE_FAILED, SCAN_FAILED }

    enum class ScanMode { FAST, SAVING }

    data class LinkInfo(
        val peer: Int,
        val initiator: Boolean,
        val rssi: Int?,
        val txPhy: Int,
        val rxPhy: Int,
        val rttMs: Int?,
        val txBytes: Long,
        val rxBytes: Long,
        val sentFrames: Long,
        val dropped: Long,
        val queued: Int,
    )

    data class Snapshot(
        val psm: Int,
        val advertising: Boolean,
        val codedAdvertising: Boolean,
        val scanning: Boolean,
        val scanMode: ScanMode,
        val seen: Int,
        val links: List<LinkInfo>,
        val problem: Problem?,
        val problemCode: Int,
        /** Enlaces máximos en uso: los del usuario, sin pasar del límite aprendido del chip. */
        val maxLinks: Int,
        val learnedLimit: Int,
    )

    private class Seen(var device: BluetoothDevice, var psm: Int, var rssi: Int, var lastSeen: Long, var waitingSince: Long)

    private val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    val caps = Caps(
        codedPhy = adapter.isLeCodedPhySupported,
        le2M = adapter.isLe2MPhySupported,
        extendedAdvertising = adapter.isLeExtendedAdvertisingSupported,
        maxAdvertisingData = adapter.leMaximumAdvertisingDataLength,
    )

    private val lock = Any()
    private val links = mutableListOf<BleLink>()
    private val seen = HashMap<Int, Seen>()
    private val connecting = HashSet<Int>()
    private var userMaxLinks = options.maxLinks
    private val learner = LinkLimitLearner(options.learnedLimit)
    private val policy = LinkPolicy(selfId).apply { maxLinks = learner.limit(options.maxLinks) }

    @Volatile private var running = false
    @Volatile private var phy = options.phy
    private var server: BluetoothServerSocket? = null
    private var psm = 0
    private var ticker: ScheduledExecutorService? = null
    private var tickCount = 0L

    @Volatile private var advertising = false
    @Volatile private var codedAdvertising = false
    @Volatile private var scanning = false
    @Volatile private var scanMode = ScanMode.FAST
    @Volatile private var problem: Problem? = null
    @Volatile private var problemCode = 0
    private var lastScanStart = 0L
    private var lastAdvertiseStart = 0L
    @Volatile private var lastLinkChange = 0L

    /** Abre el canal L2CAP, anuncia y escanea. Lanza IOException si no puede abrir el canal. */
    fun start() {
        val srv = adapter.listenUsingInsecureL2capChannel()
        server = srv
        psm = srv.psm
        running = true
        lastLinkChange = clock()
        thread(name = "l2cap-accept") { acceptLoop(srv) }
        startAdvertising()
        startScan(ScanMode.FAST)
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ble-mesh") }.also {
            it.scheduleWithFixedDelay(::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        running = false
        ticker?.shutdownNow()
        ticker = null
        stopScan()
        stopAdvertising()
        runCatching { server?.close() }
        server = null
        val all = synchronized(lock) { links.toList().also { seen.clear(); connecting.clear() } }
        all.forEach { it.close() }
    }

    override fun send(peer: Int, content: ByteArray, audio: Boolean) {
        val link = synchronized(lock) { links.firstOrNull { it.ready && it.peer == peer } } ?: return
        link.send(content, audio)
    }

    /** El PHY se puede cambiar con los enlaces abiertos: se pide de nuevo en todos. */
    fun setPhy(p: PhyPreference) {
        phy = p
        synchronized(lock) { links.filter { it.ready } }.forEach { applyPhy(it) }
    }

    fun setMaxLinks(n: Int) = synchronized(lock) {
        userMaxLinks = n
        policy.maxLinks = learner.limit(n)
    }

    /** Olvida el límite aprendido del chip (el usuario lo pide desde Ajustes). */
    fun resetLearnedLimit() = synchronized(lock) {
        learner.reset()
        policy.maxLinks = learner.limit(userMaxLinks)
    }

    fun snapshot(): Snapshot {
        val (infos, seenCount) = synchronized(lock) {
            links.filter { it.ready }.map {
                LinkInfo(
                    it.peer, it.initiator, it.gatt.rssi, it.gatt.txPhy, it.gatt.rxPhy, it.rttMs,
                    it.txBytes, it.rxBytes, it.sentAudio, it.dropped, it.queued,
                )
            } to seen.size
        }
        val (max, learned) = synchronized(lock) { policy.maxLinks to learner.learned }
        return Snapshot(psm, advertising, codedAdvertising, scanning, scanMode, seenCount, infos, problem, problemCode, max, learned)
    }

    /** Resultado de añadir un enlace, para aprender el límite del chip. Con el cerrojo cogido. */
    private fun learnLimit(ready: Int, rssi: Int?, success: Boolean) {
        if (learner.onResult(ready, goodSignal = rssi != null && rssi >= GOOD_RSSI, success = success)) {
            policy.maxLinks = learner.limit(userMaxLinks)
        }
    }

    // --- Anuncio ---

    private fun startAdvertising() {
        lastAdvertiseStart = clock()
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            report(Problem.NO_ADVERTISER, 0)
            return
        }
        val payload = AdvInfo(FrameCodec.PROTOCOL, keys.advId, selfId, psm).encode()
        // Anuncio normal (1M): el UUID va en el anuncio (lo filtra el hardware) y los datos en la respuesta al escaneo
        val legacy = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val advData = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(SERVICE).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceData(SERVICE, payload).build()
        advertiser.startAdvertisingSet(legacy, advData, scanResponse, null, null, legacyAdvCallback)

        // Largo alcance: anuncio extendido en Coded PHY (todo en un paquete; conectable y escaneable no pueden ir juntos)
        if (options.longRange && caps.codedPhy && caps.extendedAdvertising) {
            val coded = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(true)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(SERVICE).addServiceData(SERVICE, payload).build()
            advertiser.startAdvertisingSet(coded, data, null, null, null, codedAdvCallback)
        }
    }

    private fun stopAdvertising() {
        adapter.bluetoothLeAdvertiser?.let { adv ->
            runCatching { adv.stopAdvertisingSet(legacyAdvCallback) }
            runCatching { adv.stopAdvertisingSet(codedAdvCallback) }
        }
        advertising = false
        codedAdvertising = false
    }

    private val legacyAdvCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            advertising = status == ADVERTISE_SUCCESS
            if (!advertising) report(Problem.ADVERTISE_FAILED, status)
        }
    }

    private val codedAdvCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            codedAdvertising = status == ADVERTISE_SUCCESS
            if (!codedAdvertising) report(Problem.CODED_ADVERTISE_FAILED, status)
        }
    }

    // --- Escaneo ---

    private fun startScan(mode: ScanMode) {
        lastScanStart = clock()
        val scanner = adapter.bluetoothLeScanner ?: return report(Problem.SCAN_FAILED, 0)
        val settings = ScanSettings.Builder()
            .setScanMode(if (mode == ScanMode.FAST) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .apply {
                // Sin esto solo llegan anuncios normales y no se vería el de Coded PHY
                if (caps.extendedAdvertising) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        val filter = ScanFilter.Builder().setServiceUuid(SERVICE).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onSuccess {
                scanning = true
                scanMode = mode
            }
            .onFailure { report(Problem.SCAN_FAILED, 0) }
    }

    private fun stopScan() {
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onSeen(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onSeen(it) }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            report(Problem.SCAN_FAILED, errorCode)
        }
    }

    private fun onSeen(result: ScanResult) {
        val info = AdvInfo.decode(result.scanRecord?.getServiceData(SERVICE)) ?: return
        if (info.protocol != FrameCodec.PROTOCOL || info.groupId != keys.advId || info.nodeId == selfId) return
        val now = clock()
        synchronized(lock) {
            val s = seen[info.nodeId]
            if (s == null) {
                seen[info.nodeId] = Seen(result.device, info.psm, result.rssi, now, now)
            } else {
                s.device = result.device
                s.psm = info.psm
                s.rssi = result.rssi
                s.lastSeen = now
            }
        }
    }

    // --- Enlaces ---

    private fun tick() {
        if (!running) return
        val now = clock()
        tickCount++
        val toClose = mutableListOf<BleLink>()
        val toConnect = mutableListOf<Pair<Int, Seen>>()
        val open: List<BleLink>
        val missing: Boolean
        synchronized(lock) {
            val linked = links.filter { it.ready }.map { it.peer }.toSet()
            // Mientras hay enlace no se espera: si se cae, la espera para el reintento asimétrico cuenta desde entonces
            for (id in linked) seen[id]?.waitingSince = now
            seen.entries.removeAll { now - it.value.lastSeen > FORGET_SEEN_MS }
            links.filter { !it.ready && now - it.createdAt > HELLO_TIMEOUT_MS }.forEach { toClose += it }
            // Enlace muerto: el otro no contesta ni al ping. Se corta ya para reconectar, sin esperar al Bluetooth
            links.filter { it.ready && now - it.lastRxAt > DEAD_LINK_MS }.forEach { toClose += it }

            val candidates = seen.map { (id, s) -> LinkPolicy.Candidate(id, s.rssi, s.lastSeen, s.waitingSince) }
            for (id in policy.pick(candidates, linked, connecting, now)) {
                connecting += id
                toConnect += id to seen.getValue(id)
            }
            open = links.filter { it.ready }
            // Falta alguien: un nodo visto hace poco con el que no hay enlace (y aún cabe uno más)
            missing = linked.isEmpty() || (linked.size < policy.maxLinks &&
                seen.any { (id, s) -> id !in linked && now - s.lastSeen < LinkPolicy.SEEN_TIMEOUT_MS })
        }
        toClose.forEach { it.close() }
        toConnect.forEach { (id, s) -> connect(id, s.device, s.psm, s.rssi) }
        if (tickCount % (PING_MS / TICK_MS) == 0L) open.forEach { it.ping() }
        if (tickCount % (RSSI_MS / TICK_MS) == 0L) open.forEach { it.gatt.gatt?.readRemoteRssi() }

        // Escaneo adaptativo y reintentos. Android solo deja arrancar el escaneo 5 veces cada 30 s: como mucho una cada 10 s
        val wanted = if (missing || now - lastLinkChange < STABLE_MS) ScanMode.FAST else ScanMode.SAVING
        if ((!scanning || wanted != scanMode) && now - lastScanStart >= SCAN_RESTART_MS) {
            stopScan()
            startScan(wanted)
        }
        if (!advertising && now - lastAdvertiseStart >= ADVERTISE_RETRY_MS) {
            stopAdvertising()
            startAdvertising()
        }
    }

    private fun connect(id: Int, device: BluetoothDevice, psm: Int, rssi: Int) = thread(name = "ble-connect") {
        val ready = synchronized(lock) { links.count { it.ready } }
        val handle = GattHandle()
        try {
            val mask = if (options.longRange && caps.codedPhy) {
                BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK
            } else {
                BluetoothDevice.PHY_LE_1M_MASK
            }
            // Obsoleta en API 37, pero la sustituta no existe en las versiones que soporta la app (desde la 29)
            @Suppress("DEPRECATION")
            handle.gatt = device.connectGatt(context, false, handle, BluetoothDevice.TRANSPORT_LE, mask)
            if (!handle.settled.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !handle.connected) throw IOException("GATT")
            handle.gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            val socket = device.createInsecureL2capChannel(psm)
            socket.connect()
            if (!running) {
                socket.close()
                throw IOException("parado")
            }
            register(BleLink(socket, initiator = true, gatt = handle, session = newSession(true), clock = clock, events = linkEvents))
        } catch (_: Exception) {
            handle.close()
            synchronized(lock) {
                policy.onFailure(id, clock())
                if (running) learnLimit(ready, rssi, success = false)
            }
        } finally {
            synchronized(lock) { connecting -= id }
        }
    }

    private fun acceptLoop(srv: BluetoothServerSocket) {
        while (running) {
            val socket = try {
                srv.accept()
            } catch (_: IOException) {
                break
            }
            if (!running) {
                runCatching { socket.close() }
                break
            }
            val count = synchronized(lock) { links.size }
            if (count >= options.maxLinks + EXTRA_INCOMING) {
                runCatching { socket.close() }
                continue
            }
            // Cliente GATT sobre la conexión que ya existe: solo para RSSI, PHY y prioridad
            val handle = GattHandle()
            @Suppress("DEPRECATION")
            handle.gatt = socket.remoteDevice.connectGatt(context, false, handle, BluetoothDevice.TRANSPORT_LE)
            register(BleLink(socket, initiator = false, gatt = handle, session = newSession(false), clock = clock, events = linkEvents))
        }
    }

    private fun newSession(initiator: Boolean) = LinkSession(crypto, keys, selfId, initiator)

    private fun register(link: BleLink) {
        synchronized(lock) { links += link }
        link.start()
    }

    private fun applyPhy(link: BleLink) {
        val g = link.gatt.gatt ?: return
        val (mask, opt) = when (phy) {
            PhyPreference.AUTO -> return
            PhyPreference.LE_1M -> BluetoothDevice.PHY_LE_1M_MASK to BluetoothDevice.PHY_OPTION_NO_PREFERRED
            PhyPreference.LE_2M -> {
                if (!caps.le2M) return
                BluetoothDevice.PHY_LE_2M_MASK to BluetoothDevice.PHY_OPTION_NO_PREFERRED
            }
            PhyPreference.CODED_S2 -> {
                if (!caps.codedPhy) return
                BluetoothDevice.PHY_LE_CODED_MASK to BluetoothDevice.PHY_OPTION_S2
            }
            PhyPreference.CODED_S8 -> {
                if (!caps.codedPhy) return
                BluetoothDevice.PHY_LE_CODED_MASK to BluetoothDevice.PHY_OPTION_S8
            }
        }
        g.setPreferredPhy(mask, mask, opt)
    }

    private fun report(p: Problem, code: Int) {
        problem = p
        problemCode = code
    }

    /** El que inició el enlace, visto desde aquí. */
    private fun initiatorOf(link: BleLink) = if (link.initiator) selfId else link.peer

    private val linkEvents = object : BleLink.Events {
        override fun onPeerHello(link: BleLink, nodeId: Int): Boolean = synchronized(lock) {
            // Si ya hay enlace con ese nodo y es el bueno (lo inició el de id menor), este sobra. Si no, se mantienen los
            // dos hasta que este termine el saludo y entonces se cierra el viejo (sin cortar la conversación).
            val preferred = minOf(selfId, nodeId)
            links.none { it !== link && it.peer == nodeId && it.ready && initiatorOf(it) == preferred }
        }

        override fun onUp(link: BleLink) {
            val old = synchronized(lock) {
                val preferred = minOf(selfId, link.peer)
                val others = links.filter { it !== link && it.peer == link.peer }
                // Dos enlaces cruzados que terminan el saludo a la vez: gana siempre el que inició el de id menor
                if (initiatorOf(link) != preferred && others.any { initiatorOf(it) == preferred }) {
                    null
                } else {
                    lastLinkChange = clock()
                    policy.onSuccess(link.peer)
                    // Un enlace más con los que ya había: el chip lo aguanta (entrantes incluidos)
                    if (others.none { it.ready }) learnLimit(links.count { it.ready && it !== link }, link.gatt.rssi, success = true)
                    others
                }
            }
            if (old == null) {
                link.close()
                return
            }
            // El nuevo ya está listo: cerrar los viejos no avisa de caída
            old.forEach { it.close() }
            applyPhy(link)
            link.gatt.gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            listener.onLinkUp(link.peer)
        }

        override fun onFrame(link: BleLink, frame: Frame) = listener.onFrame(link.peer, frame)

        override fun onClosed(link: BleLink) {
            val down = synchronized(lock) {
                links.remove(link)
                if (link.ready) lastLinkChange = clock()
                link.ready && links.none { it.ready && it.peer == link.peer }
            }
            if (down) listener.onLinkDown(link.peer)
        }
    }

    companion object {
        fun capsOf(context: Context): Caps? {
            val a = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            return Caps(a.isLeCodedPhySupported, a.isLe2MPhySupported, a.isLeExtendedAdvertisingSupported, a.leMaximumAdvertisingDataLength)
        }

        /** Servicio de la app: lo que se busca al escanear. */
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("6d6f746f-6963-4f6d-a001-000000000001"))

        const val TICK_MS = 250L
        const val PING_MS = 1_000L
        const val RSSI_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val HELLO_TIMEOUT_MS = 5_000L
        const val FORGET_SEEN_MS = 30_000L

        /** Sin nada del otro (ni el pong de un ping por segundo) en este tiempo, el enlace se da por muerto. */
        const val DEAD_LINK_MS = 6_000L

        /** Con esta señal o mejor, si no se puede añadir un enlace no es por alcance (para aprender el límite del chip). */
        const val GOOD_RSSI = -75

        /** Sin cambios en los enlaces durante este tiempo, la malla se da por estable y el escaneo pasa a ahorro. */
        const val STABLE_MS = 30_000L
        const val SCAN_RESTART_MS = 10_000L
        const val ADVERTISE_RETRY_MS = 10_000L

        /** Entrantes de más que se aceptan aunque se esté en el máximo (el otro puede no tener alternativa). */
        const val EXTRA_INCOMING = 2
    }
}
