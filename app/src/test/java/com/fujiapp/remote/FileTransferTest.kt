package com.fujiapp.remote

import com.fujiapp.remote.ble.FileAssembler
import com.fujiapp.remote.ble.FileTransfer
import com.fujiapp.remote.ble.FileTransferException
import com.fujiapp.remote.ble.FujiProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileTransferTest {

    /** A FILE_PARTIAL_DATA value as the camera sends it: u16 seq, u32 length, payload. */
    private fun chunk(seq: Int, payload: ByteArray): ByteArray =
        FujiProtocol.le16(seq) + FujiProtocol.le32(payload.size.toLong()) + payload

    private fun info(name: String, size: Long): ByteArray =
        name.toByteArray().copyOf(40) + FujiProtocol.le32(size)

    @Test fun backup_is_reassembled_and_checked() {
        // Same shape as fuji-bridge's test: one full 120-byte chunk, then the last with the sum.
        val first = ByteArray(120) { 7 }
        val last = byteArrayOf(1, 2, 3, 4)
        val sum = FileTransfer.checksum(first + last)
        val a = FileAssembler()
        a.add(FileTransfer.parseChunk(chunk(0, first)))
        a.add(FileTransfer.parseChunk(chunk(0xFFFF, last + FujiProtocol.le16(sum))))
        assertTrue(a.isComplete)
        val file = a.finish(FileTransfer.parseFileInfo(info("backup.dat", 124)))
        assertArrayEquals(first + last, file)
    }

    @Test fun damaged_backup_is_refused() {
        val a = FileAssembler()
        a.add(FileTransfer.parseChunk(chunk(0xFFFF, byteArrayOf(9, 9, 0, 0))))
        try {
            a.finish(null)
            fail("a wrong checksum must fail")
        } catch (_: FileTransferException) {
        }
    }

    @Test fun file_is_cut_to_the_reported_size() {
        val a = FileAssembler()
        val body = byteArrayOf(5, 6, 7, 0, 0)
        a.add(FileTransfer.parseChunk(chunk(0xFFFF, body + FujiProtocol.le16(FileTransfer.checksum(body)))))
        assertArrayEquals(byteArrayOf(5, 6, 7), a.finish(FileTransfer.FileInfo("backup.dat", 3)))
    }

    @Test fun file_information_parses_name_and_size() {
        // libfuji's capture of the X-series backup: "backup.dat", 0x827C bytes.
        val parsed = FileTransfer.parseFileInfo(info("backup.dat", 0x827C))!!
        assertEquals("backup.dat", parsed.name)
        assertEquals(0x827CL, parsed.size)
    }

    @Test(expected = FileTransferException::class)
    fun chunk_claiming_more_than_it_carries_is_rejected() {
        FileTransfer.parseChunk(FujiProtocol.le16(0) + FujiProtocol.le32(200) + ByteArray(10))
    }

    @Test(expected = FileTransferException::class)
    fun no_chunks_after_the_last() {
        val a = FileAssembler()
        a.add(FileTransfer.parseChunk(chunk(0xFFFF, byteArrayOf(0, 0))))
        a.add(FileTransfer.parseChunk(chunk(1, byteArrayOf(1))))
    }

    @Test fun state_codes() {
        assertEquals(1, FileTransfer.stateCode(byteArrayOf(1, 0, 0, 0)))
        assertEquals(3, FileTransfer.stateCode(byteArrayOf(3, 0)))
        assertEquals(1, FileTransfer.stateCode(byteArrayOf(1)))
    }
}
