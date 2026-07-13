package no.blckswan.monicakey

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val STORE_ALIAS = "monica_key_local_store_v1"

class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("monica_key_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(STORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                STORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(name: String) = prefs.edit().remove(name).apply()
}

class AppConfig(context: Context) {
    private val store = SecretStore(context.applicationContext)

    var serverUrl: String
        get() = store.get("server") ?: "http://192.168.43.191:4242"
        set(value) = store.put("server", value.trim().trimEnd('/'))

    var role: Role?
        get() = store.get("role")?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        set(value) = store.put("role", value?.name)

    var channelId: String?
        get() = store.get("channel_id")
        set(value) = store.put("channel_id", value)

    var dataKey: String?
        get() = store.get("data_key")
        set(value) = store.put("data_key", value)

    var claimSecret: String?
        get() = store.get("claim_secret")
        set(value) = store.put("claim_secret", value)

    var revokePrivateKey: String?
        get() = store.get("revoke_private")
        set(value) = store.put("revoke_private", value)

    var revokePublicKey: String?
        get() = store.get("revoke_public")
        set(value) = store.put("revoke_public", value)

    var trackingEnabled: Boolean
        get() = store.get("tracking") == "1"
        set(value) = store.put("tracking", if (value) "1" else "0")

    fun saveHome(home: HomePoint?) {
        if (home == null) {
            store.remove("home")
        } else {
            store.put("home", "${home.latitude},${home.longitude},${home.updatedAt}")
        }
    }

    fun home(): HomePoint? = store.get("home")?.split(',')?.let { parts ->
        if (parts.size != 3) return@let null
        runCatching { HomePoint(parts[0].toDouble(), parts[1].toDouble(), parts[2].toLong()) }.getOrNull()
    }

    fun clearSenderClaimSecret() { claimSecret = null }
}

object CryptoBox {
    private val random = SecureRandom()

    fun randomToken(bytes: Int = 32): String = b64(ByteArray(bytes).also(random::nextBytes))

    fun seal(keyB64: String, plain: ByteArray): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(unb64(keyB64), "AES"),
            GCMParameterSpec(128, nonce)
        )
        return b64(nonce + cipher.doFinal(plain))
    }

    fun open(keyB64: String, packedB64: String): ByteArray {
        val packed = unb64(packedB64)
        require(packed.size > 28) { "Encrypted packet is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(unb64(keyB64), "AES"),
            GCMParameterSpec(128, packed.copyOfRange(0, 12))
        )
        return cipher.doFinal(packed.copyOfRange(12, packed.size))
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    fun unb64(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
}

object RevokeKeys {
    data class PairData(val publicKey: String, val privateKey: String)

    fun generate(): PairData {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        return PairData(CryptoBox.b64(pair.public.encoded), CryptoBox.b64(pair.private.encoded))
    }

    fun sign(privateKeyB64: String, data: String): String {
        val privateKey: PrivateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(CryptoBox.unb64(privateKeyB64)))
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray(Charsets.UTF_8))
        return CryptoBox.b64(signature.sign())
    }
}
