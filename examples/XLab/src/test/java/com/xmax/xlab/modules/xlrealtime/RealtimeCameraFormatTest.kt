//
//  RealtimeCameraFormatTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.RealtimeConnectionState
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeVideoFormat
import com.xmax.xlab.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeCameraFormatTest {

    @Test
    fun `model formats display reduced ratios instead of pixel dimensions`() {
        assertEquals(
            listOf("16:9", "9:16", "1:1", "3:4", "4:3"),
            RealtimeModel.X2_1.supportedVideoFormats.map(::cameraAspectRatioLabel),
        )
    }

    @Test
    fun `equivalent sizes share the same ratio label`() {
        assertEquals("16:9", cameraAspectRatioLabel(RealtimeVideoFormat(1280, 720, 24)))
        assertEquals("9:16", cameraAspectRatioLabel(RealtimeVideoFormat(720, 1280, 30)))
        assertEquals("1:1", cameraAspectRatioLabel(RealtimeVideoFormat(800, 800, 30)))
    }

    @Test
    fun `ready disconnected preview permits opening the ratio menu`() {
        assertNull(cameraFormatHint(RealtimeConnectionState.READY))
    }

    @Test
    fun `active connections explain that generation must stop before changing ratio`() {
        for (state in listOf(
            RealtimeConnectionState.CONNECTING,
            RealtimeConnectionState.CONNECTED,
            RealtimeConnectionState.GENERATING,
        )) {
            assertEquals(R.string.realtime_camera_format_stop_hint, cameraFormatHint(state))
        }
    }

    @Test
    fun `generation preparation also requires stopping before changing ratio`() {
        assertEquals(
            R.string.realtime_camera_format_stop_hint,
            cameraFormatHint(RealtimeConnectionState.READY, generationBusy = true),
        )
    }

    @Test
    fun `camera preparation and updates explain that preview must be ready`() {
        for (state in RealtimeConnectionState.entries.filter {
            it != RealtimeConnectionState.READY &&
                it != RealtimeConnectionState.CONNECTING &&
                it != RealtimeConnectionState.CONNECTED &&
                it != RealtimeConnectionState.GENERATING
        }) {
            assertEquals(R.string.realtime_camera_format_wait_hint, cameraFormatHint(state))
        }
        assertEquals(
            R.string.realtime_camera_format_wait_hint,
            cameraFormatHint(RealtimeConnectionState.READY, cameraSwitching = true),
        )
        assertEquals(
            R.string.realtime_camera_format_wait_hint,
            cameraFormatHint(RealtimeConnectionState.READY, cameraFormatUpdating = true),
        )
        assertEquals(
            R.string.realtime_camera_format_wait_hint,
            cameraFormatHint(RealtimeConnectionState.READY, isSuspendedForBackground = true),
        )
    }
}
