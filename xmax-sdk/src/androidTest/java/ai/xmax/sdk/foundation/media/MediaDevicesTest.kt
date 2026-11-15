//
//  MediaDevicesTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.MediaDevices
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the public discovery API describes the inputs reported by Android.
 */
@RunWith(AndroidJUnit4::class)
public class MediaDevicesTest {

    /**
     * Checks camera identifiers and facing against the camera service without starting capture.
     */
    @Test
    public fun reportsCameraIdsAndFacing(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val cameras = MediaDevices.listCameraDevices(context)

        assertEquals(manager?.cameraIdList?.toSet().orEmpty(), cameras.map { it.id }.toSet())
        cameras.forEach { camera ->
            val facing = checkNotNull(manager).getCameraCharacteristics(camera.id).get(CameraCharacteristics.LENS_FACING)
            val expected = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> CameraPosition.FRONT
                CameraCharacteristics.LENS_FACING_BACK -> CameraPosition.BACK
                else -> null
            }
            assertEquals(expected, camera.position)
            assertEquals(facing == CameraCharacteristics.LENS_FACING_EXTERNAL, camera.isExternal)
            assertTrue(camera.name.isNotBlank())
        }
    }

    /**
     * Checks audio input identifiers and types against the audio service without opening a recorder.
     */
    @Test
    public fun reportsConnectedAudioInputs(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        val inputs = manager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
        val microphones = MediaDevices.listMicrophoneDevices(context)

        assertEquals(
            inputs.associate { it.id.toString() to it.type },
            microphones.associate { it.id to it.type },
        )
        assertTrue(microphones.all { it.name.isNotBlank() })
    }
}
