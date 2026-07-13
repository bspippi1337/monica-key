#!/usr/bin/env python3
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELAY_URL = os.environ.get(
    "MONICA_RELAY_URL",
    "https://relay-not-configured.invalid",
).strip().rstrip("/")


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
    '''        val title = text("Monica har nøkkelen\\ntil hjertet mitt", 34f, Color.rgb(255, 247, 232), true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
        }
        rootContent.addView(title, full())
        rootContent.addView(text("PRESTEKRAGE • PRIVAT • LIVE", 12f, Color.rgb(243, 199, 118), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }, full(top = 6, bottom = 16))''',
    '''        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(20), dp(18), dp(17))
            background = rounded(
                Color.argb(238, 5, 10, 8),
                Color.argb(170, 243, 199, 118)
            )
            elevation = dp(8).toFloat()
        }
        val heroTitle = if (BuildConfig.APP_ROLE == "MONICA") {
            "Du holder nøkkelen\\ntil hjertet mitt"
        } else {
            "Monica har nøkkelen\\ntil hjertet mitt"
        }
        val title = text(heroTitle, 31f, Color.rgb(255, 249, 238), true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        }
        hero.addView(title, full())
        hero.addView(text("PRIVAT • LIVE", 12f, Color.rgb(243, 199, 118), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        }, full(top = 9))
        rootContent.addView(hero, full(bottom = 16))''',
)

replace_once(
    main_activity,
    '''            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Du holder nøkkelen")
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Send til Monica"))''',
    '''            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Du holder nøkkelen")
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Send til Monica"))
            }.onFailure {
                toast("Invitasjonslenken er kopiert til utklippstavlen")
            }''',
)

secret_store = ROOT / "app/src/main/java/no/blckswan/monicakey/SecretStore.kt"
replace_once(
    secret_store,
    '''    var serverUrl: String
        get() = store.get("server") ?: "http://192.168.43.191:4242"
        set(value) = store.put("server", value.trim().trimEnd('/'))''',
    '''    var serverUrl: String
        get() {
            val saved = store.get("server")
            val obsolete = setOf(
                "http://192.168.43.191:4242",
                "https://bspippi1337-monica-key-relay.onrender.com",
                "https://relay-not-configured.invalid"
            )
            return if (saved.isNullOrBlank() || saved in obsolete) {
                BuildConfig.RELAY_URL
            } else {
                saved
            }
        }
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

        // Deling må aldri blokkeres av midlertidig nett- eller relayfeil.
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

backdrop = ROOT / "app/src/main/java/no/blckswan/monicakey/RomanticBackdropView.kt"
replace_once(
    backdrop,
    '''        drawDaisy(canvas, width * 0.06f, height * 0.08f, width * 0.17f, -0.2f)
        drawDaisy(canvas, width * 0.94f, height * 0.13f, width * 0.15f, 0.25f)''',
    '''        drawDaisy(canvas, width * 0.02f, height * 0.04f, width * 0.12f, -0.2f)
        drawDaisy(canvas, width * 0.98f, height * 0.18f, width * 0.12f, 0.25f)''',
)
replace_once(
    backdrop,
    '''            paint.color = if (i % 3 == 0) Color.rgb(255, 245, 225) else Color.rgb(255, 253, 244)''',
    '''            paint.color = if (i % 3 == 0) {
                Color.argb(215, 255, 245, 225)
            } else {
                Color.argb(225, 255, 253, 244)
            }''',
)

print(f"Prepared Android source for relay {RELAY_URL}")
