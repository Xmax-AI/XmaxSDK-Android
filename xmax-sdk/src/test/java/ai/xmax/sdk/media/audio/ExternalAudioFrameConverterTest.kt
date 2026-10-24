//
//  ExternalAudioFrameConverterTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.audio

import ai.xmax.sdk.AudioFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ExternalAudioFrameConverterTest {

    @Test
    fun `matching PCM retains partial packets and copies caller storage`() {
        val converter = ExternalAudioFrameConverter()
        val output = mutableListOf<AudioFrame>()
        val first = pcm(240, 1000)
        converter.append(first, 1_000_000, 48000, 1, output::add)
        first.fill(0)
        assertTrue(output.isEmpty())

        converter.append(pcm(720, 2000), 1_005_000, 48000, 1, output::add)
        assertEquals(listOf(1_000_000L, 1_010_000L), output.map { it.timestampUs })
        assertTrue(output.all { it.data.size == 960 })
        val samples = samples(output.first())
        assertEquals(List(240) { 1000.toShort() }, samples.take(240))
        assertEquals(List(240) { 2000.toShort() }, samples.drop(240))
    }

    @Test
    fun `resampling is independent of input buffer boundaries without timestamp drift`() {
        fun collect(chunk: Int): List<Pair<List<Byte>, Long>> {
            val converter = ExternalAudioFrameConverter()
            val output = mutableListOf<AudioFrame>()
            var offset = 0
            while (offset < 88200) {
                val count = minOf(chunk, 88200 - offset)
                val bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) { index ->
                    val sample = (((offset + index) % 300) * 100 - 15000).toShort()
                    bytes.putShort(sample)
                    bytes.putShort(sample)
                }
                converter.append(bytes.array(), 1_000_000 + offset * 1_000_000L / 44100, 44100, 2, output::add)
                offset += count
            }
            return output.map { it.data.toList() to it.timestampUs }
        }

        val whole = collect(88200)
        assertEquals(199, whole.size)
        assertEquals(whole, collect(1024))
        assertEquals(whole, collect(137))
        whole.zipWithNext().forEach { (first, second) -> assertEquals(10000L, second.second - first.second) }
    }

    @Test
    fun `stereo is downmixed before packet delivery`() {
        val output = mutableListOf<AudioFrame>()
        ExternalAudioFrameConverter().append(pcm(480, 3000, 1000), 0, 48000, 2, output::add)
        assertEquals(1, output.size)
        assertEquals(List(480) { 2000.toShort() }, samples(output.single()))
    }

    @Test
    fun `downsampling and upsampling preserve constant audio`() {
        for (rate in listOf(8000, 16000, 32000, 96000)) {
            val output = mutableListOf<AudioFrame>()
            ExternalAudioFrameConverter().append(pcm(rate / 10, -2000), 0, rate, 1, output::add)
            assertTrue(output.size in 9..10)
            assertTrue(output.all { samples(it).all { sample -> sample == (-2000).toShort() } })
        }
    }

    @Test
    fun `negative and overlapping timestamps are ignored without changing pending audio`() {
        val converter = ExternalAudioFrameConverter()
        val output = mutableListOf<AudioFrame>()
        converter.append(byteArrayOf(), -1, -1, -1, output::add)
        converter.append(pcm(240, 1000), 0, 48000, 1, output::add)
        converter.append(pcm(480, 3000), 1000, 48000, 1, output::add)
        converter.append(pcm(240, 1000), 5000, 48000, 1, output::add)
        assertEquals(1, output.size)
        assertEquals(List(480) { 1000.toShort() }, samples(output.single()))
    }

    @Test
    fun `a gap discards the partial packet without filling silence`() {
        val converter = ExternalAudioFrameConverter()
        val output = mutableListOf<AudioFrame>()
        converter.append(pcm(240, 1000), 0, 48000, 1, output::add)
        converter.append(pcm(480, 2000), 2_000_000, 48000, 1, output::add)
        assertEquals(1, output.size)
        assertEquals(2_000_000L, output.single().timestampUs)
        assertEquals(List(480) { 2000.toShort() }, samples(output.single()))
    }

    @Test
    fun `format changes and incomplete PCM are rejected`() {
        val converter = ExternalAudioFrameConverter()
        converter.append(pcm(480, 1000), 0, 48000, 1) {}
        assertThrows(IllegalArgumentException::class.java) {
            converter.append(pcm(480, 1000), 10000, 44100, 1) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            converter.append(pcm(480, 1000, 1000), 10000, 48000, 2) {}
        }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1))) {
            assertThrows(IllegalArgumentException::class.java) {
                converter.append(bytes, 10000, 48000, 1) {}
            }
        }
    }

    private fun pcm(count: Int, vararg channels: Int): ByteArray =
        ByteBuffer.allocate(count * channels.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(count) { channels.forEach { putShort(it.toShort()) } }
        }.array()

    private fun samples(frame: AudioFrame): List<Short> =
        ByteBuffer.wrap(frame.data).order(ByteOrder.LITTLE_ENDIAN).let { buffer ->
            List(buffer.remaining() / 2) { buffer.short }
        }
}
