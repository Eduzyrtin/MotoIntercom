package com.sanabria.motointercom

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sanabria.motointercom.audio.AudioIo
import com.sanabria.motointercom.audio.HelmetCompat
import com.sanabria.motointercom.audio.HelmetTester
import com.sanabria.motointercom.core.crypto.Group
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.engine.EngineState
import com.sanabria.motointercom.engine.IntercomSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Puente entre la interfaz y el motor (que vive en la Application y lo arranca el servicio). */
class IntercomViewModel(app: Application) : AndroidViewModel(app) {
    private val intercom = app as MotoIntercomApp

    val settings: StateFlow<IntercomSettings> = intercom.settings.settings
    val state: StateFlow<EngineState> = intercom.engine.state

    val helmetTester = HelmetTester(app)
    private var testJob: Job? = null
    private val compat = HelmetCompat(app)
    private val listedHelmets = HelmetCompat.listed(app)
    private val _helmets = MutableStateFlow(compat.all())
    val helmets: StateFlow<List<HelmetCompat.Record>> = _helmets.asStateFlow()

    /** Cascos Bluetooth conectados ahora mismo (se actualiza al conectar o desconectar uno). */
    private val headsets = MutableStateFlow(AudioIo.bluetoothHeadsets(app))
    private val am = app.getSystemService(AudioManager::class.java)
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            headsets.value = AudioIo.bluetoothHeadsets(app)
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            headsets.value = AudioIo.bluetoothHeadsets(app)
        }
    }

    /** Casco conectado con un modo recomendado distinto del que está puesto. */
    data class HelmetHint(val headset: String, val recommendation: HelmetCompat.Recommendation)

    val helmetHint: StateFlow<HelmetHint?> = combine(headsets, settings, helmets) { hs, s, _ ->
        hs.firstNotNullOfOrNull { h ->
            compat.recommend(h, listedHelmets)?.takeIf { it.mode != s.audioMode }?.let { HelmetHint(h, it) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    fun update(change: (IntercomSettings) -> IntercomSettings) = intercom.updateSettings(change)

    fun start() = IntercomService.start(getApplication())

    fun stop() = IntercomService.stop(getApplication())

    fun setPtt(pressed: Boolean) {
        intercom.engine.pttPressed = pressed
    }

    fun toggleMic() = intercom.engine.toggleMic()

    fun setMasterVolume(level: Float) = intercom.engine.setMasterVolume(level)

    fun setVoiceVolume(level: Float) = intercom.engine.setVoiceVolume(level)

    fun setMediaVolume(level: Float) = intercom.engine.setMediaVolume(level)

    fun mediaKey(keyCode: Int) = intercom.engine.mediaKey(keyCode)

    fun newGroup(name: String) {
        intercom.settings.newGroup(name)
    }

    fun joinGroup(group: Group) {
        intercom.settings.joinGroup(group)
    }

    fun forgetLinkLimit() = update { it.copy(learnedLinkLimit = 0) }

    /** Resultados de las pruebas de casco de este móvil, en texto para compartir. */
    fun exportHelmets(): String = compat.export("${Build.MANUFACTURER} ${Build.MODEL}")

    fun testHelmet(mode: AudioMode) {
        if (testJob?.isActive == true || state.value.running) return
        testJob = viewModelScope.launch { helmetTester.run(mode) }
    }

    fun answerHelmetTest(heard: Boolean) {
        helmetTester.answer(heard)
        _helmets.value = compat.all()
    }

    fun cancelHelmetTest() {
        testJob?.cancel()
        helmetTester.cancel()
    }

    override fun onCleared() {
        am.unregisterAudioDeviceCallback(deviceCallback)
        cancelHelmetTest()
    }
}
