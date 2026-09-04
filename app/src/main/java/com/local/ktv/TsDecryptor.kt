package com.local.ktv

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object TsDecryptor {
    private const val TAG = "TsDecryptor"
    private const val HEADER_SIZE = 512
    private const val TS_PACKET_SIZE = 188
    private const val MAX_VALIDATION_PACKETS = 32 * 1024

    private val signatures = listOf("THUNDERCRYP3", "HHCMUSECRYP1", "HHCMUSECRYP2")
        .map { it.toByteArray(Charsets.US_ASCII) }

    @JvmStatic
    fun isEncrypted(file: File?): Boolean {
        if (file == null || !file.exists() || file.length() < HEADER_SIZE) return false
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                if (input.readUnsignedByte() != 0x47) return@use true
                val signature = ByteArray(12)
                input.seek(500)
                input.readFully(signature)
                signatures.any(signature::contentEquals)
            }
        }.getOrDefault(false)
    }

    @JvmStatic
    fun decryptFile(inputFile: File, outputFile: File): Boolean {
        if (!inputFile.exists() || inputFile.length() <= HEADER_SIZE) return false
        return try {
            RandomAccessFile(inputFile, "r").use { input ->
                val header = ByteArray(HEADER_SIZE)
                input.readFully(header)
                val key = resolveKey(header)
                val segmentSize = header.u8(452) * 1024
                val mode = header.u8(453)
                val interval = header.u8(454)
                val firstEncryptedSegment = header.u8(457)
                require(segmentSize > 0) { "Invalid encrypted segment size" }
                require(mode == 0) { "Unsupported Thunder encryption mode: $mode" }

                val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                }
                outputFile.parentFile?.mkdirs()
                outputFile.outputStream().buffered(256 * 1024).use { output ->
                    val segment = ByteArray(segmentSize)
                    var segmentIndex = 0L
                    while (true) {
                        val count = input.readChunk(segment)
                        if (count <= 0) break
                        if (shouldDecrypt(segmentIndex, firstEncryptedSegment, interval)) {
                            val encryptedLength = count - count % 16
                            if (encryptedLength > 0) {
                                val decrypted = cipher.doFinal(segment, 0, encryptedLength)
                                output.write(decrypted)
                                if (encryptedLength < count) {
                                    output.write(segment, encryptedLength, count - encryptedLength)
                                }
                            } else {
                                output.write(segment, 0, count)
                            }
                        } else {
                            output.write(segment, 0, count)
                        }
                        segmentIndex++
                    }
                }
            }

            check(outputFile.length() == inputFile.length() - HEADER_SIZE) {
                "Decrypted file length mismatch"
            }
            check(isValidTransportStream(outputFile)) { "Decrypted TS validation failed" }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "TS decrypt failed: ${inputFile.absolutePath}", error)
            outputFile.delete()
            false
        }
    }

    private fun shouldDecrypt(segmentIndex: Long, firstEncryptedSegment: Int, interval: Int): Boolean {
        if (interval == 0) return true
        return segmentIndex == firstEncryptedSegment.toLong() ||
            (segmentIndex > firstEncryptedSegment && segmentIndex % interval == 1L)
    }

    private fun resolveKey(header: ByteArray): ByteArray {
        val signature = header.copyOfRange(500, 512)
        val table = when {
            signature.contentEquals(signatures[0]) -> THUNDER_KEYS
            signature.contentEquals(signatures[1]) -> HHC1_KEYS
            signature.contentEquals(signatures[2]) -> HHC2_KEYS
            else -> error("Unknown Thunder encryption signature")
        }
        return table[header.u8(53) and 0x0f].hexToBytes()
    }

    private fun isValidTransportStream(file: File): Boolean {
        val packetCount = minOf(file.length() / TS_PACKET_SIZE, MAX_VALIDATION_PACKETS.toLong())
        if (packetCount == 0L) return false
        BufferedInputStream(file.inputStream(), 256 * 1024).use { input ->
            val packet = ByteArray(TS_PACKET_SIZE)
            repeat(packetCount.toInt()) {
                var read = 0
                while (read < packet.size) {
                    val count = input.read(packet, read, packet.size - read)
                    if (count < 0) return false
                    read += count
                }
                if (packet[0] != 0x47.toByte()) return false
            }
        }
        return true
    }

    private fun RandomAccessFile.readChunk(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private val THUNDER_KEYS = arrayOf(
        "c6d3cdd0f1ebf5ded4d7d3cebbd3dad2cecdd0b8deaccbead2c5cac3c2dba6ac",
        "f5deebd8d3ced7b9dad2d3d8d0bcd9f6cbd7c9c2cab1d0f0a6f8d0afd0d3b9d4",
        "c3d4d7b2c2d5aaeecbd3b7dcbcc6b4f4b7d4b2b2fed5e0cbd3d5b2d7c6b7cef3",
        "b6bdaea7ced2d2b4aac1bbd3cbc8b7d6aacbbdaecbd4cbb5f9daddc0cecbe4d7",
        "d7d3d4dde8cedaf3c7b4cbddd2d3aee7f5d6d6b2d2aed0c9cbcddbb2ddf0e6c9",
        "b4d3d3b0d9d6cbe2e2aeaea2d6b8c7b2c3c9e5bbd6d9c7bcaee2d2dababac1b2",
        "d9d6cbe2e2aeaea2d6b2c7b2c3e0e5bbd6d9c7bcaee2d2dababad6b2d3d3b1bb",
        "e2aeaea2d69dc7b2c35fe5bbd6d9c7bcaee2d2dababac2b2d3d3d9bbd6cbe2f0",
        "97bbecd2fab782f0c1d0f6b3bab2c8a9dec7d0ec62fdf8b1d3d2cebccef5c4dd",
        "eadcd4eecad6d3bec7ae81fde6badcd7eecf98d3c1f6d1cefac8d4c2b6d2c4c6",
        "e7bed4c1b5fdd8bcbfd7d0c867d3cbcbd1d4d1d6d4d8e1c8c4c7d1d6eedee1c8",
        "f2b1c8bdbbd4cfeff1b0d0f0d3cbddd4cec492d8d3c2b3e1daedb5fdb1bcf0d0",
        "d3cdd2d6d2f5d1dc81c1b3ba90eec2ee81cecece90c5fdc4ceb2d4cdc4bbd5f5",
        "f5cbaee0b9c9e8cafafae5bfcdcebccef5acc3c4b9d6bccdfadcc3f5bfd6b6d2",
        "d6d3c3b7dcdad2f4b7d6b3c3fedca3f4baccd2c2eeecf3e3b7c3cabdfefcbfab",
        "e0b4e4f8b8c9c9d3a3a5cfdad2cab5d2f3a6dbf3d6bfd2bfaecbcba5cec5bcc3",
    )

    private val HHC1_KEYS = arrayOf(
        "d7cad2d3d3b1e0d0d4cfcbc5bbb0b5f3d1d6d4d7a7aec3d4b6b2bad4f8bbf5b6",
        "d3cfd2d0b2b6d5d2bbf8dfb2babacebec3c3b4fdb7d7d6d7b8f7aed3c9c2d3ce",
        "cec4babde1b1f5bbc9b6d3b6edf8ebf8ceb2c5b2aabbf3bbc8d6d3d0cbd2d1c5",
        "f6f8dad0d4d0b6d3f2c5f8d0b5b7c7d3dcbad7e0bdb0c8c1f7aecaa6b6d6d0d4",
        "d4b1d2d7bbd8d3d3ceced7b2b4bdd3bbd1d6d4d6a7aebbd8ced1bed4e1a7fdf2",
        "d3d3d3eec7b9d6d2ddb1c1b2ced4d3b1cabbdad8d3b7cacedaf2c7c5d7d7b0c6",
        "d6d7b9c3aed3dbbbc7d4c6b9f3bbe4dbd6b8d6c6aeb8bee4d3d4b8d0ebdab8d0",
        "d0aabbaecbbad2d2f9cdd4e0b2b6c0b2bbf8f1bbd0babdbfd0cddac9d6b2d6d0",
        "d7b1b0b6d3a5b2f8cabec3c9b3d3f4f7ceced3d3dededadac7c7cad1f3f3c2d4",
        "f8c3d3c6c0c0b9c8d6f1b1e7b8d5d4c7bbdfbbd0b6d2cac8f8b2abe7bad7d4b4",
        "d6bbd2d5aebcb2feb2b2d7d2bbbbd3d4bcd6d4b5baaabbc2d6c8cec6aacbaaa9",
        "aee2c0ebd2b6d6d6d4f8aeaed0ced2d2ccded4d4c3b3b5c0f1dcc2f1c3b5c6d3",
        "cbb4b2dcb3d3bbb2c6d0d3d7dfc4e2d3cacbbeceaef9d8cab6d3c3d0f8fbcfa2",
        "aee1c0cfd2d6d6ced4aeaee4c0d2d2b2f1d4d4aecbc0c0cec0f1f1cad4bcc3d0",
        "bebad0c4b4f5a2d1bad7d7d3ced3d3d0d2cfd4cad4c4bbc2b1cec9b5f0caabdc",
        "bda2dee4d2bbd7cbe0d8d3f9d7d2d4d2e3b2bbd4d2b2cab9d4bbd3dbb7d3c6c6",
    )

    private val HHC2_KEYS = arrayOf(
        "b1b4d6d6f8f3aeaed5cab5b5dfc2d8c0b9cbb4b2fac0e6bbd6c9cdbfaefaf6c9",
        "c0dac9c9d5c9d3d3dfcfebebc1cdd6d6eeacaeaec3d2cbc9f1e2c0fad3bfbfb6",
        "d5b5b7bddfc0b2abc7d6b4c4faf7cbaad6d3ceb2c6c3e5bbb9d2d5ced9b2dfc5",
        "a8f8bfcdc1d6d7b7eedae4a3cacacacaebebebebd0c7c1c3d0bfb7f7b1cac9ce",
        "ced7d5d6aaf4dfc6d6c6d2c8aee4f2a8cacdc0d2c6e2fbb2d2cab6b1d4c6f8f8",
        "f8f8f8f8c8b1b1c4a1b8dcd3d6d6d6d6aeaeaeaecac7c5b1b5bfadb0b6b6b6b6",
        "c3b5cecbedc3b4e3cbcbd5b2e3e3bdbbcab6b6caa4e0f8a4d5d2c3d5dfb2eddf",
        "e1cbf2b8b8b4c7d4eff8a7f2b3bcc0c4b5d7efdac7cac0cda7aea1e2b3cdc1d6",
        "b6b9c7d4dba5fcf2b1b3beb9f8c7c3fab4d4b1d3ecf2a9c3c8c1cab2f1a6a6bb",
        "d9aef8fbcebebed5b4c3c3dfb6d2b6cec3b2f8b4c7b7b9d6c9f2faaed6b1c0d3",
        "c8d2b9d7a1f2cae3d3c1bed2c3b8fcb2d3d3cab9dadab3fab9b5bfd6fad0c9ae",
        "a6dad9aec7d0d0c8fce9d5a5d6d3d6c6d0daaee4d4bcb7c6add2d1dfc4b0cab9",
        "d2b6b8cebbfed1e1d6cad2b6d3aebbfeb5d6cacab1d3afaecebdb5cae1d9b1af",
        "d3e4aed0b6c9cab6f8c6c7f8b3b6ced2cbf8bde6d6d1cac7aef8a4bfd7d6b5b9",
        "b9bebec2fafcfcc3b4ceb4ceceaaceaad6c9d6c9aecfaecfc8c6c8c6abc6abc6",
        "f8f8dff8c7c9d2b7fcc6b2a5c8d6b9c4cbaecab1d6c9c9c6aec6cfe4b1d5b1b4",
    )
}
