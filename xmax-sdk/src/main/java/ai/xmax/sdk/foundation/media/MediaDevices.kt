//
//  MediaDevices.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Queries local media devices independently of credentials, capture, and realtime connections.
 */
public object MediaDevices {

    /**
     * Lists the cameras exposed by Camera2 without requesting camera permission.
     *
     * Each call takes a fresh snapshot on an I/O dispatcher. Cameras in use can appear in
     * the list. Entries whose characteristics cannot be read are omitted.
     *
     * @param context The Android context used to access the camera service.
     * @return The cameras reported by Android, or an empty list if the list cannot be read.
     */
    public suspend fun listCameraDevices(
        context: Context,
    ): List<CameraDevice> = withContext(Dispatchers.IO) {
        val manager = context.applicationContext.getSystemService(CameraManager::class.java)
            ?: return@withContext emptyList()

        val ids = try {
            manager.cameraIdList
        } catch (_: CameraAccessException) {
            return@withContext emptyList()
        } catch (_: SecurityException) {
            return@withContext emptyList()
        }

        ids.mapNotNull { id ->
            cameraDevice(
                manager,
                id,
            )
        }
    }

    /**
     * Lists connected audio inputs without requesting recording permission.
     *
     * Each call takes a fresh snapshot on an I/O dispatcher. The list can include virtual inputs.
     *
     * @param context The Android context used to access the audio service.
     * @return The audio inputs reported by Android, or an empty list if the list cannot be read.
     */
    public suspend fun listMicrophoneDevices(
        context: Context,
    ): List<MicrophoneDevice> = withContext(Dispatchers.IO) {
        val manager = context.applicationContext.getSystemService(AudioManager::class.java)
            ?: return@withContext emptyList()

        val inputs = try {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (_: SecurityException) {
            return@withContext emptyList()
        }

        inputs.map { input ->
            MicrophoneDevice(
                id = input.id.toString(),
                name = input.productName?.toString().orEmpty().ifBlank { "Audio input" },
                type = input.type,
            )
        }
    }

    /**
     * Reads a camera's facing and assigns a display name for that facing.
     *
     * @param manager The Android camera service.
     * @param id The Camera2 device identifier.
     * @return The device description, or null if its characteristics cannot be read.
     */
    private fun cameraDevice(
        manager: CameraManager,
        id: String,
    ): CameraDevice? {
        val facing = try {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        } catch (_: CameraAccessException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SecurityException) {
            return null
        }

        val position = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> CameraPosition.FRONT
            CameraCharacteristics.LENS_FACING_BACK -> CameraPosition.BACK
            else -> null
        }
        val isExternal = facing == CameraCharacteristics.LENS_FACING_EXTERNAL
        val name = when {
            position == CameraPosition.FRONT -> "Front camera"
            position == CameraPosition.BACK -> "Rear camera"
            isExternal -> "External camera"
            else -> "Camera"
        }
        return CameraDevice(
            id = id,
            name = name,
            position = position,
            isExternal = isExternal,
        )
    }
}
