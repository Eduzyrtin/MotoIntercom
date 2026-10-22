package com.sanabria.motointercom

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Autoconexión: el casco elegido se conecta (o se desconecta) del móvil por Bluetooth.
 *
 * Al conectarse, si el intercomunicador no está en marcha, el servicio conecta con el grupo por defecto sin abrir la
 * app: solo sale su notificación, con las opciones de desconectar o abrir la app. Android solo deja arrancar el micrófono
 * desde segundo plano si el casco está asociado como dispositivo complementario (se hace al elegirlo en Ajustes); por si
 * no se puede (sin permisos o sin asociación), queda una notificación para conectar con un toque.
 * Al desconectarse, el servicio cuelga y la app se cierra si el casco no vuelve en unos segundos.
 */
class HelmetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.intercom
        val s = app.settings.current
        if (!s.autoConnect || s.helmetAddress.isEmpty()) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        if (!device.address.equals(s.helmetAddress, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                app.helmetConnected.value = true
                if (!app.engine.state.value.running) connect(context, s.helmetName)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> app.helmetConnected.value = false
        }
    }

    companion object {
        const val ACTION_AUTOCONNECT = "com.sanabria.motointercom.AUTOCONNECT"
        const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "helmet"

        fun autoConnectIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_AUTOCONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun connect(context: Context, helmet: String) {
            val app = context.intercom
            app.settings.activate(app.settings.current.defaultGroup)
            val started = hasPermissions(context) && runCatching { IntercomService.start(context) }.isSuccess
            if (!started) promptToConnect(context, helmet)
        }

        private fun hasPermissions(context: Context): Boolean {
            val needed = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                } else {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            return needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }

        /** Aviso de reserva: conecta con un toque (abre la app, que pide lo que falte). Se borra al conectar. */
        fun promptToConnect(context: Context, helmet: String) {
            val nm = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (!nm.areNotificationsEnabled()) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_helmet_channel), NotificationManager.IMPORTANCE_HIGH),
            )
            val tap = PendingIntent.getActivity(
                context, 10, autoConnectIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_intercom)
                .setContentTitle(context.getString(R.string.notif_helmet_title))
                .setContentText(context.getString(R.string.notif_helmet_text, helmet))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }
    }
}
