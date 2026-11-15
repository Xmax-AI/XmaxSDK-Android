//
//  RealtimeDeviceListDialog.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.CameraDevice
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.MediaDevices
import ai.xmax.sdk.MicrophoneDevice
import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xmax.xlab.R
import com.xmax.xlab.xLabText

/**
 * Shows available inputs and lets the user switch the camera stream's selected devices.
 *
 * @param cameraDeviceId The active camera ID.
 * @param microphoneDeviceId The selected audio input ID, or null for the Android default input.
 * @param canSwitch Whether the camera stream can switch devices.
 * @param isGenerating Whether generation must be stopped before switching.
 * @param onCameraSelected Switches to the selected camera.
 * @param onMicrophoneSelected Switches to the selected audio input or the Android default input.
 * @param onDismiss Closes the device list.
 */
@Composable
internal fun RealtimeDeviceListDialog(
    cameraDeviceId: String?,
    microphoneDeviceId: String?,
    canSwitch: Boolean,
    isGenerating: Boolean,
    onCameraSelected: (String) -> Unit,
    onMicrophoneSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var devices by remember(context) { mutableStateOf<Pair<List<CameraDevice>, List<MicrophoneDevice>>?>(null) }
    LaunchedEffect(context) {
        val cameras = MediaDevices.listCameraDevices(context)
        val microphones = MediaDevices.listMicrophoneDevices(context)
        devices = cameras to microphones
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(xLabText(R.string.realtime_devices_title)) },
        text = {
            val snapshot = devices
            if (snapshot == null) {
                Text(xLabText(R.string.realtime_devices_loading))
            } else {
                val (cameras, microphones) = snapshot
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (isGenerating) {
                        Text(xLabText(R.string.realtime_devices_stop_generation))
                    }
                    DeviceListSection(
                        title = xLabText(R.string.realtime_devices_cameras),
                        emptyMessage = if (cameras.isEmpty()) xLabText(R.string.realtime_devices_no_cameras) else null,
                    ) {
                        cameras.forEach { camera ->
                            val label = when {
                                camera.isExternal -> R.string.realtime_device_camera_external
                                camera.position == CameraPosition.FRONT -> R.string.realtime_device_camera_front
                                camera.position == CameraPosition.BACK -> R.string.realtime_device_camera_rear
                                else -> R.string.realtime_device_camera
                            }
                            DeviceListRow(
                                name = xLabText(label),
                                detail = xLabText(
                                    R.string.realtime_device_id,
                                    camera.id,
                                ),
                                selected = camera.id == cameraDeviceId,
                                enabled = canSwitch,
                                onSelect = { onCameraSelected(camera.id) },
                            )
                        }
                    }
                    DeviceListSection(
                        title = xLabText(R.string.realtime_devices_microphones),
                        emptyMessage = null,
                    ) {
                        DeviceListRow(
                            name = xLabText(R.string.realtime_devices_system_default),
                            detail = null,
                            selected = microphoneDeviceId == null,
                            enabled = canSwitch,
                            onSelect = { onMicrophoneSelected(null) },
                        )
                        if (microphones.isEmpty()) {
                            Text(xLabText(R.string.realtime_devices_no_microphones))
                        }
                        microphones.forEach { microphone ->
                            DeviceListRow(
                                name = microphone.name,
                                detail = xLabText(
                                    R.string.realtime_device_audio_detail,
                                    microphoneTypeLabel(microphone.type),
                                    microphone.id,
                                ),
                                selected = microphone.id == microphoneDeviceId,
                                enabled = canSwitch,
                                onSelect = { onMicrophoneSelected(microphone.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(xLabText(R.string.realtime_devices_close))
            }
        },
    )
}

/**
 * Shows a device category and its current entries.
 *
 * @param title The device category.
 * @param emptyMessage The message to display in place of the rows, if any.
 * @param rows The device rows.
 */
@Composable
private fun DeviceListSection(
    title: String,
    emptyMessage: String?,
    rows: @Composable () -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        if (emptyMessage != null) {
            Text(emptyMessage)
        } else {
            rows()
        }
    }
}

/**
 * Returns a localized label for an Android audio input type.
 *
 * @param type The Android audio device type.
 */
@Composable
private fun microphoneTypeLabel(
    type: Int,
): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> xLabText(R.string.realtime_device_audio_built_in)
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET -> xLabText(R.string.realtime_device_audio_usb)
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET -> xLabText(R.string.realtime_device_audio_bluetooth)
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> xLabText(R.string.realtime_device_audio_wired)
    else -> xLabText(
        R.string.realtime_device_audio_other,
        type,
    )
}

/**
 * Displays a selectable device with its identification details.
 *
 * @param name The display name.
 * @param detail The device identifier and any available type information.
 * @param selected Whether this input is currently selected.
 * @param enabled Whether device selection is allowed.
 * @param onSelect Selects the input.
 */
@Composable
private fun DeviceListRow(
    name: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { if (!selected) onSelect() },
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
