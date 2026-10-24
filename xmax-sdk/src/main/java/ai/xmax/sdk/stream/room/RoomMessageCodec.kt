//
//  RoomMessageCodec.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import java.util.UUID
import org.json.JSONObject

/**
 * Fragments `__agora_chunk__` messages within the JSON-escaped UTF-8 packet budget.
 */
internal class RoomMessageCodec {

    /**
     * The fragments and integrity metadata for one partially received message.
     */
    private data class Record(
        /**
         * The total fragment count.
         */
        val count: Int,

        /**
         * Received fragments indexed by sequence number.
         */
        val parts: MutableMap<Int, String> = mutableMapOf(),

        /**
         * The cumulative received payload size in bytes; defaults to 0.
         */
        var bytes: Int = 0,
    )

    /**
     * The reassembly cache keyed by sender and message identifier.
     */
    private val records = LinkedHashMap<Pair<String, String>, Record>()

    /**
     * Encodes a signal as ordered fragments within the RTC packet size limit.
     *
     * @param message The message to send or log.
     * @return The signal fragments in transmission order.
     */
    fun encode(
        message: String,
    ): List<String> {
        require(message.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) { "Room message is too large" }
        if (message.toByteArray(Charsets.UTF_8).size <= 800) return listOf(message)

        val pieces = mutableListOf<String>()
        val part = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < message.length) {
            val codePoint = message.codePointAt(offset)
            val character = String(Character.toChars(codePoint))
            val cost = JSONObject.quote(character).toByteArray(Charsets.UTF_8).size - 2
            if (bytes + cost > 600 && part.isNotEmpty()) {
                pieces += part.toString()
                part.setLength(0)
                bytes = 0
            }

            part.append(character)
            bytes += cost
            offset += Character.charCount(codePoint)
        }

        if (part.isNotEmpty()) pieces += part.toString()
        val id = UUID.randomUUID().toString().replace(
            "-",
            "",
        )

        return pieces.mapIndexed { index, data ->
            JSONObject().put(
                "event",
                EVENT,
            ).put(
                "eventId",
                id,
            ).put(
                "index",
                index,
            )
                .put(
                    "count",
                    pieces.size,
                ).put(
                    "data",
                    data,
                ).toString()
        }
    }

    /**
     * Reassembles a sender's fragmented signal, returning null until complete.
     *
     * @param sender The user sending the fragments.
     * @param message The message to send or log.
     * @return The complete message, or null while fragments remain missing.
     */
    @Synchronized
    fun decode(
        sender: String,
        message: String,
    ): String? {
        if (message.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) return null

        val json = runCatching { JSONObject(message) }.getOrNull() ?: return null
        if (json.optString("event") != EVENT) return message

        val id = json.opt("eventId") as? String ?: return null
        val index = json.opt("index") as? Int ?: return null
        val count = json.opt("count") as? Int ?: return null
        val data = json.opt("data") as? String ?: return null
        if (id.isBlank() || id.length > 128 || count !in 1..512 || index !in 0 until count) return null

        val key = sender to id
        val record = records.getOrPut(key) { Record(count) }
        while (records.size > 64) records.remove(records.keys.first())
        if (record.count != count || record.parts[index]?.let { it != data } == true) {
            records.remove(key)
            return null
        }

        if (index !in record.parts) {
            record.parts[index] = data
            record.bytes += data.toByteArray(Charsets.UTF_8).size
        }

        if (record.bytes > MAX_MESSAGE_BYTES) {
            records.remove(key)
            return null
        }

        if (record.parts.size != count) return null

        records.remove(key)
        val joined = (0 until count).joinToString("") { record.parts.getValue(it) }
        return joined.takeIf { runCatching { JSONObject(it) }.isSuccess }
    }

    /**
     * Clears incomplete message fragments.
     */
    @Synchronized
    fun clear() = records.clear()

    /**
     * Fragment event names and message size limits.
     */
    private companion object {

        /**
         * The protocol event identifying signal fragments.
         */
        const val EVENT = "__agora_chunk__"

        /**
         * The maximum reassembled message size, in bytes.
         */
        const val MAX_MESSAGE_BYTES = 256 * 1024
    }
}
