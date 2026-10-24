//
//  MediaFrameListener.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.VideoFrame

/**
 * Receives a video frame from local media.
 */
internal typealias MediaVideoFrameListener = (VideoFrame) -> Unit
/**
 * Receives a PCM audio frame from local media.
 */
internal typealias MediaAudioFrameListener = (AudioFrame) -> Unit
