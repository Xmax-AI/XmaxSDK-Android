//
//  RoomEvent.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.runtime.RuntimeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds structured event messages for RTC room transport.
 */
internal object RoomEvent {

    /**
     * Builds a realtime generation start signal.
     *
     * @param userId The room's business user identifier.
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param minimumBitrate The minimum encoder bitrate, in kbps.
     * @param maximumBitrate The maximum encoder bitrate, in kbps.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param targetVideoFormat The downlink output format.
     * @param paddingContain Whether the server fits input and removes padding from output; defaults to false.
     * @return The generation start JSON signal.
     */
    fun start(
        userId: String,
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        minimumBitrate: Int,
        maximumBitrate: Int,
        faceRestoration: Boolean = false,
        targetVideoFormat: RealtimeVideoFormat? = null,
        paddingContain: Boolean = false,
    ): String = encode(
        event = "start",
        userId = userId,
        taskId = taskId,
        params = generationParameters(
            videoFormat,
            context,
            faceRestoration,
        ).apply {
            if (paddingContain) {
                if (targetVideoFormat == null || context.referenceVideo != null) {
                    throw XmaxError(
                        XmaxErrorCode.INVALID_CONFIGURATION,
                        "Contain padding requires a target size and a realtime frame input",
                    )
                }

                put(
                    "padding",
                    "contain",
                )
            }

            put(
                "mirror",
                false,
            )
            put(
                "min_bitrate",
                minimumBitrate,
            )
            put(
                "max_bitrate",
                maximumBitrate,
            )
            val target = targetVideoFormat ?: videoFormat.takeIf { context.referenceVideo != null }
            target?.let { put(
                "target_size",
                JSONArray().put(it.width).put(it.height),
            ) }
            context.referenceVideo?.let {
                put(
                    "ref_video_path",
                    it.path,
                )
                put(
                    "sample_method",
                    it.sampleMethod.value,
                )
            }
        },
    )

    /**
     * Encodes output dimension and server bitrate updates, with bitrate in kbps.
     *
     * @param userId The room's business user identifier.
     * @param taskId The identifier associating the generation request with its result stream.
     * @param targetVideoFormat The downlink output format.
     * @param minimumBitrate The minimum encoder bitrate, in kbps.
     * @param maximumBitrate The maximum encoder bitrate, in kbps.
     * @return The downlink dimension and bitrate JSON signal.
     */
    fun changeTargetSize(
        userId: String,
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        minimumBitrate: Int,
        maximumBitrate: Int,
    ): String = encode(
        event = "change_target_size",
        userId = userId,
        taskId = taskId,
        params = JSONObject()
            .put(
                "target_size",
                JSONArray().put(targetVideoFormat.width).put(targetVideoFormat.height),
            )
            .put(
                "min_bitrate",
                minimumBitrate,
            )
            .put(
                "max_bitrate",
                maximumBitrate,
            ),
    )

    /**
     * Builds a generation condition update signal.
     *
     * @param userId The room's business user identifier.
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param paddingContain Whether the server fits input and removes padding from output; defaults to false.
     * @return The generation condition JSON signal.
     */
    fun changeCondition(
        userId: String,
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        paddingContain: Boolean = false,
    ): String = encode(
        event = "change_condition",
        userId = userId,
        taskId = taskId,
        params = generationParameters(
            videoFormat,
            context,
            faceRestoration,
        ).apply {
            if (paddingContain) put(
                "padding",
                "contain",
            )
        },
    )

    /**
     * Builds a signal stopping the specified task.
     *
     * @param userId The room's business user identifier.
     * @param taskId The identifier associating the generation request with its result stream.
     * @return The generation stop JSON signal.
     */
    fun stop(
        userId: String,
        taskId: String,
    ): String = encode(
        event = "stop",
        userId = userId,
        taskId = taskId,
    )

    /**
     * Builds a trajectory signal.
     *
     * @param userId The room's business user identifier.
     * @param taskId The identifier associating the generation request with its result stream.
     * @param points Normalized media trajectory points from 0 to 1.
     * @return The trajectory JSON signal.
     */
    fun tracks(
        userId: String,
        taskId: String,
        points: List<RealtimePoint>,
    ): String = encode(
        event = "tracks",
        userId = userId,
        taskId = taskId,
        tracks = JSONArray().apply {
            points.forEach { point ->
                put(JSONArray().put(point.x).put(point.y))
            }
        },
    )

    /**
     * Builds a room heartbeat signal.
     *
     * @param userId The room's business user identifier.
     * @return The room heartbeat JSON signal.
     */
    fun heartbeat(
        userId: String,
    ): String = encode(
        event = "heartbeat",
        userId = userId,
    )

    /**
     * Builds generation dimensions, prompt, reference media, and face restoration parameters.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`.
     * @return JSON for the current operation.
     */
    private fun generationParameters(
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
    ): JSONObject = JSONObject()
        .put(
            "model",
            "default",
        )
        .put(
            "size",
            JSONArray().put(videoFormat.width).put(videoFormat.height),
        )
        .put(
            "prompt",
            context.prompt,
        )
        .put(
            "face_replace",
            faceRestoration,
        )
        .apply {
            context.referencePath?.let { put(
                "ref_image_path",
                it,
            ) }
        }

    /**
     * Builds a JSON signal containing the event, user, task, and parameters.
     *
     * @param event The business signaling event name.
     * @param userId The room's business user identifier.
     * @param taskId The identifier associating the generation request with its result stream.
     * @param params The business parameters, or null to omit the parameter object.
     * @param tracks The trajectory coordinate array, or null to omit trajectories.
     * @return The encoded JSON signal.
     */
    private fun encode(
        event: String,
        userId: String,
        taskId: String? = null,
        params: JSONObject? = null,
        tracks: JSONArray? = null,
    ): String = try {
        JSONObject()
            .put(
                "event",
                event,
            )
            .put(
                "user_id",
                userId,
            )
            .put(
                "runtime",
                RuntimeInfo.current.toJson(),
            )
            .apply {
                taskId?.let { put(
                    "uid",
                    it,
                ); put(
                    "session_uid",
                    it,
                ) }
                params?.let { put(
                    "params",
                    it,
                ) }
                tracks?.let { put(
                    "tracks",
                    it,
                ) }
            }
            .toString()
    } catch (
        error: Throwable,
    ) {
        throw XmaxError(
            code = XmaxErrorCode.INTERNAL_ERROR,
            message = "Failed to encode RTC room event",
            cause = error,
        )
    }
}
