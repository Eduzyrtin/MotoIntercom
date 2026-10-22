package com.sanabria.motointercom.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import com.sanabria.motointercom.core.mesh.LinkSession
import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.FrameAssembler
import com.sanabria.motointercom.core.protocol.FrameCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Cliente GATT sobre la misma conexión que el canal L2CAP. No se usa para datos: solo da el RSSI del enlace, el PHY y la
 * prioridad de conexión (intervalo corto = menos latencia). En el que inicia, además, abre la conexión pidiendo también
 * Coded PHY, cosa que el socket L2CAP solo no permite.
 */
@SuppressLint("MissingPermission")
class GattHandle : BluetoothGattCallback() {
    @Volatile var gatt: BluetoothGatt? = null
    @Volatile var connected = false
        private set
    @Volatile var rssi: Int? = null
        private set
    @Volatile var txPhy = 0
        private set
    @Volatile var rxPhy = 0
        private set

    /** Se libera al conectar o al fallar. */
    val settled = CountDownLatch(1)

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        connected = newState == BluetoothProfile.STATE_CONNECTED
        if (connected) g.readPhy()
        settled.countDown()
    }

    override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) this.rssi = rssi
    }

    override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = onPhy(txPhy, rxPhy, status)

    override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = onPhy(txPhy, rxPhy, status)

    private fun onPhy(tx: Int, rx: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            txPhy = tx
            rxPhy = rx
        }
    }

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        connected = false
    }
}

/**
 * Un enlace L2CAP con otro móvil: un hilo lee y reconstruye tramas, otro escribe desde una cola.
 *
 * El saludo y el cifrado los lleva [session]: el Hello sale en claro y todo lo demás se cifra en el hilo de escritura,
 * justo antes de enviarlo (así el contador del cifrado sigue el orden real del cable). La voz que no sale a tiempo se
 * descarta de la cola antes de cifrarla (mejor perder 20 ms viejos que acumular retardo); el control nunca se descarta.
 */
