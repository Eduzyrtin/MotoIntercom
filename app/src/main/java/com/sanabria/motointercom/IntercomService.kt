package com.sanabria.motointercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sanabria.motointercom.engine.TalkMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano (micrófono + dispositivo conectado) que mantiene el intercomunicador con la pantalla apagada
 * o la app en segundo plano. La notificación dice cuántos hay en el grupo y cómo está el micro, y deja silenciarlo o
 * desconectar o abrir la app. Si está activado, también recoge el botón de reproducir/pausa del casco con una MediaSession.
 */
class IntercomService : Service() {

    private data class Info(
        val running: Boolean,
        val bluetoothOff: Boolean,
        val riders: Int,
        val talkMode: TalkMode,
        val micMuted: Boolean,
        /** Sin micro del casco (casco clásico en música primero, o sin casco): solo se escucha. */
        val noMic: Boolean,
        val groupName: String,
        val helmetButton: Boolean,
    )

    private val scope = MainScope()
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> shutdown()
            ACTION_TOGGLE_MIC -> intercom.engine.toggleMic()
        }
        // Si el sistema mata el proceso no se relanza solo: un servicio de micrófono no puede arrancar desde segundo plano
        return START_NOT_STICKY
    }

    private fun start() {
        if (started) return
        val app = intercom
        createChannel()
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null), types)
        } catch (e: RuntimeException) {
            // Android no deja abrir el micrófono desde segundo plano (casco sin asociar): se avisa para conectar con un toque
            HelmetReceiver.promptToConnect(this, app.settings.current.helmetName)
            stopSelf()
            return
        }
        started = true
        app.engine.start(app.settings.current)
        if (!app.engine.state.value.running) {
            shutdown()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motointercom:intercom")
            .apply { acquire(WAKE_LOCK_MS) }
        // Ya conectado: sobra el aviso de "casco conectado, toca para conectar"
        NotificationManagerCompat.from(this).cancel(HelmetReceiver.NOTIFICATION_ID)
        // Con la autoconexión, si se apaga el casco (fin de la ruta) y no vuelve en un rato, se cuelga
        // Solo cuenta que el casco se apague durante esta conexión, no una desconexión de antes
        if (app.helmetConnected.value == false) app.helmetConnected.value = null
        scope.launch {
            var hangUp: Job? = null
            app.helmetConnected.collect { connected ->
                hangUp?.cancel()
                if (connected == false && app.settings.current.autoConnect) {
                    hangUp = launch {
                        delay(HELMET_GONE_MS)
                        app.helmetGone.tryEmit(Unit)
                        shutdown()
                    }
                }
            }
        }
        scope.launch {
            combine(app.engine.state, app.settings.settings) { st, s ->
                Info(
                    running = st.running,
                    bluetoothOff = st.bluetoothOff,
                    riders = st.nodes.count { it.hops > 0 },
                    talkMode = s.talkMode,
                    micMuted = st.micMuted,
                    noMic = !st.helmetMic,
                    groupName = s.groupName,
                    helmetButton = s.helmetButton,
                )
            }.distinctUntilChanged().collect { info ->
                if (!info.running) {
                    shutdown()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(info))
                    helmetButton(info.helmetButton)
                }
            }
        }
    }

    private fun shutdown() {
        intercom.engine.stop()
        helmetButton(false)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.coroutineContext.cancelChildren()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        started = false
        stopSelf()
    }

    override fun onDestroy() {
        if (started) shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // --- Botón del casco ---

    /**
     * Con una MediaSession activa y reproduciendo (la app saca audio todo el rato), el botón de reproducir/pausa del
     * casco llega aquí por AVRCP en vez de a la app de música.
     */
    private fun helmetButton(enabled: Boolean) {
        if (enabled && mediaSession == null) {
            mediaSession = MediaSession(this, "MotoIntercom").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(intent: Intent): Boolean {
                        val ev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        }
                        if (ev != null && ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0 && ev.keyCode in HELMET_KEYS) {
                            intercom.engine.toggleMic()
                        }
                        return true
                    }
                })
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build(),
                )
                isActive = true
            }
        } else if (!enabled) {
            mediaSession?.release()
            mediaSession = null
        }
    }

    // --- Notificación ---

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(info: Info?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_intercom)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
        if (info == null) {
            return b.setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notif_connecting)).build()
        }
        val mic = getString(
            when {
                info.talkMode == TalkMode.MUTE -> R.string.notif_mic_off
                info.micMuted -> R.string.notif_mic_muted
                info.noMic -> R.string.notif_listen_only
                else -> R.string.notif_mic_open
            },
        )
        val text = if (info.bluetoothOff) {
            getString(R.string.status_bt_off)
        } else {
            resources.getQuantityString(R.plurals.notif_riders, info.riders, info.riders) + " · " + mic
        }
        b.setContentTitle(getString(R.string.notif_title, info.groupName)).setContentText(text)
        if (info.talkMode != TalkMode.MUTE) {
            val label = if (info.micMuted) R.string.action_unmute else R.string.action_mute
            b.addAction(0, getString(label), servicePending(ACTION_TOGGLE_MIC, 1))
        }
        b.addAction(0, getString(R.string.action_stop), servicePending(ACTION_STOP, 2))
        b.addAction(0, getString(R.string.action_open), open)
        return b.build()
    }

    private fun servicePending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, IntercomService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val ACTION_START = "com.sanabria.motointercom.START"
        private const val ACTION_STOP = "com.sanabria.motointercom.STOP"
        private const val ACTION_TOGGLE_MIC = "com.sanabria.motointercom.TOGGLE_MIC"
        private const val CHANNEL_ID = "intercom"
        private const val NOTIFICATION_ID = 1

        /** Casco apagado este tiempo con la autoconexión activa: se cuelga y se cierra la app (aguanta un corte breve). */
        private const val HELMET_GONE_MS = 10 * 1000L

        /** Un día de ruta como mucho; se suelta al desconectar. */
        private const val WAKE_LOCK_MS = 12 * 60 * 60 * 1000L

        private val HELMET_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
        )

        /** Hay que llamarlo con la app en primer plano (Android no deja arrancar el micrófono desde segundo plano). */
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, IntercomService::class.java).setAction(ACTION_START))

        fun stop(context: Context) {
            context.startService(Intent(context, IntercomService::class.java).setAction(ACTION_STOP))
        }

        fun toggleMic(context: Context) {
            context.startService(Intent(context, IntercomService::class.java).setAction(ACTION_TOGGLE_MIC))
        }
    }
}
