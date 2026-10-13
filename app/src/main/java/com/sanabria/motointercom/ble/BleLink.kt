package com.sanabria.motointercom.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
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
 * Un enlace L2CAP con otro móvil: un hilo lee y reconstruye tramas, otro escribe desde una cola. La voz que no sale a
 * tiempo se descarta por la cabeza (mejor perder 20 ms viejos que acumular retardo); el control nunca se descarta.
 */
class BleLink(
    private val socket: BluetoothSocket,
    val initiator: Boolean,
    val gatt: GattHandle,
    private val clock: () -> Long,
    private val events: Events,
) {
    interface Events {
        fun onHello(link: BleLink, hello: Frame.Hello)
        fun onFrame(link: BleLink, frame: Frame)
        fun onClosed(link: BleLink)
    }

    private class Item(val bytes: ByteArray, val audio: Boolean)

    val createdAt = clock()

    @Volatile var peer = 0
    @Volatile var peerName = ""
    @Volatile var helloDone = false

    @Volatile var rttMs: Int? = null
        private set
    @Volatile var txBytes = 0L
        private set
    @Volatile var rxBytes = 0L
        private set
    @Volatile var dropped = 0L
        private set

    private val closed = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<Item>()
    private var queuedAudio = 0

    val queued: Int get() = lock.withLock { queue.size }

    fun start(hello: Frame.Hello) {
        send(FrameCodec.encode(hello), audio = false)
        thread(name = "l2cap-read") { readLoop() }
        thread(name = "l2cap-write") { writeLoop() }
    }

    fun send(bytes: ByteArray, audio: Boolean) {
        lock.withLock {
            if (closed.get()) return
            if (audio) {
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
            queue.addLast(Item(bytes, audio))
            notEmpty.signal()
        }
    }

    fun ping() = send(FrameCodec.encode(Frame.Ping(clock().toInt())), audio = false)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        lock.withLock { notEmpty.signalAll() }
        runCatching { socket.close() }
        gatt.close()
        events.onClosed(this)
    }

    private fun readLoop() {
        val buf = ByteArray(maxOf(socket.maxReceivePacketSize, 1024))
        val assembler = FrameAssembler { f ->
            when (f) {
                is Frame.Hello -> events.onHello(this, f)
                is Frame.Ping -> send(FrameCodec.encode(Frame.Pong(f.time)), audio = false)
                is Frame.Pong -> rttMs = clock().toInt() - f.time
                else -> if (helloDone) events.onFrame(this, f)
            }
        }
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

    private fun writeLoop() {
        try {
            val out = socket.outputStream
            while (true) {
                val item = lock.withLock {
                    while (queue.isEmpty() && !closed.get()) notEmpty.await()
                    if (closed.get()) return
                    queue.removeFirst().also { if (it.audio) queuedAudio-- }
                }
                out.write(item.bytes)
                out.flush()
                txBytes += item.bytes.size
            }
        } catch (_: Exception) {
            close()
        }
    }

    private companion object {
        /** 100 ms de voz por enlace; más es retardo que nadie quiere oír. */
        const val MAX_QUEUED_AUDIO = 5
        const val MAX_QUEUED = 256
    }
}
