package no.blckswan.monicakey

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class LocationTrackingService : Service() {
    private lateinit var locations: LocationManager
    private lateinit var db: TimelineDb
    private lateinit var config: AppConfig
    private lateinit var live: LiveClient
    private var lastAccepted: LocationPoint? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = System.currentTimeMillis()
            val previous = lastAccepted
            if (previous != null && now - previous.timestamp < 2_500L && location.accuracy >= previous.accuracy) return
            if (location.hasAccuracy() && location.accuracy > 250f) return

            val point = LocationPoint(
                timestamp = location.time.takeIf { it > 0 } ?: now,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy.takeIf { location.hasAccuracy() } ?: 0f,
                speedMps = location.speed.takeIf { location.hasSpeed() } ?: 0f,
                bearing = location.bearing.takeIf { location.hasBearing() } ?: 0f
            )
            lastAccepted = point
            db.insertPoint(point)
            AppBus.localLocation = point
            config.home()?.let { AppBus.eta = EtaEngine.calculate(point, it) }
            live.publishLocation(point)
            AppBus.status = if (AppBus.connected) "Deler live med Monica" else "Logger lokalt, venter på nett"
            AppBus.changed()
            updateNotification(point)
        }

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            AppBus.status = "$provider er slått av"
            AppBus.changed()
        }
    }

    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        db = TimelineDb(this)
        config = AppConfig(this)
        live = LiveClient.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            config.trackingEnabled = false
            stopUpdates()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            AppBus.status = "Lokal logging er stoppet"
            AppBus.changed()
            return START_NOT_STICKY
        }
        if (config.role != Role.PIPPI) {
            stopSelf()
            return START_NOT_STICKY
        }
        config.trackingEnabled = true
        startForeground(NOTIFICATION_ID, notification(null))
        live.connect()
        startUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        stopUpdates()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            AppBus.status = "Posisjonstillatelse mangler"
            AppBus.changed()
            stopSelf()
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.filter { locations.isProviderEnabled(it) }.forEach { provider ->
            runCatching { locations.requestLocationUpdates(provider, 5_000L, 5f, listener) }
        }
        runCatching {
            providers.mapNotNull { locations.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.let(listener::onLocationChanged)
        }
    }

    private fun stopUpdates() = runCatching { locations.removeUpdates(listener) }.getOrNull()

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Nøkkelen live-posisjon",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Synlig varsel mens live-posisjon logges" }
        )
    }

    private fun notification(point: LocationPoint?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Monica har nøkkelen")
        .setContentText(
            if (point == null) "Starter privat tidslinje …"
            else "Live • ±${point.accuracy.toInt()} m • ${db.pointCount()} punkter lokalt"
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0,
            "Stopp logging",
            PendingIntent.getService(
                this,
                1,
                Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification(point: LocationPoint) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(point))
    }

    companion object {
        const val ACTION_START = "no.blckswan.monicakey.START"
        const val ACTION_STOP = "no.blckswan.monicakey.STOP"
        private const val CHANNEL_ID = "monica_key_location"
        private const val NOTIFICATION_ID = 42
    }
}
