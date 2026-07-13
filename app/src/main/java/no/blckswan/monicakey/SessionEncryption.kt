package no.blckswan.monicakey

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Hardened directional AEAD chain. Production still requires the maintained Signal DH ratchet. */
class SessionEncryption(
    context: Context,
    channelId: String,
    rootKeyB64: String,
    localRole: Role
) {
    private data class KeyVersion(val id: Int, val key: ByteArray)

    private val lock = Any()
    private val random = SecureRandom()
    private val store = SecretStore(context.applicationContext)
    private val scope = sha256(channelId).take(24) + "_" + localRole.name.lowercase()
    private val root = CryptoBox.unb64(rootKeyB64).also {
        require(it.size == 32) { "Session root key must be 256 bits" }
    }
    private val sendDirection = if (localRole == Role.PIPPI) "PIPPI->MONICA" else "MONICA->PIPPI"
    private val receiveDirection = if (localRole == Role.PIPPI) "MONICA->PIPPI" else "PIPPI->MONICA"

    private var sendKeyId = readInt("send_id", 1)
    private var sendKey = readBytes("send_key") ?: initialKey(sendDirection)
    private var sendSalt = readBytes("send_salt")
    private var nextMessageId = readLong("send_next", 1L).coerceAtLeast(1L)

    private var receiveKeyId = readInt("receive_id", 1)
    private var receiveKey = readBytes("receive_key") ?: initialKey(receiveDirection)
    private var receiveSalt = readBytes("receive_salt")
    private val history = ArrayDeque<KeyVersion>()
    private val replay = ReplayWindow(
        highest = readLong("receive_highest", 0L),
        seen = store.get(name("receive_seen"))
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            .orEmpty()
    )

    init {
        readHistory().forEach(history::addLast)
        persistSend()
        persistReceive()
    }

    fun encrypt(plaintext: ByteArray): ByteArray = synchronized(lock) {
        require(plaintext.isNotEmpty())
        if (nextMessageId > 1L && (nextMessageId - 1L) % ROTATE_EVERY == 0L) rotateSend()

        val messageId = nextMessageId
        val iv = ByteArray(FixedEnvelope.IV_BYTES).also(random::nextBytes)
        val cipherLength = plaintext.size + FixedEnvelope.TAG_BYTES
        require(cipherLength <= FixedEnvelope.MAX_CIPHERTEXT_BYTES)
        val aad = FixedEnvelope.aad(sendKeyId, messageId, iv, sendSalt, cipherLength)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)

        nextMessageId += 1L
        persistSend()
        FixedEnvelope.encode(
            FixedEnvelope.Packet(sendKeyId, messageId, iv, ciphertext, sendSalt?.copyOf())
        )
    }

    @Throws(AEADBadTagException::class)
    fun decrypt(envelope: ByteArray): ByteArray = synchronized(lock) {
        val packet = FixedEnvelope.decode(envelope)
        if (!replay.accepts(packet.messageId)) throw SecurityException("Replay rejected")
        val key = receiveKeyFor(packet)
        val aad = FixedEnvelope.aad(
            packet.keyId,
            packet.messageId,
            packet.iv,
            packet.rekeySalt,
            packet.ciphertext.size
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.iv))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(packet.ciphertext)
        replay.mark(packet.messageId)
        persistReceive()
        plaintext
    }

    private fun rotateSend() {
        val salt = ByteArray(FixedEnvelope.SALT_BYTES).also(random::nextBytes)
        val old = sendKey
        sendKey = derive(old, salt, sendDirection, sendKeyId + 1)
        old.fill(0)
        sendKeyId += 1
        sendSalt = salt
    }

    private fun receiveKeyFor(packet: FixedEnvelope.Packet): ByteArray {
        if (packet.keyId == receiveKeyId) return receiveKey
        history.firstOrNull { it.id == packet.keyId }?.let { return it.key }
        if (packet.keyId != receiveKeyId + 1) throw SecurityException("Unexpected key generation")

        val salt = packet.rekeySalt ?: throw SecurityException("Missing re-key salt")
        history.addFirst(KeyVersion(receiveKeyId, receiveKey.copyOf()))
        while (history.size > RETAINED_KEYS) history.removeLast().key.fill(0)

        val old = receiveKey
        receiveKey = derive(old, salt, receiveDirection, packet.keyId)
        old.fill(0)
        receiveKeyId = packet.keyId
        receiveSalt = salt.copyOf()
        return receiveKey
    }

    private fun initialKey(direction: String): ByteArray = HkdfSha256.derive(
        inputKeyMaterial = root,
        salt = MessageDigest.getInstance("SHA-256")
            .digest("monica-key-v2:$scope".toByteArray()),
        info = "monica-key-direction:$direction".toByteArray()
    )

    private fun derive(oldKey: ByteArray, salt: ByteArray, direction: String, id: Int): ByteArray =
        HkdfSha256.derive(
            inputKeyMaterial = oldKey,
            salt = salt,
            info = "monica-key-session:$direction:$id".toByteArray()
        )

    private fun persistSend() {
        store.put(name("send_id"), sendKeyId.toString())
        store.put(name("send_key"), CryptoBox.b64(sendKey))
        store.put(name("send_salt"), sendSalt?.let(CryptoBox::b64))
        store.put(name("send_next"), nextMessageId.toString())
    }

    private fun persistReceive() {
        store.put(name("receive_id"), receiveKeyId.toString())
        store.put(name("receive_key"), CryptoBox.b64(receiveKey))
        store.put(name("receive_salt"), receiveSalt?.let(CryptoBox::b64))
        store.put(name("receive_highest"), replay.highest().toString())
        store.put(name("receive_seen"), replay.snapshot().joinToString(","))
        store.put(
            name("receive_history"),
            history.joinToString(";") { "${it.id}:${CryptoBox.b64(it.key)}" }
        )
    }

    private fun readHistory(): List<KeyVersion> = store.get(name("receive_history"))
        ?.split(';')
        ?.mapNotNull { item ->
            val separator = item.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val id = item.substring(0, separator).toIntOrNull() ?: return@mapNotNull null
            val key = runCatching { CryptoBox.unb64(item.substring(separator + 1)) }.getOrNull()
                ?: return@mapNotNull null
            if (key.size == 32) KeyVersion(id, key) else null
        }
        .orEmpty()

    private fun name(suffix: String) = "session_v2_${scope}_$suffix"

    private fun readBytes(suffix: String): ByteArray? = store.get(name(suffix))?.let {
        runCatching { CryptoBox.unb64(it) }.getOrNull()
    }

    private fun readInt(suffix: String, fallback: Int) =
        store.get(name(suffix))?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

    private fun readLong(suffix: String, fallback: Long) =
        store.get(name(suffix))?.toLongOrNull()?.takeIf { it >= 0L } ?: fallback

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val ROTATE_EVERY = 1000L
        private const val RETAINED_KEYS = 5
    }
}
