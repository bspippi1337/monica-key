package no.blckswan.monicakey

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryProtocol {
    const val TYPE_LOCATION: Byte = 0x01
    const val TYPE_CHAT: Byte = 0x02
    const val TYPE_VOICE: Byte = 0x03
    const val TYPE_HEARTBEAT: Byte = 0x04

    const val LOCATION_PACKET_SIZE = 33
    const val FIXED_ENVELOPE_SIZE = 8192

    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val speed: Float,
        val bearing: Float,
        val timestampSeconds: Int
    )

    object LocationPacket {
        fun encode(data: LocationData): ByteArray = ByteBuffer
            .allocate(LOCATION_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_LOCATION)
            .putDouble(data.latitude)
            .putDouble(data.longitude)
            .putFloat(data.accuracy)
            .putFloat(data.speed)
            .putFloat(data.bearing)
            .putInt(data.timestampSeconds)
            .array()

        fun decode(packet: ByteArray): LocationData {
            require(packet.size == LOCATION_PACKET_SIZE) {
                "Location packet must be exactly $LOCATION_PACKET_SIZE bytes"
            }
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            require(buffer.get() == TYPE_LOCATION) { "Not a location packet" }
            return LocationData(
                latitude = buffer.double,
                longitude = buffer.double,
                accuracy = buffer.float,
                speed = buffer.float,
                bearing = buffer.float,
                timestampSeconds = buffer.int
            )
        }
    }

    fun encodeText(type: Byte, utf8: ByteArray): ByteArray {
        require(type == TYPE_CHAT || type == TYPE_HEARTBEAT) { "Invalid text/control type" }
        require(utf8.size <= 65_535) { "Payload is too large" }
        return ByteBuffer.allocate(3 + utf8.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(type)
            .putShort(utf8.size.toShort())
            .put(utf8)
            .array()
    }

    fun encodeVoice(pcm: ByteArray): ByteArray {
        require(pcm.size <= 65_535) { "Voice chunk is too large" }
        return ByteBuffer.allocate(3 + pcm.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_VOICE)
            .putShort(pcm.size.toShort())
            .put(pcm)
            .array()
    }

    fun decodeLengthPrefixed(packet: ByteArray, expectedType: Byte): ByteArray {
        require(packet.size >= 3) { "Packet is too short" }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        require(buffer.get() == expectedType) { "Unexpected packet type" }
        val length = buffer.short.toInt() and 0xffff
        require(length == buffer.remaining()) { "Invalid packet length" }
        return ByteArray(length).also(buffer::get)
    }
}
