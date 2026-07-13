package no.blckswan.monicakey

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object EtaEngine {
    fun calculate(from: LocationPoint, home: HomePoint): EtaSnapshot {
        val straight = haversineMeters(from.latitude, from.longitude, home.latitude, home.longitude)
        val movingSpeed = (from.speedMps * 3.6f).coerceAtLeast(0f)

        val walkingDistanceKm = straight * 1.18 / 1000.0
        val bicycleDistanceKm = straight * 1.12 / 1000.0
        val roadDistanceKm = straight * 1.25 / 1000.0
        val transitDistanceKm = straight * 1.30 / 1000.0

        val walkKmh = if (movingSpeed in 2f..8f) movingSpeed.toDouble() else 4.8
        val bicycleKmh = if (movingSpeed in 8f..30f) movingSpeed.toDouble() else 16.0
        val carKmh = if (movingSpeed > 30f) movingSpeed.toDouble().coerceAtMost(90.0) else 35.0

        return EtaSnapshot(
            distanceMeters = straight,
            walkMinutes = minutes(walkingDistanceKm, walkKmh),
            bicycleMinutes = minutes(bicycleDistanceKm, bicycleKmh),
            carMinutes = minutes(roadDistanceKm, carKmh, fixedMinutes = 2),
            transitMinutes = minutes(transitDistanceKm, 24.0, fixedMinutes = 8),
            calculatedAt = System.currentTimeMillis()
        )
    }

    private fun minutes(distanceKm: Double, kmh: Double, fixedMinutes: Int = 0): Int {
        if (distanceKm < 0.03) return 0
        return (ceil(distanceKm / kmh * 60.0).toInt() + fixedMinutes).coerceAtLeast(1)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * radius * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
