package no.blckswan.monicakey

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

internal object FixedEnvelope {
    const val SIZE = BinaryProtocol.FIXED_ENVELOPE_SIZE
    const val IV_BYTES = 12
    const val SALT_BYTES = 32
    const val TAG_BYTES = 16
    private const val HEADER_BYTES = 61
    const val MAX_CIPHERTEXT_BYTES = SIZE - HEADER_BYTES

    private const val MARKER: Byte = 0x7e
    private const val VERSION: Byte = 0x02
    private const val FLAG_SALT = 0x01
    private val zeroSalt = ByteArray(SALT_BYTES)
    private val random = SecureRandom()

    data class Packet(
        val keyId: Int,
        val messageId: Long,
        val iv: ByteArray,
        val ciphertext: ByteArray,
        val rekeySalt: ByteArray?
    )

    fun encode(packet: Packet): ByteArray {
        require(packet.keyId > 0)
        require(packet.messageId > 0)
        require(packet.iv.size == IV_BYTES)
        require(packet.ciphertext.size in TAG_BYTES..MAX_CIPHERTEXT_BYTES)
        require(packet.rekeySalt == null || packet.rekeySalt.size == SALT_BYTES)

        val output = ByteArray(SIZE).also(random::nextBytes)
        ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN).apply {
            put(MARKER)
            put(VERSION)
            putInt(packet.keyId)
            putLong(packet.messageId)
            put(if (packet.rekeySalt != null) FLAG_SALT.toByte() else 0)
            put(packet.iv)
            put(packet.rekeySalt ?: zeroSalt)
            putShort(packet.ciphertext.size.toShort())
            put(packet.ciphertext)
        }
        return output
    }

    fun decode(input: ByteArray): Packet {
        require(input.size == SIZE) { "Envelope must be exactly $SIZE bytes" }
        val buffer = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
        require(buffer.get() == MARKER) { "Invalid envelope marker" }
        require(buffer.get() == VERSION) { "Unsupported envelope version" }
        val keyId = buffer.int
        val messageId = buffer.long
        val flags = buffer.get().toInt() and 0xff
        require(flags and FLAG_SALT.inv() == 0) { "Unknown envelope flags" }
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val saltBytes = ByteArray(SALT_BYTES).also(buffer::get)
        val length = buffer.short.toInt() and 0xffff
        require(length in TAG_BYTES..MAX_CIPHERTEXT_BYTES) { "Invalid ciphertext length" }
        val ciphertext = ByteArray(length).also(buffer::get)
        return Packet(
            keyId = keyId,
            messageId = messageId,
            iv = iv,
            ciphertext = ciphertext,
            rekeySalt = if (flags and FLAG_SALT != 0) saltBytes else null
        )
    }

    fun aad(
        keyId: Int,
        messageId: Long,
        iv: ByteArray,
        rekeySalt: ByteArray?,
        ciphertextLength: Int
    ): ByteArray = ByteBuffer
        .allocate(HEADER_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(MARKER)
        .put(VERSION)
        .putInt(keyId)
        .putLong(messageId)
        .put(if (rekeySalt != null) FLAG_SALT.toByte() else 0)
        .put(iv)
        .put(rekeySalt ?: zeroSalt)
        .putShort(ciphertextLength.toShort())
        .array()
}
