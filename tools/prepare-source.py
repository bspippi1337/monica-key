#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLOUD_RELAY = "https://bspippi1337-monica-key-relay.onrender.com"
LEGACY_RELAY = "http://192.168.43.191:4242"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Fant ikke forventet tekst i {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


main_activity = ROOT / "app/src/main/java/no/blckswan/monicakey/MainActivity.kt"
replace_once(
    main_activity,
    'text("PRESTEKRAGE • PRIVAT • LIVE", 12f',
    'text("PRIVAT • LIVE", 12f',
)

secret_store = ROOT / "app/src/main/java/no/blckswan/monicakey/SecretStore.kt"
replace_once(
    secret_store,
    f'''    var serverUrl: String
        get() = store.get("server") ?: "{LEGACY_RELAY}"
        set(value) = store.put("server", value.trim().trimEnd('/'))''',
    f'''    var serverUrl: String
        get() {{
            val saved = store.get("server")
            return if (saved.isNullOrBlank() || saved == "{LEGACY_RELAY}") {{
                "{CLOUD_RELAY}"
            }} else {{
                saved
            }}
        }}
        set(value) = store.put("server", value.trim().trimEnd('/'))''',
)

live_client = ROOT / "app/src/main/java/no/blckswan/monicakey/LiveClient.kt"
replace_once(
    live_client,
    '''    fun ensureSenderChannel(done: (Boolean, String) -> Unit) {
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
    }''',
    '''    fun ensureSenderChannel(done: (Boolean, String) -> Unit) {
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

        // Deling må fungere selv om relayen akkurat redeployer eller nettet er nede.
        done(true, "Invitasjonen er klar")

        post("/v1/channels", body) { ok, _ ->
            AppBus.status = if (ok) {
                "Privat kanal er klar"
            } else {
                "Invitasjonen er lagret • kobler automatisk når nettet er klart"
            }
            AppBus.changed()
            if (ok) connect()
        }
    }''',
)

replace_once(
    live_client,
    '''    fun claimForMonica(done: (Boolean, String) -> Unit) {
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
    }''',
    '''    fun claimForMonica(done: (Boolean, String) -> Unit) {
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

        // Monica kan fullføre oppsettet selv om Pippi delte mens han var offline.
        val createBody = JSONObject()
            .put("channelId", channel)
            .put("claimSecret", claim)

        post("/v1/channels", createBody) { created, createMessage ->
            if (!created) {
                done(false, createMessage)
                return@post
            }

            val claimBody = JSONObject()
                .put("claimSecret", claim)
                .put("revokePublicKey", publicKey)

            post("/v1/channels/$channel/claim", claimBody) { ok, message ->
                if (ok) config.claimSecret = null
                done(ok, message)
            }
        }
    }''',
)

print("Monica Key source prepared for managed cloud relay")
