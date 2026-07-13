package no.blckswan.monicakey

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class LiveClient private constructor(context: Context) {
    private val app = context.applicationContext
    private val config = AppConfig(app)
    private val db = TimelineDb(app)
    private val main = Handler(Looper.getMainLooper())
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var reconnectAttempt = 0
    @Volatile private var deliberateClose = false

    init {
        AppBus.home = config.home()
    }

    fun connect() {
        val channel = config.channelId ?: return
        val key = config.dataKey ?: return
        val role = config.role ?: return
        if (channel.isBlank() || key.isBlank()) return

        deliberateClose = false
        socket?.cancel()
        val wsBase = config.serverUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val url = "$wsBase/v1/ws?channel=${enc(channel)}&peer=${enc(role.name)}"
        AppBus.status = "Kobler til privat kanal …"
        AppBus.changed()
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                AppBus.connected = true
                AppBus.status = "Privat kanal er live"
                AppBus.changed()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWire(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleWire(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppBus.connected = false
                AppBus.status = if (reason.isBlank()) "Kanalen er lukket" else reason
                AppBus.changed()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppBus.connected = false
                AppBus.status = "Nett nede: ${t.message ?: "ukjent feil"}"
                AppBus.changed()
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        deliberateClose = true
        socket?.close(1000, "Appen koblet fra")
        socket = null
        AppBus.connected = false
        AppBus.changed()
    }

    private fun scheduleReconnect() {
        if (deliberateClose || config.channelId == null) return
        val wait = (1L shl reconnectAttempt.coerceAtMost(5)) * 1000L
        reconnectAttempt++
        main.postDelayed({ connect() }, wait)
    }

    fun ensureSenderChannel(done: (Boolean, String) -> Unit) {
        if (config.role != Role.PIPPI) {
            done(false, "Kun avsendertelefonen oppretter kanalen")
            return
        }
        if (config.channelId == null) config.channelId = CryptoBox.randomToken(18)
        if (config.dataKey == null) config.dataKey = CryptoBox.randomToken(32)
        if (config.claimSecret == null) config.claimSecret = CryptoBox.randomToken(24)

        val body = JSONObject()
            .put("channelId", config.channelId)
            .put("claimSecret", config.claimSecret)
        post("/v1/channels", body, done)
    }

    fun claimForMonica(done: (Boolean, String) -> Unit) {
        val channel = config.channelId
        val claim = config.claimSecret
        if (config.role != Role.MONICA || channel == null || claim == null) {
            done(false, "Invitasjonen mangler kanal eller engangsnøkkel")
            return
        }
        var privateKey = config.revokePrivateKey
        var publicKey = config.revokePublicKey
        if (privateKey == null || publicKey == null) {
            val pair = RevokeKeys.generate()
            privateKey = pair.privateKey
            publicKey = pair.publicKey
            config.revokePrivateKey = privateKey
            config.revokePublicKey = publicKey
        }
        val body = JSONObject()
            .put("claimSecret", claim)
            .put("revokePublicKey", publicKey)
        post("/v1/channels/$channel/claim", body) { ok, message ->
            if (ok) config.claimSecret = null
            done(ok, message)
        }
    }

    fun revokeFromMonica(done: (Boolean, String) -> Unit) {
        val channel = config.channelId
        val privateKey = config.revokePrivateKey
        if (config.role != Role.MONICA || channel == null || privateKey == null) {
            done(false, "Denne telefonen har ikke Monicas revokasjonsnøkkel")
            return
        }
        val timestamp = System.currentTimeMillis()
        val signed = "$channel:$timestamp"
        val body = JSONObject()
            .put("timestamp", timestamp)
            .put("signature", RevokeKeys.sign(privateKey, signed))
        post("/v1/channels/$channel/revoke", body, done)
    }

    fun invitationUri(): Uri? {
        val channel = config.channelId ?: return null
        val key = config.dataKey ?: return null
        val claim = config.claimSecret ?: return null
        return Uri.Builder()
            .scheme("monicakey")
            .authority("join")
            .appendQueryParameter("server", config.serverUrl)
            .appendQueryParameter("channel", channel)
            .appendQueryParameter("key", key)
            .appendQueryParameter("claim", claim)
            .build()
    }

    fun publishLocation(point: LocationPoint) {
        val body = JSONObject()
            .put("lat", point.latitude)
            .put("lon", point.longitude)
            .put("accuracy", point.accuracy.toDouble())
            .put("speed", point.speedMps.toDouble())
            .put("bearing", point.bearing.toDouble())
            .put("timestamp", point.timestamp)
        val home = config.home()
        if (home != null) {
            val eta = EtaEngine.calculate(point, home)
            AppBus.eta = eta
            body.put("eta", etaJson(eta))
        }
        sendEncrypted("location", body)
    }

    fun publishHome(home: HomePoint) {
        config.saveHome(home)
        AppBus.home = home
        sendEncrypted(
            "home",
            JSONObject()
                .put("lat", home.latitude)
                .put("lon", home.longitude)
                .put("updatedAt", home.updatedAt)
        )
        AppBus.changed()
    }

    fun sendChat(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val role = config.role ?: return
        val message = ChatMessage(System.currentTimeMillis(), role, clean, true)
        db.insertMessage(message)
        sendEncrypted("chat", JSONObject().put("text", clean).put("timestamp", message.timestamp))
        AppBus.changed()
    }

    fun sendCallSignal(signal: String) {
        sendEncrypted("call", JSONObject().put("signal", signal))
    }

    fun sendAudioFrame(frame: ByteArray) {
        sendEncrypted("audio", JSONObject().put("pcm", CryptoBox.b64(frame)))
    }

    private fun sendEncrypted(kind: String, body: JSONObject) {
        val key = config.dataKey ?: return
        val role = config.role ?: return
        val inner = JSONObject()
            .put("kind", kind)
            .put("sender", role.name)
            .put("sentAt", System.currentTimeMillis())
            .put("body", body)
        val outer = JSONObject()
            .put("kind", "packet")
            .put("payload", CryptoBox.seal(key, inner.toString().toByteArray(Charsets.UTF_8)))
        val sent = socket?.send(outer.toString()) == true
        if (!sent) {
            AppBus.status = "Pakke venter på nett"
            AppBus.changed()
            connect()
        }
    }

    private fun handleWire(text: String) {
        runCatching {
            val outer = JSONObject(text)
            when (outer.optString("kind")) {
                "claimed" -> {
                    if (config.role == Role.PIPPI) config.clearSenderClaimSecret()
                    AppBus.status = "Monica holder nå nøkkelen"
                    AppBus.changed()
                }
                "revoked" -> {
                    AppBus.status = "Monica har fjernet tilgangen"
                    AppBus.connected = false
                    socket?.close(4001, "Tilgangen er fjernet av Monica")
                    AppBus.changed()
                }
                "packet" -> decryptPacket(outer.getString("payload"))
            }
        }.onFailure {
            AppBus.status = "Ignorerte ugyldig kryptert pakke"
            AppBus.changed()
        }
    }

    private fun decryptPacket(payload: String) {
        val key = config.dataKey ?: return
        val inner = JSONObject(String(CryptoBox.open(key, payload), Charsets.UTF_8))
        val sender = Role.valueOf(inner.getString("sender"))
        if (sender == config.role) return
        val body = inner.getJSONObject("body")
        when (inner.getString("kind")) {
            "location" -> {
                val point = LocationPoint(
                    timestamp = body.optLong("timestamp", System.currentTimeMillis()),
                    latitude = body.getDouble("lat"),
                    longitude = body.getDouble("lon"),
                    accuracy = body.optDouble("accuracy", 0.0).toFloat(),
                    speedMps = body.optDouble("speed", 0.0).toFloat(),
                    bearing = body.optDouble("bearing", 0.0).toFloat()
                )
                AppBus.remoteLocation = point
                body.optJSONObject("eta")?.let { AppBus.eta = etaFromJson(it) }
            }
            "home" -> {
                val home = HomePoint(
                    body.getDouble("lat"),
                    body.getDouble("lon"),
                    body.optLong("updatedAt", System.currentTimeMillis())
                )
                config.saveHome(home)
                AppBus.home = home
                AppBus.localLocation?.let { AppBus.eta = EtaEngine.calculate(it, home) }
            }
            "chat" -> {
                db.insertMessage(
                    ChatMessage(
                        body.optLong("timestamp", System.currentTimeMillis()),
                        sender,
                        body.getString("text"),
                        false
                    )
                )
            }
            "call" -> when (body.getString("signal")) {
                "ring" -> AppBus.incomingCall(sender)
                "accept" -> {
                    AppBus.callActive = true
                    AudioCallEngine.startPlayback(app)
                }
                "end" -> {
                    AudioCallEngine.stop()
                    AppBus.callActive = false
                    AppBus.callEnded()
                }
            }
            "audio" -> AudioCallEngine.acceptFrame(CryptoBox.unb64(body.getString("pcm")))
        }
        AppBus.changed()
    }

    private fun etaJson(eta: EtaSnapshot) = JSONObject()
        .put("distance", eta.distanceMeters)
        .put("walk", eta.walkMinutes)
        .put("bicycle", eta.bicycleMinutes)
        .put("car", eta.carMinutes)
        .put("transit", eta.transitMinutes)
        .put("calculatedAt", eta.calculatedAt)

    private fun etaFromJson(json: JSONObject) = EtaSnapshot(
        distanceMeters = json.getDouble("distance"),
        walkMinutes = json.getInt("walk"),
        bicycleMinutes = json.getInt("bicycle"),
        carMinutes = json.getInt("car"),
        transitMinutes = json.getInt("transit"),
        calculatedAt = json.getLong("calculatedAt")
    )

    private fun post(path: String, json: JSONObject, done: (Boolean, String) -> Unit) {
        val request = Request.Builder()
            .url(config.serverUrl + path)
            .post(json.toString().toRequestBody(jsonType))
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { done(false, e.message ?: "Nettverksfeil") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?: if (it.isSuccessful) "OK" else "HTTP ${it.code}"
                    main.post { done(it.isSuccessful, message) }
                }
            }
        })
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        @Volatile private var instance: LiveClient? = null
        fun get(context: Context): LiveClient = instance ?: synchronized(this) {
            instance ?: LiveClient(context).also { instance = it }
        }
    }
}
