package net.triton.frigateviewer.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi

@Suppress("NewApi")
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DeviceCapabilitiesSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val sampleRate =
        remember {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        }
    val framesPerBuffer =
        remember {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        }
    val isLowLatency =
        remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        }
    val isProAudio =
        remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
        }
    val maxMemMb = remember { Runtime.getRuntime().maxMemory() / 1024 / 1024 }
    val freeMemMb = remember { Runtime.getRuntime().freeMemory() / 1024 / 1024 }
    val outputDevices = remember { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
    val h265ok =
        remember {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
                !codec.isEncoder && codec.supportedTypes.any { it.contains("hevc", ignoreCase = true) }
            }
        }

    val capabilityItems =
        listOf(
            "Sample Rate" to "${sampleRate / 1000} kHz ($framesPerBuffer f/buf)",
            "Outputs" to "${outputDevices.size} detected",
            "Low Latency" to if (isLowLatency) "Yes (Pro Audio: $isProAudio)" else "No",
            "JVM Memory" to "${freeMemMb}MB free / ${maxMemMb}MB max",
        )

    SettingsSubPageScaffold(title = "Device Capabilities", onBack = onBack) {
        SettingsSectionHeader("Audio")

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            capabilityItems.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) ->
                        CapabilityCard(label, value, Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SettingsSectionHeader("Detected outputs")

        SettingsCard {
            Column {
                outputDevices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(device.productName.toString()) },
                        supportingContent = { Text(device.typeLabel()) },
                        leadingContent = {
                            Icon(
                                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                                    Icons.Filled.Headphones
                                } else {
                                    Icons.Filled.Speaker
                                },
                                contentDescription = null,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }

        SettingsSectionHeader("Codec support")

        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Media3 ${MediaLibraryInfo.VERSION}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "H264: supported | H265: ${if (h265ok) "supported" else "not supported"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun AudioDeviceInfo.typeLabel() =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "Other"
    }

@Composable
private fun CapabilityCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
