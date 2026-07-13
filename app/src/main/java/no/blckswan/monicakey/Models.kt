package no.blckswan.monicakey

import java.util.concurrent.CopyOnWriteArrayList

enum class Role { PIPPI, MONICA }

data class LocationPoint(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speedMps: Float = 0f,
    val bearing: Float = 0f
)

data class HomePoint(val latitude: Double, val longitude: Double, val updatedAt: Long)

data class EtaSnapshot(
    val distanceMeters: Double,
    val walkMinutes: Int,
    val bicycleMinutes: Int,
    val carMinutes: Int,
    val transitMinutes: Int,
    val calculatedAt: Long
)

data class ChatMessage(
    val timestamp: Long,
    val sender: Role,
    val text: String,
    val outgoing: Boolean
)

interface AppListener {
    fun onStateChanged() = Unit
    fun onIncomingCall(from: Role) = Unit
    fun onCallEnded() = Unit
}

object AppBus {
    private val listeners = CopyOnWriteArrayList<AppListener>()

    @Volatile var localLocation: LocationPoint? = null
    @Volatile var remoteLocation: LocationPoint? = null
    @Volatile var home: HomePoint? = null
    @Volatile var eta: EtaSnapshot? = null
    @Volatile var connected: Boolean = false
    @Volatile var callActive: Boolean = false
    @Volatile var status: String = "Ikke koblet til"

    fun add(listener: AppListener) { listeners += listener }
    fun remove(listener: AppListener) { listeners -= listener }
    fun changed() = listeners.forEach { it.onStateChanged() }
    fun incomingCall(from: Role) = listeners.forEach { it.onIncomingCall(from) }
    fun callEnded() = listeners.forEach { it.onCallEnded() }
}
