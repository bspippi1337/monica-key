#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Fant ikke forventet tekst i {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


gradle = ROOT / "app/build.gradle.kts"
replace_once(
    gradle,
    '''        buildConfigField("String", "RELAY_URL", "\\\"${relayUrl.replace("\\\\", "\\\\\\\\").replace("\\\"", "\\\\\\\"")}\\\"")
    }''',
    '''        buildConfigField("String", "RELAY_URL", "\\\"${relayUrl.replace("\\\\", "\\\\\\\\").replace("\\\"", "\\\\\\\"")}\\\"")
        buildConfigField("boolean", "SHOWCASE_MODE", "false")
    }''',
)
replace_once(
    gradle,
    '''        create("monica") {
            dimension = "owner"
            applicationId = "no.blckswan.monicakey"
            versionNameSuffix = "-monica"
            buildConfigField("String", "APP_ROLE", "\\\"MONICA\\\"")
            resValue("string", "app_name", "Monica Key")
        }
    }''',
    '''        create("monica") {
            dimension = "owner"
            applicationId = "no.blckswan.monicakey"
            versionNameSuffix = "-monica"
            buildConfigField("String", "APP_ROLE", "\\\"MONICA\\\"")
            resValue("string", "app_name", "Monica Key")
        }

        create("showcase") {
            dimension = "owner"
            applicationId = "no.blckswan.monicakey.showcase"
            versionNameSuffix = "-showcase"
            buildConfigField("String", "APP_ROLE", "\\\"PIPPI\\\"")
            buildConfigField("boolean", "SHOWCASE_MODE", "true")
            resValue("string", "app_name", "Monica Key Showcase")
        }
    }''',
)

activity = ROOT / "app/src/main/java/no/blckswan/monicakey/MainActivity.kt"
replace_once(
    activity,
    '''        live = LiveClient.get(this)
        parseJoinUri(intent?.data)
        buildUi()''',
    '''        live = LiveClient.get(this)
        if (BuildConfig.SHOWCASE_MODE) {
            seedShowcase()
        } else {
            parseJoinUri(intent?.data)
        }
        buildUi()''',
)
replace_once(
    activity,
    '''        if (config.channelId != null && config.dataKey != null) {
            live.connect()
        }
        if (config.role == Role.PIPPI && config.trackingEnabled) {''',
    '''        if (!BuildConfig.SHOWCASE_MODE && config.channelId != null && config.dataKey != null) {
            live.connect()
        }
        if (!BuildConfig.SHOWCASE_MODE && config.role == Role.PIPPI && config.trackingEnabled) {''',
)
replace_once(
    activity,
    '''        setIntent(intent)
        parseJoinUri(intent.data)
        refreshUi()''',
    '''        setIntent(intent)
        if (!BuildConfig.SHOWCASE_MODE) parseJoinUri(intent.data)
        refreshUi()''',
)

seed_function = '''    private fun seedShowcase() {
        config.role = Role.PIPPI
        config.trackingEnabled = false
        config.channelId = "showcase_channel_2026"
        config.dataKey = CryptoBox.randomToken(32)
        config.claimSecret = CryptoBox.randomToken(24)

        val now = System.currentTimeMillis()
        val home = HomePoint(58.85276, 5.73577, now - 3_600_000L)
        val points = listOf(
            LocationPoint(now - 42 * 60_000L, 58.85834, 5.72792, 9f, 1.1f, 145f),
            LocationPoint(now - 31 * 60_000L, 58.85641, 5.73064, 7f, 1.4f, 132f),
            LocationPoint(now - 19 * 60_000L, 58.85462, 5.73316, 6f, 1.2f, 128f),
            LocationPoint(now - 7 * 60_000L, 58.85331, 5.73488, 5f, 0.9f, 121f),
            LocationPoint(now, 58.85294, 5.73542, 4f, 0.5f, 118f)
        )

        if (db.pointCount() == 0L) {
            points.forEach(db::insertPoint)
            listOf(
                ChatMessage(now - 38 * 60_000L, Role.MONICA, "Kor langt vekke e du? ❤️", false),
                ChatMessage(now - 35 * 60_000L, Role.PIPPI, "På vei hjem. Appen passer på resten.", true),
                ChatMessage(now - 18 * 60_000L, Role.MONICA, "Ser deg nærme deg nå 🌼", false),
                ChatMessage(now - 5 * 60_000L, Role.PIPPI, "Snart hos deg.", true)
            ).forEach(db::insertMessage)
        }

        config.saveHome(home)
        AppBus.home = home
        AppBus.localLocation = points.last()
        AppBus.remoteLocation = null
        AppBus.eta = EtaSnapshot(
            distanceMeters = 310.0,
            walkMinutes = 4,
            bicycleMinutes = 1,
            carMinutes = 1,
            transitMinutes = 3,
            calculatedAt = now
        )
        AppBus.connected = true
        AppBus.status = "Showcase • privat forbindelse aktiv"
    }

'''
replace_once(
    activity,
    '''    private fun buildUi() {''',
    seed_function + '''    private fun buildUi() {''',
)

replace_once(
    activity,
    '''    private fun toggleTracking() {
        if (config.role != Role.PIPPI) return''',
    '''    private fun toggleTracking() {
        if (BuildConfig.SHOWCASE_MODE) {
            toast("Showcase: live-logging er demonstrert uten GPS")
            return
        }
        if (config.role != Role.PIPPI) return''',
)
replace_once(
    activity,
    '''    private fun createInvite() {
        config.role = Role.PIPPI''',
    '''    private fun createInvite() {
        if (BuildConfig.SHOWCASE_MODE) {
            val uri = Uri.parse("monicakey://join?showcase=1")
            val text = "Monica, denne nøkkelen er din:\\n$uri"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Monica Key Showcase", uri.toString()))
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Du holder nøkkelen")
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Vis invitasjonen"))
            }.onFailure {
                toast("Showcase-invitasjonen er kopiert")
            }
            return
        }
        config.role = Role.PIPPI''',
)
replace_once(
    activity,
    '''    private fun toggleCall() {
        if (AppBus.callActive) {''',
    '''    private fun toggleCall() {
        if (BuildConfig.SHOWCASE_MODE) {
            toast("Showcase: kryptert samtale")
            return
        }
        if (AppBus.callActive) {''',
)
replace_once(
    activity,
    '''        settingsCard.addView(text("Ingen Google Maps, Firebase eller Google-konto. Server: ${config.serverUrl}", 13f, Color.rgb(218, 225, 219), false), full(top = 8))
        settingsCard.addView(actionButton("Bytt serveradresse") { editServer() }, full(top = 10))''',
    '''        settingsCard.addView(text(
            if (BuildConfig.SHOWCASE_MODE) {
                "Offline showcase • ingen konto, server eller posisjon deles"
            } else {
                "Ingen Google Maps, Firebase eller Google-konto. Server: ${config.serverUrl}"
            },
            13f,
            Color.rgb(218, 225, 219),
            false
        ), full(top = 8))
        if (!BuildConfig.SHOWCASE_MODE) {
            settingsCard.addView(actionButton("Bytt serveradresse") { editServer() }, full(top = 10))
        }''',
)

print("Prepared Monica Key offline showcase")
