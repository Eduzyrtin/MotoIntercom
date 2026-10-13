package com.sanabria.motointercom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sanabria.motointercom.engine.EngineState
import com.sanabria.motointercom.engine.IntercomEngine
import com.sanabria.motointercom.engine.IntercomSettings
import com.sanabria.motointercom.engine.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Dueño del motor: sobrevive a los giros de pantalla. El bloque 2 lo pasa a un servicio en primer plano. */
class IntercomViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val engine = IntercomEngine(app)
    private val _settings = MutableStateFlow(store.load())

    val settings: StateFlow<IntercomSettings> = _settings.asStateFlow()
    val state: StateFlow<EngineState> = engine.state

    init {
        engine.loadVolumes(_settings.value.voiceLevel, _settings.value.mediaLevel)
        // Los volúmenes también cambian con los botones del móvil o del casco: se guardan igual
        viewModelScope.launch {
            engine.state.map { it.volumes }.distinctUntilChanged().collect { v ->
                val s = _settings.value
                if (s.voiceLevel != v.voice || s.mediaLevel != v.media) {
                    val updated = s.copy(voiceLevel = v.voice, mediaLevel = v.media)
                    _settings.value = updated
                    store.save(updated)
                }
            }
        }
    }

    fun update(change: (IntercomSettings) -> IntercomSettings) {
        val s = change(_settings.value)
        _settings.value = s
        store.save(s)
        engine.apply(s)
    }

    fun start() = engine.start(_settings.value)

    fun stop() = engine.stop()

    fun setPtt(pressed: Boolean) {
        engine.pttPressed = pressed
    }

    fun setMasterVolume(level: Float) = engine.setMasterVolume(level)

    fun setVoiceVolume(level: Float) = engine.setVoiceVolume(level)

    fun setMediaVolume(level: Float) = engine.setMediaVolume(level)

    fun mediaKey(keyCode: Int) = engine.mediaKey(keyCode)

    override fun onCleared() = engine.stop()
}
