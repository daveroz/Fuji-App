package com.fujiapp.remote.ble

import java.io.ByteArrayOutputStream
import java.io.IOException

class FileTransferException(message: String) : IOException(message)

/**
 * The camera's Bluetooth file transfer (service `af854c2e`), as XApp uses it for the settings
 * backup (Th0rgal/fuji-bridge, from XApp 2.2.1):
 *
 *  1. listen to FILE_TRANSACTION_STATE and BACKUP_STATE
 *  2. write BACKUP_REQUEST = 1 (camera settings)
 *  3. wait up to 10 s for BACKUP_STATE = 1 (ready); 0 means the camera is busy
 *  4. write FILE_PARTIAL_SIZE = 120, then FILE_TRANSFER_INDEX = 0
 *  5. on every TRANSACTION_STATE 1 or 2, read FILE_PARTIAL_DATA (one [Chunk])
 *  6. on TRANSACTION_STATE 3, check the checksum, read FILE_INFORMATION, write
 *     FILE_TRANSFER_RESULT = 3; on any failure write 0 instead
 */
object FileTransfer {

    /** Bytes per chunk XApp asks for. */
    const val PARTIAL_SIZE = 120L
    const val BACKUP_KIND_SETTINGS = 1

    const val STATE_FIRST_CHUNK = 1
    const val STATE_NEXT_CHUNK = 2
    const val STATE_COMPLETE = 3

    const val RESULT_ABORT = 0
    const val RESULT_FINISHED = 3

    /** Sequence number of the final chunk, whose payload ends with the 16-bit checksum. */
    const val LAST_SEQUENCE = 0xFFFF

    private const val CHUNK_HEADER = 6
    private const val FILE_NAME_FIELD = 40

    /** One FILE_PARTIAL_DATA value: u16 sequence, u32 length, then `length` bytes. */
    class Chunk(val sequence: Int, val payload: ByteArray) {
        val isLast: Boolean get() = sequence == LAST_SEQUENCE
    }

    /** FILE_INFORMATION: the file name (NUL padded) and its size in bytes. */
    data class FileInfo(val name: String, val size: Long)

    fun parseChunk(value: ByteArray): Chunk {
        if (value.size < CHUNK_HEADER) throw FileTransferException("chunk too short (${value.size} bytes)")
        val sequence = FujiProtocol.u16(value)!!
        val length = FujiProtocol.u32(value, 2)!!
        if (length > value.size - CHUNK_HEADER) {
            throw FileTransferException("chunk says $length bytes but carries ${value.size - CHUNK_HEADER}")
        }
        return Chunk(sequence, value.copyOfRange(CHUNK_HEADER, CHUNK_HEADER + length.toInt()))
    }

    fun parseFileInfo(value: ByteArray?): FileInfo? {
        if (value == null || value.size < FILE_NAME_FIELD + 4) return null
        val name = FujiProtocol.string(value.copyOf(FILE_NAME_FIELD)) ?: ""
        return FileInfo(name, FujiProtocol.u32(value, FILE_NAME_FIELD)!!)
    }

    /** The first uint16 of a FILE_TRANSACTION_STATE / BACKUP_STATE value. */
    fun stateCode(value: ByteArray?): Int? = FujiProtocol.u16(value) ?: FujiProtocol.u8(value)

    /** The 16-bit sum of every data byte, as the camera appends it to the last chunk. */
    fun checksum(data: ByteArray): Int = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFFFF
}

/**
 * Reassembles a file from [FileTransfer.Chunk]s and verifies the checksum the camera appends to
 * the last one.
 */
class FileAssembler {
    private val out = ByteArrayOutputStream()
    private var sum = 0
    private var expectedSum: Int? = null

    var isComplete: Boolean = false
        private set

    val received: Int get() = out.size()

    fun add(chunk: FileTransfer.Chunk) {
        if (isComplete) throw FileTransferException("chunk after the last one")
        var body = chunk.payload
        if (chunk.isLast) {
            isComplete = true
            if (body.size >= 2) {
                expectedSum = FujiProtocol.u16(body.copyOfRange(body.size - 2, body.size))
                body = body.copyOf(body.size - 2)
            }
        }
        out.write(body)
        sum += body.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
    }

    /**
     * The finished file. Throws if the checksum does not match. When [info] gives a size smaller
     * than what arrived, the file is cut to it (the last chunk may be padded).
     */
    fun finish(info: FileTransfer.FileInfo?): ByteArray {
        val expected = expectedSum
        val actual = sum and 0xFFFF
        if (expected != null && expected != actual) {
            throw FileTransferException("checksum mismatch: got %04X, camera sent %04X".format(actual, expected))
        }
        val data = out.toByteArray()
        val size = info?.size ?: 0
        return if (size > 0 && size < data.size) data.copyOf(size.toInt()) else data
    }
}
