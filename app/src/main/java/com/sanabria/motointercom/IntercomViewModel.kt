package com.sanabria.motointercom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sanabria.motointercom.audio.HelmetCompat
import com.sanabria.motointercom.audio.HelmetTester
import com.sanabria.motointercom.core.crypto.Group
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.engine.EngineState
import com.sanabria.motointercom.engine.IntercomSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Puente entre la interfaz y el motor (que vive en la Application y lo arranca el servicio). */
class IntercomViewModel(app: Application) : AndroidViewModel(app) {
    private val intercom = app as MotoIntercomApp

    val settings: StateFlow<IntercomSettings> = intercom.settings.settings
    val state: StateFlow<EngineState> = intercom.engine.state

    val helmetTester = HelmetTester(app)
    private var testJob: Job? = null
    private val _helmets = MutableStateFlow(HelmetCompat(app).all())
    val helmets: StateFlow<List<HelmetCompat.Record>> = _helmets.asStateFlow()

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

    fun testHelmet(mode: AudioMode) {
        if (testJob?.isActive == true || state.value.running) return
        testJob = viewModelScope.launch { helmetTester.run(mode) }
    }

    fun answerHelmetTest(heard: Boolean) {
        helmetTester.answer(heard)
        _helmets.value = HelmetCompat(getApplication()).all()
    }

    fun cancelHelmetTest() {
        testJob?.cancel()
        helmetTester.cancel()
    }

    override fun onCleared() = cancelHelmetTest()
}
