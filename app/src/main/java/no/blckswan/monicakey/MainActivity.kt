package no.blckswan.monicakey

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), AppListener {
    private lateinit var config: AppConfig
    private lateinit var db: TimelineDb
    private lateinit var live: LiveClient

    private lateinit var rootContent: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var roleText: TextView
    private lateinit var coordinateText: TextView
    private lateinit var etaText: TextView
    private lateinit var trackingButton: Button
    private lateinit var inviteButton: Button
    private lateinit var homeButton: Button
    private lateinit var revokeButton: Button
    private lateinit var callButton: Button
    private lateinit var messageInput: EditText
    private lateinit var messageList: LinearLayout
    private lateinit var timelineList: LinearLayout

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            refreshUi()
            if (config.role == Role.PIPPI && config.trackingEnabled) startTracking()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        config.role = Role.valueOf(BuildConfig.APP_ROLE)
        db = TimelineDb(this)
        live = LiveClient.get(this)
        parseJoinUri(intent?.data)
        buildUi()
        AppBus.add(this)
        AppBus.home = config.home()

        if (config.channelId != null && config.dataKey != null) {
            live.connect()
        }
        if (config.role == Role.PIPPI && config.trackingEnabled) {
            requestLocationPermissions()
            startTracking()
        }
        refreshUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseJoinUri(intent.data)
        refreshUi()
    }

    override fun onDestroy() {
        AppBus.remove(this)
        super.onDestroy()
    }

    override fun onStateChanged() = runOnUiThread { refreshUi() }

    override fun onIncomingCall(from: Role) = runOnUiThread {
        AlertDialog.Builder(this)
            .setTitle(if (from == Role.PIPPI) "Pippi ringer" else "Monica ringer")
            .setMessage("Privat kryptert lydsamtale")
            .setPositiveButton("Svar") { _, _ ->
                requestAudioPermission()
                if (AudioCallEngine.start(this)) {
                    live.sendCallSignal("accept")
                    refreshUi()
                }
            }
            .setNegativeButton("Avvis") { _, _ -> live.sendCallSignal("end") }
            .show()
    }

    override fun onCallEnded() = runOnUiThread {
        toast("Samtalen er avsluttet")
        refreshUi()
    }

    private fun parseJoinUri(uri: Uri?) {
        if (uri?.scheme != "monicakey" || uri.host != "join") return
        val server = uri.getQueryParameter("server")
        val channel = uri.getQueryParameter("channel")
        val key = uri.getQueryParameter("key")
        val claim = uri.getQueryParameter("claim")
        if (server.isNullOrBlank() || channel.isNullOrBlank() || key.isNullOrBlank() || claim.isNullOrBlank()) {
            toast("Invitasjonslenken er ufullstendig")
            return
        }
        config.serverUrl = server
        config.role = Role.MONICA
        config.channelId = channel
        config.dataKey = key
        config.claimSecret = claim
        live.claimForMonica { ok, message ->
            toast(if (ok) "Nøkkelen er lagret på Monicas telefon" else message)
            if (ok) live.connect()
            refreshUi()
        }
    }

    private fun buildUi() {
        val frame = FrameLayout(this)
        frame.addView(
            RomanticBackdropView(this),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        rootContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(38))
        }
        scroll.addView(rootContent)
        frame.addView(scroll)
        setContentView(frame)

        val title = text("Monica har nøkkelen\ntil hjertet mitt", 34f, Color.rgb(255, 247, 232), true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
        }
        rootContent.addView(title, full())
        rootContent.addView(text("PRESTEKRAGE • PRIVAT • LIVE", 12f, Color.rgb(243, 199, 118), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }, full(top = 6, bottom = 16))

        val identityCard = card()
        roleText = text("", 18f, Color.WHITE, true)
        statusText = text("", 14f, Color.rgb(225, 232, 226), false)
        identityCard.addView(roleText)
        identityCard.addView(statusText, full(top = 6))
        rootContent.addView(identityCard, full(bottom = 12))

        val liveCard = card()
        liveCard.addView(text("LIVE TIL MONICAS LEILIGHET", 12f, Color.rgb(243, 199, 118), true))
        coordinateText = text("Ingen posisjon ennå", 14f, Color.WHITE, false)
        etaText = text("ETA vises når hjem og posisjon er kjent", 18f, Color.rgb(255, 247, 232), true)
        liveCard.addView(coordinateText, full(top = 8))
        liveCard.addView(etaText, full(top = 10))
        rootContent.addView(liveCard, full(bottom = 12))

        val controls = card()
        trackingButton = actionButton("Start live-logging") { toggleTracking() }
        inviteButton = actionButton("Lag invitasjon til Monica") { createInvite() }
        homeButton = actionButton("Sett denne posisjonen som Monicas hjem") { captureHome() }
        revokeButton = actionButton("Fjern min tilgang") { confirmRevoke() }
        callButton = actionButton("Ring privat") { toggleCall() }
        controls.addView(trackingButton, full(bottom = 8))
        controls.addView(inviteButton, full(bottom = 8))
        controls.addView(homeButton, full(bottom = 8))
        controls.addView(revokeButton, full(bottom = 8))
        controls.addView(callButton)
        rootContent.addView(controls, full(bottom = 12))

        val chatCard = card()
        chatCard.addView(text("MELLOM OSS", 12f, Color.rgb(243, 199, 118), true))
        messageList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatCard.addView(messageList, full(top = 8, bottom = 8))
        messageInput = EditText(this).apply {
            hint = "Skriv til ${if (config.role == Role.MONICA) "Pippi" else "Monica"}"
            setHintTextColor(Color.rgb(150, 160, 153))
            setTextColor(Color.WHITE)
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            background = rounded(Color.argb(150, 9, 13, 11), Color.argb(110, 243, 199, 118))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        chatCard.addView(messageInput, full(bottom = 8))
        chatCard.addView(actionButton("Send kryptert melding") {
            live.sendChat(messageInput.text.toString())
            messageInput.setText("")
            refreshMessages()
        })
        rootContent.addView(chatCard, full(bottom = 12))

        val timelineCard = card()
        timelineCard.addView(text("MIN TIDSLINJE", 12f, Color.rgb(243, 199, 118), true))
        timelineList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timelineCard.addView(timelineList, full(top = 8, bottom = 8))
        val timelineButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        timelineButtons.addView(actionButton("GPX") { shareExport("gpx") }, weight())
        timelineButtons.addView(space(8))
        timelineButtons.addView(actionButton("GeoJSON") { shareExport("geojson") }, weight())
        timelineCard.addView(timelineButtons)
        rootContent.addView(timelineCard, full(bottom = 12))

        val settingsCard = card()
        settingsCard.addView(text("EGEN INFRASTRUKTUR", 12f, Color.rgb(243, 199, 118), true))
        settingsCard.addView(text("Ingen Google Maps, Firebase eller Google-konto. Server: ${config.serverUrl}", 13f, Color.rgb(218, 225, 219), false), full(top = 8))
        settingsCard.addView(actionButton("Bytt serveradresse") { editServer() }, full(top = 10))
        rootContent.addView(settingsCard)
    }

    private fun refreshUi() {
        val role = config.role
        roleText.text = when (role) {
            Role.PIPPI -> "Pippi • Monica holder nøkkelen"
            Role.MONICA -> "Monica • Du holder nøkkelen"
            null -> "Velg telefonens rolle"
        }
        statusText.text = AppBus.status + if (AppBus.connected) "  •  tilkoblet" else ""

        val point = when (role) {
            Role.MONICA -> AppBus.remoteLocation
            Role.PIPPI -> AppBus.localLocation
            null -> null
        }
        coordinateText.text = point?.let {
            String.format(Locale.US, "%.5f, %.5f  •  ±%d m", it.latitude, it.longitude, it.accuracy.toInt())
        } ?: "Ingen live-posisjon ennå"

        val eta = AppBus.eta
        etaText.text = eta?.let {
            val km = it.distanceMeters / 1000.0
            "${String.format(Locale.US, "%.1f", km)} km hjem\n" +
                "Til fots ${it.walkMinutes} min  •  Sykkel ${it.bicycleMinutes} min\n" +
                "Bil ${it.carMinutes} min  •  Kollektiv ${it.transitMinutes} min"
        } ?: "ETA vises når Monica har satt hjem og posisjon mottas"

        trackingButton.visibility = if (role == Role.PIPPI) View.VISIBLE else View.GONE
        trackingButton.text = if (config.trackingEnabled) "Stopp live-logging" else "Start live-logging"
        inviteButton.visibility = if (role == Role.PIPPI) View.VISIBLE else View.GONE
        homeButton.visibility = if (role == Role.MONICA) View.VISIBLE else View.GONE
        revokeButton.visibility = if (role == Role.MONICA && config.revokePrivateKey != null) View.VISIBLE else View.GONE
        callButton.isEnabled = role != null && config.channelId != null
        callButton.text = if (AppBus.callActive) "Avslutt samtale" else "Ring privat"

        if (role == null) showRoleSetup()
        refreshMessages()
        refreshTimeline()
    }

    private fun showRoleSetup() {
        AlertDialog.Builder(this)
            .setTitle("Hvilken telefon er dette?")
            .setMessage("Pippis telefon sender tidslinjen. Monicas telefon mottar invitasjonen og holder den eneste revokasjonsnøkkelen.")
            .setPositiveButton("Pippis telefon") { _, _ ->
                config.role = Role.PIPPI
                refreshUi()
            }
            .setNegativeButton("Monicas telefon") { _, _ ->
                config.role = Role.MONICA
                refreshUi()
            }
            .setCancelable(false)
            .show()
    }

    private fun toggleTracking() {
        if (config.role != Role.PIPPI) return
        if (config.trackingEnabled) {
            config.trackingEnabled = false
            startService(Intent(this, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_STOP))
        } else {
            config.trackingEnabled = true
            requestLocationPermissions()
            startTracking()
        }
        refreshUi()
    }

    private fun startTracking() {
        if (!hasLocation()) return
        val intent = Intent(this, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun createInvite() {
        config.role = Role.PIPPI
        live.ensureSenderChannel { ok, message ->
            if (!ok) {
                toast(message)
                return@ensureSenderChannel
            }
            live.connect()
            val uri = live.invitationUri() ?: run {
                toast("Kunne ikke lage invitasjon")
                return@ensureSenderChannel
            }
            val text = "Monica, denne nøkkelen er din:\n$uri"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Monica Key", uri.toString()))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Du holder nøkkelen")
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Send til Monica"))
        }
    }

    private fun captureHome() {
        if (!hasLocation()) {
            requestLocationPermissions()
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { manager.removeUpdates(this) }
                val home = HomePoint(location.latitude, location.longitude, System.currentTimeMillis())
                live.publishHome(home)
                toast("Monicas leilighet er lagret")
                refreshUi()
            }
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            toast("Slå på posisjon først")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        runCatching { manager.requestSingleUpdate(provider, listener, mainLooper) }
            .onFailure { toast(it.message ?: "Kunne ikke hente posisjon") }
    }

    private fun confirmRevoke() {
        AlertDialog.Builder(this)
            .setTitle("Fjerne tilgangen?")
            .setMessage("Bare denne telefonen kan gjøre dette. Kanalen stenges permanent.")
            .setPositiveButton("Fjern") { _, _ ->
                live.revokeFromMonica { ok, message ->
                    toast(message)
                    if (ok) live.disconnect()
                }
            }
            .setNegativeButton("Behold", null)
            .show()
    }

    private fun toggleCall() {
        if (AppBus.callActive) {
            live.sendCallSignal("end")
            AudioCallEngine.stop()
        } else {
            requestAudioPermission()
            if (AudioCallEngine.start(this)) live.sendCallSignal("ring")
        }
        refreshUi()
    }

    private fun refreshMessages() {
        if (!::messageList.isInitialized) return
        messageList.removeAllViews()
        db.recentMessages(30).forEach { message ->
            val label = if (message.sender == Role.PIPPI) "Pippi" else "Monica"
            val whenText = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp))
            val bubble = text("$label  $whenText\n${message.text}", 14f, Color.WHITE, false).apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(
                    if (message.outgoing) Color.argb(210, 42, 71, 54) else Color.argb(210, 55, 35, 42),
                    Color.argb(100, 243, 199, 118)
                )
            }
            messageList.addView(bubble, full(left = if (message.outgoing) 42 else 0, right = if (message.outgoing) 0 else 42, bottom = 6))
        }
        if (messageList.childCount == 0) {
            messageList.addView(text("Ingen meldinger ennå", 13f, Color.rgb(180, 190, 183), false))
        }
    }

    private fun refreshTimeline() {
        if (!::timelineList.isInitialized) return
        timelineList.removeAllViews()
        val recent = db.recentPoints(8)
        recent.forEach { point ->
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(point.timestamp))
            timelineList.addView(text(
                "$time  •  ${String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)}  •  ±${point.accuracy.toInt()} m",
                12f,
                Color.rgb(224, 232, 225),
                false
            ), full(bottom = 5))
        }
        if (recent.isEmpty()) timelineList.addView(text("Ingen punkter lagret ennå", 13f, Color.rgb(180, 190, 183), false))
    }

    private fun shareExport(format: String) {
        val points = db.recentPoints(5000).asReversed()
        if (points.isEmpty()) {
            toast("Tidslinjen er tom")
            return
        }
        val content = if (format == "gpx") Exporter.gpx(points) else Exporter.geoJson(points)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = if (format == "gpx") "application/gpx+xml" else "application/geo+json"
            putExtra(Intent.EXTRA_SUBJECT, "Monica Key tidslinje")
            putExtra(Intent.EXTRA_TEXT, content)
        }, "Eksporter $format"))
    }

    private fun editServer() {
        val input = EditText(this).apply {
            setText(config.serverUrl)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Egen relay-server")
            .setView(input)
            .setPositiveButton("Lagre") { _, _ ->
                config.serverUrl = input.text.toString()
                live.disconnect()
                live.connect()
                refreshUi()
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private fun requestLocationPermissions() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(needed.toTypedArray())
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
        lineSpacing(0f, 1.08f)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Color.argb(222, 9, 13, 11), Color.argb(90, 243, 199, 118))
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.rgb(255, 247, 232))
        background = rounded(Color.argb(235, 24, 49, 35), Color.argb(160, 243, 199, 118))
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { click() }
    }

    private fun rounded(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = dp(17).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun full(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun space(widthDp: Int) = Space(this).apply { layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
