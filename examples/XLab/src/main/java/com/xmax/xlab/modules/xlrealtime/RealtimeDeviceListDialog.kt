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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xmax.xlab.R
import com.xmax.xlab.xLabText

/**
 * Lists the cameras and audio inputs reported by Android when the dialog opens.
 *
 * @param onDismiss Closes the device list.
 */
@Composable
internal fun RealtimeDeviceListDialog(
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
                    DeviceListSection(
                        title = xLabText(R.string.realtime_devices_cameras),
                        isEmpty = cameras.isEmpty(),
                        emptyMessage = xLabText(R.string.realtime_devices_no_cameras),
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
                            )
                        }
                    }
                    DeviceListSection(
                        title = xLabText(R.string.realtime_devices_microphones),
                        isEmpty = microphones.isEmpty(),
                        emptyMessage = xLabText(R.string.realtime_devices_no_microphones),
                    ) {
                        microphones.forEach { microphone ->
                            DeviceListRow(
                                name = microphone.name,
                                detail = xLabText(
                                    R.string.realtime_device_audio_detail,
                                    microphoneTypeLabel(microphone.type),
                                    microphone.id,
                                ),
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
 * @param isEmpty Whether the category has any devices to display.
 * @param emptyMessage The message shown when no devices are listed.
 * @param rows The device rows.
 */
@Composable
private fun DeviceListSection(
    title: String,
    isEmpty: Boolean,
    emptyMessage: String,
    rows: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        if (isEmpty) {
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
 * Displays a device name with its identification details.
 *
 * @param name The display name.
 * @param detail The device identifier and any available type information.
 */
@Composable
private fun DeviceListRow(
    name: String,
    detail: String,
) {
    Column {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
