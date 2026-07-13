package no.blckswan.monicakey

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object HkdfSha256 {
    fun derive(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int = 32
    ): ByteArray {
        require(outputLength in 1..(255 * 32)) { "Invalid HKDF output length" }

        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudoRandomKey = extract.doFinal(inputKeyMaterial)

        val output = ByteArray(outputLength)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        try {
            while (offset < outputLength) {
                val expand = Mac.getInstance("HmacSHA256")
                expand.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
                expand.update(previous)
                expand.update(info)
                expand.update(counter.toByte())
                val block = expand.doFinal()
                previous.fill(0)
                previous = block
                val count = minOf(block.size, outputLength - offset)
                block.copyInto(output, destinationOffset = offset, endIndex = count)
                offset += count
                counter += 1
            }
            return output
        } finally {
            pseudoRandomKey.fill(0)
            previous.fill(0)
        }
    }
}
