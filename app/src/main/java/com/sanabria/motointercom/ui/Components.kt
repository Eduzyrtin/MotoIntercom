@file:OptIn(ExperimentalMaterial3Api::class)

package com.sanabria.motointercom.ui

import android.bluetooth.BluetoothDevice
import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sanabria.motointercom.R
import com.sanabria.motointercom.engine.IntercomSettings

/** Lo que la interfaz puede pedir al motor. */
class ScreenActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val update: ((IntercomSettings) -> IntercomSettings) -> Unit,
    val ptt: (Boolean) -> Unit,
    val masterVolume: (Float) -> Unit,
    val voiceVolume: (Float) -> Unit,
    val mediaVolume: (Float) -> Unit,
    val mediaKey: (Int) -> Unit,
)

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun <T> Choice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) {
                Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
fun deviceLabel(type: Int?, name: String): String {
    if (type == null) return stringResource(R.string.dev_none)
    val res = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.dev_builtin_mic
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.string.dev_sco
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> R.string.dev_a2dp
        AudioDeviceInfo.TYPE_BLE_HEADSET -> R.string.dev_ble_headset
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.string.dev_wired
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> R.string.dev_usb
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.dev_speaker
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.dev_earpiece
        else -> null
    }
    val label = res?.let { stringResource(it) } ?: stringResource(R.string.dev_other, type)
    return if (name.isBlank()) label else "$label · $name"
}

/** Nombres técnicos del estándar, iguales en todos los idiomas. */
fun phyName(phy: Int) = when (phy) {
    BluetoothDevice.PHY_LE_1M -> "1M"
    BluetoothDevice.PHY_LE_2M -> "2M"
    BluetoothDevice.PHY_LE_CODED -> "Coded"
    else -> "?"
}

fun percent(level: Float) = (level * 100).toInt()
