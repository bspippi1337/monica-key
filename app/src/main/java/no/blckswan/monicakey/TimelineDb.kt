package no.blckswan.monicakey

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TimelineDb(context: Context) : SQLiteOpenHelper(context, "monica_key.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE points(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                accuracy REAL NOT NULL,
                speed REAL NOT NULL,
                bearing REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                sender TEXT NOT NULL,
                body TEXT NOT NULL,
                outgoing INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_points_ts ON points(ts DESC)")
        db.execSQL("CREATE INDEX idx_messages_ts ON messages(ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertPoint(point: LocationPoint) {
        writableDatabase.insert("points", null, ContentValues().apply {
            put("ts", point.timestamp)
            put("lat", point.latitude)
            put("lon", point.longitude)
            put("accuracy", point.accuracy)
            put("speed", point.speedMps)
            put("bearing", point.bearing)
        })
    }

    fun recentPoints(limit: Int = 500): List<LocationPoint> {
        val result = ArrayList<LocationPoint>()
        readableDatabase.query(
            "points",
            arrayOf("ts", "lat", "lon", "accuracy", "speed", "bearing"),
            null,
            null,
            null,
            null,
            "ts DESC",
            limit.coerceIn(1, 5000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                result += LocationPoint(
                    timestamp = c.getLong(0),
                    latitude = c.getDouble(1),
                    longitude = c.getDouble(2),
                    accuracy = c.getFloat(3),
                    speedMps = c.getFloat(4),
                    bearing = c.getFloat(5)
                )
            }
        }
        return result
    }

    fun insertMessage(message: ChatMessage) {
        writableDatabase.insert("messages", null, ContentValues().apply {
            put("ts", message.timestamp)
            put("sender", message.sender.name)
            put("body", message.text)
            put("outgoing", if (message.outgoing) 1 else 0)
        })
    }

    fun recentMessages(limit: Int = 100): List<ChatMessage> {
        val result = ArrayList<ChatMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("ts", "sender", "body", "outgoing"),
            null,
            null,
            null,
            null,
            "ts DESC",
            limit.coerceIn(1, 1000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                result += ChatMessage(
                    timestamp = c.getLong(0),
                    sender = Role.valueOf(c.getString(1)),
                    text = c.getString(2),
                    outgoing = c.getInt(3) == 1
                )
            }
        }
        return result.asReversed()
    }

    fun pointCount(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM points", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }
}