class BleLink(
    private val socket: BluetoothSocket,
    val initiator: Boolean,
    val gatt: GattHandle,
    private val session: LinkSession,
    private val clock: () -> Long,
    private val events: Events,
) {
    interface Events {
        /** El otro es del grupo (Hello válido). Devuelve false para rechazar el enlace (hay otro mejor con ese nodo). */
        fun onPeerHello(link: BleLink, nodeId: Int): Boolean

        /** El otro ha demostrado tener la clave: el enlace ya vale. */
        fun onUp(link: BleLink)

        fun onFrame(link: BleLink, frame: Frame)

        fun onClosed(link: BleLink)
    }

    /** [bytes] es una trama ya lista (Hello en claro) o un contenido que hay que cifrar al enviarlo. */
    private class Item(val bytes: ByteArray, val audio: Boolean, val raw: Boolean)

    val createdAt = clock()

    /** Id del otro extremo (desde su Hello). */
    @Volatile var peer = 0
        private set

    /** Saludo completo: se le puede mandar voz. */
    @Volatile var ready = false
        private set

    @Volatile var rttMs: Int? = null
        private set

    /** Cuándo salió el ping que aún no ha vuelto (0 = ninguno). Atascado el enlace, el pong llega tarde: su edad lo delata. */
    @Volatile private var pingSentAt = 0L
    @Volatile var txBytes = 0L
        private set
    @Volatile var rxBytes = 0L
        private set
    @Volatile var dropped = 0L
        private set

    /** Tramas de voz que han salido de verdad (no descartadas). */
    @Volatile var sentAudio = 0L
        private set

    /** Última vez que llegó algo válido del otro (con el ping, al menos una vez por segundo). */
    @Volatile var lastRxAt = clock()
        private set

    private val closed = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<Item>()
    private var queuedAudio = 0

    val queued: Int get() = lock.withLock { queue.size }

    fun start() {
        enqueue(Item(session.hello(), audio = false, raw = true))
        thread(name = "l2cap-read") { readLoop() }
        thread(name = "l2cap-write") { writeLoop() }
    }

    /**
     * Retardo que llevan ahora los datos dentro del enlace: el mayor entre el último RTT y la edad del ping que sigue sin
     * volver. El ping va por el mismo canal que la voz, así que mide también lo que la pila Bluetooth tiene retenido.
     */
    val backlogMs: Int
        get() {
            val sent = pingSentAt
            val waiting = if (sent == 0L) 0 else (clock() - sent).toInt()
            return maxOf(rttMs ?: 0, waiting)
        }

    /** Contenido (tipo + cuerpo) para este enlace; se cifra al enviarlo. */
    fun send(content: ByteArray, audio: Boolean) {
        if (!ready) return
        // Con el canal atascado, más voz solo alarga el retardo (el socket no avisa: escribe en una cola enorme). Se
        // descarta y cuenta como descarte, para que la calidad adaptativa baje
        if (audio && backlogMs > MAX_BACKLOG_MS) {
            lock.withLock { dropped++ }
            return
        }
        enqueue(Item(content, audio, raw = false))
    }

    /** Un solo ping a la vez: si el enlace se atasca no se apilan, y la edad del pendiente mide el atasco. */
    fun ping() {
        if (pingSentAt != 0L) return
        val now = clock()
        pingSentAt = now
        send(FrameCodec.encodeBody(Frame.Ping(now.toInt())), audio = false)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        lock.withLock { notEmpty.signalAll() }
        runCatching { socket.close() }
        gatt.close()
        events.onClosed(this)
    }

    private fun enqueue(item: Item) {
        lock.withLock {
            if (closed.get()) return
            if (item.audio) {
                if (queuedAudio >= MAX_QUEUED_AUDIO) {
                    val it = queue.iterator()
                    while (it.hasNext()) {
                        if (it.next().audio) {
                            it.remove()
                            queuedAudio--
                            dropped++
                            break
                        }
                    }
                }
                queuedAudio++
            } else if (queue.size >= MAX_QUEUED) {
                return
            }
            queue.addLast(item)
            notEmpty.signal()
        }
    }

    private fun readLoop() {
        val buf = ByteArray(maxOf(socket.maxReceivePacketSize, 1024))
        val assembler = FrameAssembler { b, off, len -> onContent(b, off, len) }
        try {
            val input = socket.inputStream
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) break
                rxBytes += n
                assembler.feed(buf, 0, n)
            }
        } catch (_: Exception) {
            // Enlace caído o trama corrupta: se cierra igual
        } finally {
            close()
        }
    }

    private fun onContent(b: ByteArray, off: Int, len: Int) {
        val e = session.onContent(b, off, len)
        if (e != null && e !is LinkSession.Event.Failed) lastRxAt = clock()
        when (e) {
            is LinkSession.Event.PeerHello -> {
                peer = e.nodeId
                if (events.onPeerHello(this, e.nodeId)) {
                    enqueue(Item(FrameCodec.encodeBody(Frame.Ready), audio = false, raw = false))
                } else {
                    close()
                }
            }
            LinkSession.Event.Up -> {
                ready = true
                events.onUp(this)
            }
            is LinkSession.Event.Received -> when (val f = e.frame) {
                is Frame.Ping -> send(FrameCodec.encodeBody(Frame.Pong(f.time)), audio = false)
                is Frame.Pong -> {
                    rttMs = clock().toInt() - f.time
                    pingSentAt = 0L
                }
                else -> events.onFrame(this, f)
            }
            is LinkSession.Event.Failed -> close()
            null -> Unit
        }
    }

    private fun writeLoop() {
        try {
            val out = socket.outputStream
            while (true) {
                val item = lock.withLock {
                    while (queue.isEmpty() && !closed.get()) notEmpty.await()
                    if (closed.get()) return
                    queue.removeFirst().also { if (it.audio) queuedAudio-- }
                }
                val bytes = if (item.raw) item.bytes else session.seal(item.bytes)
                out.write(bytes)
                out.flush()
                txBytes += bytes.size
                if (item.audio) sentAudio++
            }
        } catch (_: Exception) {
            close()
        }
    }

    private companion object {
        /** 100 ms de voz por enlace; más es retardo que nadie quiere oír. */
        const val MAX_QUEUED_AUDIO = 5
        const val MAX_QUEUED = 256

        /** Si ping y pong tardan más que esto, el canal va atascado y la voz nueva se descarta hasta que se vacíe. */
        const val MAX_BACKLOG_MS = 300
    }
}
