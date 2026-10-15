package com.sanabria.motointercom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sanabria.motointercom.R
import com.sanabria.motointercom.audio.AudioIo
import com.sanabria.motointercom.audio.HelmetTester
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.engine.IntercomSettings
import kotlin.math.roundToInt

/**
 * Asistente de compatibilidad: prueba el casco en modo Casco (HFP) y Mezcla (A2DP), grabando 3 s y reproduciéndolos,
 * y recomienda un modo.
 */
@Composable
fun HelmetTestScreen(test: HelmetTester.State, settings: IntercomSettings, actions: ScreenActions, back: () -> Unit) {
    val context = LocalContext.current
    var headsets by remember { mutableStateOf(emptyList<String>()) }
    LifecycleResumeEffect(Unit) {
        headsets = AudioIo.bluetoothHeadsets(context)
        onPauseOrDispose { }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    actions.cancelHelmetTest()
                    back()
                }) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                }
                Text(stringResource(R.string.test_title), style = MaterialTheme.typography.headlineSmall)
            }
            Text(stringResource(R.string.test_intro), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (headsets.isEmpty()) stringResource(R.string.test_no_devices) else stringResource(R.string.test_devices, headsets.joinToString()),
                style = MaterialTheme.typography.bodyMedium,
                color = if (headsets.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            for (mode in AudioMode.entries) ModeTest(mode, test, actions)
            Recommendation(test, settings, actions)
        }
    }
}

@Composable
private fun ModeTest(mode: AudioMode, test: HelmetTester.State, actions: ScreenActions) {
    val active = test.mode == mode && test.phase != HelmetTester.Phase.IDLE
    val busy = test.phase != HelmetTester.Phase.IDLE
    Section(stringResource(audioModeLabel(mode))) {
        when {
            active && test.phase == HelmetTester.Phase.ASK -> {
                RouteLine(test.routeMs, test.route, test.peakDb)
                Text(stringResource(R.string.test_ask), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.answerHelmetTest(true) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.answer_yes)) }
                    OutlinedButton(onClick = { actions.answerHelmetTest(false) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.answer_no))
                    }
                }
            }
            active -> {
                Text(
                    when (test.phase) {
                        HelmetTester.Phase.ROUTING -> stringResource(R.string.test_routing)
                        HelmetTester.Phase.RECORDING -> stringResource(R.string.test_recording, test.secondsLeft)
                        else -> stringResource(R.string.test_playing, test.secondsLeft)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { ((test.levelDb + 60f) / 60f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )
                OutlinedButton(onClick = actions.cancelHelmetTest) { Text(stringResource(R.string.action_cancel)) }
            }
            else -> {
                test.results[mode]?.let { r ->
                    Text(
                        stringResource(if (r.heard) R.string.test_result_ok else R.string.test_result_bad),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (r.heard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    RouteLine(r.routeMs, r.route, r.peakDb)
                }
                Button(onClick = { actions.testHelmet(mode) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.test_run))
                }
            }
        }
    }
}

@Composable
private fun RouteLine(routeMs: Long?, route: AudioIo.Route?, peakDb: Float) {
    Text(
        if (routeMs != null) stringResource(R.string.test_route_line, routeMs.toInt()) else stringResource(R.string.test_route_timeout),
        style = MaterialTheme.typography.bodySmall,
    )
    route?.let {
        Text(stringResource(R.string.route_input, deviceLabel(it.inputType, it.inputName)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.route_output, deviceLabel(it.outputType, it.outputName)), style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.test_level, peakDb.roundToInt()), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Recommendation(test: HelmetTester.State, settings: IntercomSettings, actions: ScreenActions) {
    val helmet = test.results[AudioMode.HELMET]?.heard
    val mix = test.results[AudioMode.MIX]?.heard
    if (helmet == null && mix == null) return
    val recommended = when {
        helmet == true -> AudioMode.HELMET
        mix == true -> AudioMode.MIX
        else -> null
    }
    Section(stringResource(R.string.test_recommendation)) {
        Text(
            stringResource(
                when (recommended) {
                    AudioMode.HELMET -> R.string.test_recommend_helmet
                    AudioMode.MIX -> R.string.test_recommend_mix
                    null -> R.string.test_recommend_none
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (helmet == true && mix == true) Text(stringResource(R.string.test_both_hint), style = MaterialTheme.typography.bodySmall)
        if (recommended != null && recommended != settings.audioMode) {
            Button(onClick = { actions.update { it.copy(audioMode = recommended) } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.test_use_mode))
            }
        }
    }
}
