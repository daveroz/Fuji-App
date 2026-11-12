package com.fujiapp.remote.model

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** A custom setting slot (C1–C7) as stored on the camera. */
data class CustomSlot(val number: Int, val name: String) {
    val label: String get() = "C$number"
}

/**
 * The camera's settings file: what a backup downloads, zlib-compressed. Unpacked, it starts with
 * `FUJIFILMX-BACKUP`, a four-digit format version and the camera model.
 *
 * The custom settings C1–C7 are the last seven 1 KB records of the file. Each starts with the
 * value 0x15C twice and holds the slot's name at 0x269 (up to 25 characters, zero-padded).
 * Found in an X100VI 1.31 backup (70,524 bytes unpacked): the seven names the camera showed sat
 * exactly there. The layout of the other values in a record isn't known yet, so only the names
 * are read.
 */
class SettingsFile private constructor(
    val model: String,
    val version: String,
    val size: Int,
    val customSlots: List<CustomSlot>,
) {
    companion object {
        const val MAGIC = "FUJIFILMX-BACKUP"
        const val SLOTS = 7
        const val RECORD = 0x400
        const val RECORD_MARK = 0x15C
        const val NAME_AT = 0x269
        const val NAME_LENGTH = 25
        private const val MODEL_AT = 0x14
        private const val MODEL_LENGTH = 0x20
        private const val MAX_SIZE = 4 shl 20

        /** Parses a settings file as downloaded (compressed) or already unpacked; null if it isn't one. */
        fun parse(data: ByteArray): SettingsFile? {
            val raw = if (startsWithMagic(data)) data else inflate(data) ?: return null
            if (!startsWithMagic(raw) || raw.size < MODEL_AT + MODEL_LENGTH) return null
            val version = String(raw, MAGIC.length, 4, Charsets.ISO_8859_1)
            val model = text(raw, MODEL_AT, MODEL_LENGTH)
            return SettingsFile(model, version, raw.size, slots(raw))
        }

        private fun startsWithMagic(b: ByteArray): Boolean =
            b.size >= MAGIC.length && String(b, 0, MAGIC.length, Charsets.ISO_8859_1) == MAGIC

        private fun inflate(data: ByteArray): ByteArray? {
            val inflater = Inflater()
            return try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream(data.size * 8)
                val buffer = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_SIZE) return null
                }
                out.toByteArray()
            } catch (_: DataFormatException) {
                null
            } finally {
                inflater.end()
            }
        }

        /** The seven slots from the end of the file, or none if the records aren't there. */
        private fun slots(raw: ByteArray): List<CustomSlot> {
            val start = raw.size - SLOTS * RECORD
            if (start < 0 || (0 until SLOTS).any { !isRecord(raw, start + it * RECORD) }) return emptyList()
            return (0 until SLOTS).map { i -> CustomSlot(i + 1, text(raw, start + i * RECORD + NAME_AT, NAME_LENGTH)) }
        }

        private fun isRecord(raw: ByteArray, at: Int): Boolean = u32(raw, at) == RECORD_MARK && u32(raw, at + 4) == RECORD_MARK

        private fun u32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        /** Zero-terminated text in a fixed-size field, printable ASCII only. */
        private fun text(b: ByteArray, at: Int, length: Int): String {
            val sb = StringBuilder()
            for (i in at until minOf(at + length, b.size)) {
                val v = b[i].toInt() and 0xFF
                if (v == 0) break
                if (v in 0x20..0x7E) sb.append(v.toChar())
            }
            return sb.toString().trim()
        }
    }
}
