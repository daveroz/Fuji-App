package com.fujiapp.remote.ble

import java.io.ByteArrayOutputStream
import java.io.IOException

class FileTransferException(message: String) : IOException(message)

/**
 * The camera's Bluetooth file transfer (service `af854c2e`), as FUJIFILM XApp 2.7.6 uses it for
 * the settings backup, the restore and the camera's vital data.
 *
 * **Download** (backup, vital data):
 *  1. listen to FILE_TRANSACTION_STATE and the request's state characteristic
 *  2. write the request (BACKUP_REQUEST = 1, or LOG_TRANSFER_REQUEST = 2 for vital data)
 *  3. wait for the state characteristic to become 1 (ready); 0 means the camera is busy
 *  4. write FILE_PARTIAL_SIZE = 120, then FILE_TRANSFER_INDEX = 0
 *  5. on every transaction state 1 or 2, read FILE_PARTIAL_DATA (one chunk)
 *  6. on transaction state 3, check the checksum, read FILE_INFORMATION, then write
 *     FILE_TRANSFER_RESULT = 3 (finish, backup only) and 1 (success); state 4 then closes it
 *
 * **Upload** (restore):
 *  1. listen to FILE_TRANSACTION_STATE and RESTORE_STATE; write RESTORE_REQUEST = 1
 *  2. wait for RESTORE_STATE = 1
 *  3. write FILE_PARTIAL_SIZE = 120, FILE_TRANSFER_INDEX = 0, then FILE_INFORMATION
 *  4. wait for transaction state 1, then write each chunk from [restoreChunks] and wait for the
 *     next transaction state: 2 = send the next one, 3 = all received (write FILE_TRANSFER_RESULT
 *     = 3), 0 = error (write 0); anything else cancels (write 2)
 *
 * Chunks are `u16 sequence, u32 length, length bytes`. The sequence counts from 0 and the last
 * chunk has 0xFFFF; its payload ends with the 16-bit sum of every data byte of the file.
 */
object FileTransfer {

    /** Bytes per chunk (XApp's FILE_PARTIAL_SIZE). */
    const val PARTIAL_SIZE = 120

    /** Request values. */
    const val REQUEST_SETTINGS = 1
    const val REQUEST_VITAL_DATA = 2

    /** Request state characteristic values (BACKUP_STATE, RESTORE_STATE, LOG_TRANSFER_STATE). */
    const val READY = 1

    /** FILE_TRANSACTION_STATE values. */
    const val STATE_ERROR = 0
    const val STATE_START = 1
    const val STATE_TRANSFERRING = 2
    const val STATE_FILE_FINISHED = 3
    const val STATE_FILE_PROCESS_FINISHED = 4
    const val STATE_TRANSFER_FINISHED = 5

    /** FILE_TRANSFER_RESULT values. */
    const val RESULT_FAIL = 0
    const val RESULT_SUCCESS = 1
    const val RESULT_CANCEL = 2
    const val RESULT_FINISH = 3

    /** Sequence number of the final chunk, whose payload ends with the 16-bit checksum. */
    const val LAST_SEQUENCE = 0xFFFF

    /** The name XApp gives a restored settings file. */
    const val BACKUP_FILE_NAME = "backup.dat"

    private const val CHUNK_HEADER = 6
    private const val FILE_NAME_FIELD = 40
    private const val FILE_INFO_SIZE = 44

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
        if (value == null || value.size < FILE_INFO_SIZE) return null
        val name = FujiProtocol.string(value.copyOf(FILE_NAME_FIELD)) ?: ""
        return FileInfo(name, FujiProtocol.u32(value, FILE_NAME_FIELD)!!)
    }

    /** FILE_INFORMATION as written for a restore: UTF-8 name zero-padded to 40 bytes, u32 size. */
    fun encodeFileInfo(name: String, size: Long): ByteArray {
        val out = ByteArray(FILE_INFO_SIZE)
        val utf8 = name.toByteArray(Charsets.UTF_8)
        utf8.copyInto(out, 0, 0, minOf(utf8.size, FILE_NAME_FIELD))
        FujiProtocol.le32(size).copyInto(out, FILE_NAME_FIELD)
        return out
    }

    /**
     * The FILE_PARTIAL_DATA values XApp writes to restore [data]: 120-byte chunks numbered from 0,
     * the last numbered 0xFFFF with the 16-bit sum of all data bytes appended (and counted in its
     * length). Every value is followed by a u16 checksum field: the same sum on the last chunk,
     * 0 on the others.
     */
    fun restoreChunks(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) throw FileTransferException("the backup is empty")
        val count = (data.size + PARTIAL_SIZE - 1) / PARTIAL_SIZE
        val chunks = ArrayList<ByteArray>(count)
        var sum = 0
        for (i in 0 until count) {
            val last = i == count - 1
            val start = i * PARTIAL_SIZE
            val body = data.copyOfRange(start, minOf(start + PARTIAL_SIZE, data.size))
            sum += body.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            val checksum = FujiProtocol.le16(sum and 0xFFFF)
            val payload = if (last) body + checksum else body
            chunks += FujiProtocol.le16(if (last) LAST_SEQUENCE else i) +
                FujiProtocol.le32(payload.size.toLong()) +
                payload +
                (if (last) checksum else FujiProtocol.le16(0))
        }
        return chunks
    }

    /** The first uint16 of a FILE_TRANSACTION_STATE or request state value. */
    fun stateCode(value: ByteArray?): Int? = FujiProtocol.u16(value) ?: FujiProtocol.u8(value)

    /** The 16-bit sum of every data byte, as the camera appends it to the last chunk. */
    fun checksum(data: ByteArray): Int = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFFFF
}

/**
 * Reassembles a downloaded file from [FileTransfer.Chunk]s and verifies the checksum the camera
 * appends to the last one.
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

    /** Throws if the last chunk arrived and its checksum doesn't match what was received. */
    fun verify() {
        val expected = expectedSum
        val actual = sum and 0xFFFF
        if (expected != null && expected != actual) {
            throw FileTransferException("checksum mismatch: got %04X, camera sent %04X".format(actual, expected))
        }
    }

    /**
     * The finished file. Throws if the checksum does not match. When [info] gives a size smaller
     * than what arrived, the file is cut to it (the last chunk may be padded).
     */
    fun finish(info: FileTransfer.FileInfo?): ByteArray {
        verify()
        val data = out.toByteArray()
        val size = info?.size ?: 0
        return if (size > 0 && size < data.size) data.copyOf(size.toInt()) else data
    }
}
