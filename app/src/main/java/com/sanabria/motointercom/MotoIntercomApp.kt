package com.sanabria.motointercom

import android.app.Application
import android.content.Context
import com.sanabria.motointercom.engine.IntercomEngine
import com.sanabria.motointercom.engine.IntercomSettings
import com.sanabria.motointercom.engine.SettingsRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Dueña del motor y de los ajustes durante toda la vida del proceso: la interfaz y el servicio en primer plano usan los
 * mismos. Así cerrar la actividad no corta la conversación.
 */
class MotoIntercomApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: IntercomEngine
        private set

    /** El casco elegido para la autoconexión está conectado al móvil (null = no se sabe todavía). */
    val helmetConnected = MutableStateFlow<Boolean?>(null)

    /** El casco se ha ido y el servicio ha colgado: la actividad abierta se cierra. */
    val helmetGone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        engine = IntercomEngine(this)
        engine.loadVolumes(settings.current.voiceLevel, settings.current.mediaLevel)
        // Los volúmenes también cambian con los botones del móvil o del casco: se guardan igual
        scope.launch {
            engine.state.map { it.volumes }.distinctUntilChanged().collect { v ->
                settings.update { it.copy(voiceLevel = v.voice, mediaLevel = v.media) }
            }
        }
        // El límite de enlaces que aprende la malla se guarda para la próxima conexión
        scope.launch {
            engine.state.map { it.mesh?.learnedLimit }.filterNotNull().distinctUntilChanged().collect { l ->
                settings.update { it.copy(learnedLinkLimit = l) }
            }
        }
    }

    fun updateSettings(change: (IntercomSettings) -> IntercomSettings) = engine.apply(settings.update(change))
}

val Context.intercom: MotoIntercomApp get() = applicationContext as MotoIntercomApp
