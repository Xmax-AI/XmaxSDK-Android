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
 * 接收本地媒体输出的视频帧。
 */
internal typealias MediaVideoFrameListener = (VideoFrame) -> Unit
/**
 * 接收本地媒体输出的 PCM 音频帧。
 */
internal typealias MediaAudioFrameListener = (AudioFrame) -> Unit
