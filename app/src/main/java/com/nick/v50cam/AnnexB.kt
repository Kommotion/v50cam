package com.nick.v50cam

import java.nio.ByteBuffer

/**
 * MediaCodec output is not one format. Qualcomm on the V50 usually emits Annex-B
 * (start codes). Some encoders emit length-prefixed NALs, and codec-config buffers
 * are often an AVCC decoder record. OBS is opened as raw `-f h264`, which only
 * accepts Annex-B with start codes. Everything we write on the socket is 4-byte
 * start codes (00 00 00 01).
 */
internal object AnnexB {
    private val START = byteArrayOf(0, 0, 0, 1)

    fun selfCheck() {
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11)
        check(accessUnit(idr).contentEquals(idr)) { "clean annex-b changed" }
        check(containsNalType(idr, 5))
        check(!startsWithSps(idr))

        val three = byteArrayOf(0, 0, 1, 0x65, 0x10)
        check(accessUnit(three).contentEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x10))) {
            "3-byte start code was not expanded"
        }

        val avcc = byteArrayOf(0, 0, 0, 2, 0x65, 0x11)
        check(accessUnit(avcc).contentEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x11))) {
            "length-prefixed NAL was not converted"
        }

        val spsIdr = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42,
            0, 0, 0, 1, 0x65, 0x01
        )
        check(startsWithSps(spsIdr))
        check(containsNalType(spsIdr, 5))
        val only = parameterSetsOnly(spsIdr)
        check(only != null && only.contentEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42))) {
            "parameter set extract failed"
        }

        val cfg = byteArrayOf(
            1, 0x42, 0, 0x1F, 0xFF.toByte(), 0xE1.toByte(),
            0, 2, 0x67, 0x42,
            1,
            0, 2, 0x68, 0x01
        )
        val annex = avccDecoderConfigToAnnexB(cfg)
        check(annex != null && startsWithSps(annex)) { "AVCC config missing SPS" }
        check(containsNalType(annex, 8)) { "AVCC config missing PPS" }
        check(
            annex.contentEquals(
                byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0x01)
            )
        ) { "AVCC config bytes mismatch" }
    }

    fun fromCodecConfig(csd0: ByteBuffer?, csd1: ByteBuffer?): ByteArray? {
        val a = csd0?.let { copy(it) } ?: ByteArray(0)
        val b = csd1?.let { copy(it) } ?: ByteArray(0)
        if (a.isEmpty() && b.isEmpty()) return null
        return fromCodecConfigBytes(a, b)
    }

    fun fromCodecConfigBytes(data: ByteArray, extra: ByteArray = ByteArray(0)): ByteArray? {
        if (data.isEmpty() && extra.isEmpty()) return null
        if (data.isNotEmpty() && isAvccDecoderConfig(data)) {
            return avccDecoderConfigToAnnexB(data)
        }
        val out = java.io.ByteArrayOutputStream()
        if (data.isNotEmpty()) writeNormalized(out, data)
        if (extra.isNotEmpty()) writeNormalized(out, extra)
        val bytes = out.toByteArray()
        return bytes.takeIf { it.isNotEmpty() }
    }

    /** One access unit to Annex-B. Returns [data] itself when it is already clean. */
    fun accessUnit(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        if (isAnnexB(data)) {
            return if (isCleanFourByte(data)) data else normalizeStartCodes(data)
        }
        if (isAvccDecoderConfig(data)) {
            return avccDecoderConfigToAnnexB(data) ?: data
        }
        lengthPrefixedToAnnexB(data, 4)?.let { return it }
        lengthPrefixedToAnnexB(data, 2)?.let { return it }
        if (data[0].toInt() and 0x80 == 0) {
            val out = ByteArray(4 + data.size)
            out[3] = 1
            System.arraycopy(data, 0, out, 4, data.size)
            return out
        }
        return data
    }

    fun startsWithSps(annexB: ByteArray): Boolean = firstNalType(annexB) == 7

    fun containsNalType(data: ByteArray, type: Int): Boolean {
        forEachNal(data) { nalType, _, _ ->
            if (nalType == type) return true
        }
        return false
    }

    /** SPS (7) and PPS (8) only, still Annex-B. Null if neither is present. */
    fun parameterSetsOnly(annexB: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var any = false
        forEachNal(annexB) { nalType, start, end ->
            if (nalType == 7 || nalType == 8) {
                val sc = startCodeLen(annexB, start)
                val nal = start + sc
                if (nal < end) {
                    out.write(START)
                    out.write(annexB, nal, end - nal)
                    any = true
                }
            }
        }
        return if (any) out.toByteArray() else null
    }

    private fun firstNalType(data: ByteArray): Int {
        var found = -1
        forEachNal(data) { nalType, _, _ ->
            if (found < 0) found = nalType
        }
        return found
    }

    private inline fun forEachNal(
        data: ByteArray,
        block: (nalType: Int, start: Int, end: Int) -> Unit
    ) {
        var i = 0
        val n = data.size
        while (i + 3 < n) {
            val sc = startCodeLen(data, i)
            if (sc == 0) {
                i++
                continue
            }
            val nal = i + sc
            if (nal >= n) break
            val type = data[nal].toInt() and 0x1F
            var next = n
            var j = nal + 1
            while (j + 2 < n) {
                if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() &&
                    (data[j + 2] == 1.toByte() ||
                        (j + 3 < n && data[j + 2] == 0.toByte() && data[j + 3] == 1.toByte()))
                ) {
                    next = j
                    break
                }
                j++
            }
            block(type, i, next)
            if (next == i) break
            i = next
        }
    }

    private fun writeNormalized(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        if (isAnnexB(data)) {
            val norm = if (isCleanFourByte(data)) data else normalizeStartCodes(data)
            out.write(norm)
            return
        }
        lengthPrefixedToAnnexB(data, 4)?.let {
            out.write(it)
            return
        }
        if (data[0].toInt() and 0x80 == 0) {
            out.write(START)
            out.write(data)
        }
    }

    private fun isAnnexB(data: ByteArray): Boolean = startCodeLen(data, 0) > 0

    private fun isCleanFourByte(data: ByteArray): Boolean {
        if (startCodeLen(data, 0) != 4) return false
        var i = 4
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (i == 0 || data[i - 1] != 0.toByte()) return false
            }
            i++
        }
        return true
    }

    private fun normalizeStartCodes(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        forEachNal(data) { _, start, end ->
            val sc = startCodeLen(data, start)
            val nal = start + sc
            if (nal < end) {
                out.write(START)
                out.write(data, nal, end - nal)
            }
        }
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) data else bytes
    }

    private fun startCodeLen(data: ByteArray, i: Int): Int {
        if (i + 3 < data.size &&
            data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
            data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
        ) {
            return 4
        }
        if (i + 2 < data.size &&
            data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
        ) {
            return 3
        }
        return 0
    }

    private fun isAvccDecoderConfig(data: ByteArray): Boolean {
        if (data.size < 7) return false
        if (data[0] != 1.toByte()) return false
        if (startCodeLen(data, 0) > 0) return false
        val numSps = data[5].toInt() and 0x1F
        return numSps in 1..16
    }

    private fun avccDecoderConfigToAnnexB(data: ByteArray): ByteArray? {
        return try {
            var i = 5
            if (i >= data.size) return null
            val numSps = data[i].toInt() and 0x1F
            i++
            val out = java.io.ByteArrayOutputStream()
            repeat(numSps) {
                if (i + 2 > data.size) return null
                val len = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
                if (len <= 0 || i + len > data.size) return null
                out.write(START)
                out.write(data, i, len)
                i += len
            }
            if (i >= data.size) return null
            val numPps = data[i].toInt() and 0xFF
            i++
            repeat(numPps) {
                if (i + 2 > data.size) return null
                val len = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
                if (len <= 0 || i + len > data.size) return null
                out.write(START)
                out.write(data, i, len)
                i += len
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun lengthPrefixedToAnnexB(data: ByteArray, lengthSize: Int): ByteArray? {
        if (data.size <= lengthSize) return null
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var i = 0
        var nals = 0
        while (i < data.size) {
            if (i + lengthSize > data.size) return null
            var len = 0
            for (k in 0 until lengthSize) {
                len = (len shl 8) or (data[i + k].toInt() and 0xFF)
            }
            i += lengthSize
            if (len <= 0 || i + len > data.size) return null
            if (data[i].toInt() and 0x80 != 0) return null
            out.write(START)
            out.write(data, i, len)
            i += len
            nals++
            if (nals > 64) return null
        }
        if (nals == 0 || i != data.size) return null
        return out.toByteArray()
    }

    private fun copy(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }
}
