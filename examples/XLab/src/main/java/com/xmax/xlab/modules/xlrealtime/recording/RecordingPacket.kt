//
//  RecordingPacket.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime.recording

import ai.xmax.sdk.RealtimeAudioFrame
import java.nio.ByteBuffer
import java.util.UUID

internal sealed interface RecordingPacket {
    val streamID: UUID
    val byteCount: Long

    data class Video(override val streamID: UUID, val frame: RecordingFrame) : RecordingPacket {
        override val byteCount get() = frame.y.remaining().toLong() + frame.u.remaining() + frame.v.remaining()
    }

    data class Audio(override val streamID: UUID, val frame: RecordingAudioFrame) : RecordingPacket {
        override val byteCount get() = frame.data.remaining().toLong()
    }
}

internal data class RecordingAudioFormat(val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate in 8_000..192_000 && channels in 1..2)
    }
}

internal data class RecordingAudioFrame(
    val format: RecordingAudioFormat,
    val samplesPerChannel: Int,
    val presentationTimeUs: Long,
    val durationUs: Long,
    val data: ByteBuffer,
) {
    init {
        require(samplesPerChannel > 0 && data.remaining().toLong() == samplesPerChannel.toLong() * format.channels * 2)
        require(durationUs > 0)
    }

    companion object {
        fun from(frame: RealtimeAudioFrame) = RecordingAudioFrame(
            RecordingAudioFormat(frame.sampleRate, frame.channels), frame.samplesPerChannel,
            frame.presentationTimeUs, frame.durationUs, frame.data,
        )
    }
}

internal data class RecordingTrack(val file: java.io.File, val originUs: Long)
