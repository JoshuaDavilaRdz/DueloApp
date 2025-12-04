package com.example.dueloapp.repository

import android.util.Log
import com.example.dueloapp.model.*
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class GameRepository {

    private val TAG = "GameRepository"
    private val database = FirebaseDatabase.getInstance()
    private val roomsRef = database.getReference("rooms")
    private val eventsRef = database.getReference("events")

    private var eventListener: ValueEventListener? = null
    private var currentRoomId: String? = null

    private val MAX_ROUNDS = 5

    suspend fun joinRoom(code: String): String {
        return try {
            // Buscar o crear sala
            val snapshot = roomsRef.orderByChild("code").equalTo(code).get().await()

            val roomId = if (snapshot.exists()) {
                // Sala existe
                snapshot.children.first().key!!
            } else {
                // Crear nueva sala
                val newRoomRef = roomsRef.push()
                val roomData = mapOf(
                    "code" to code,
                    "state" to "lobby",
                    "score" to emptyMap<String, Int>(),
                    "round" to 0,
                    "maxRounds" to MAX_ROUNDS,
                    "createdAt" to ServerValue.TIMESTAMP
                )
                newRoomRef.setValue(roomData).await()
                newRoomRef.key!!
            }

            Log.d(TAG, "Joined room: $roomId")
            roomId
        } catch (e: Exception) {
            Log.e(TAG, "Error joining room", e)
            throw e
        }
    }

    suspend fun startGame(roomId: String): Boolean {
        return try {
            val roomRef = roomsRef.child(roomId)

            // Inicializar juego
            val updates = mapOf(
                "state" to "playing",
                "round" to 1,
                "score" to emptyMap<String, Int>(),
                "maxRounds" to MAX_ROUNDS
            )
            roomRef.updateChildren(updates).await()

            // Crear evento START
            createEvent(roomId, "START", mapOf(
                "score" to emptyMap<String, Int>(),
                "round" to 1,
                "maxRounds" to MAX_ROUNDS
            ))

            // Generar primer objetivo
            spawnTarget(roomId)

            Log.d(TAG, "Game started for room: $roomId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting game", e)
            throw e
        }
    }

    suspend fun hitTarget(roomId: String, spawnId: String, playerName: String): ScoreData? {
        return try {
            val roomRef = roomsRef.child(roomId)
            val snapshot = roomRef.get().await()

            // Obtener estado actual
            val currentScore = snapshot.child("score").value as? Map<String, Any> ?: emptyMap()
            val mutableScore = currentScore.mapValues { (it.value as? Long)?.toInt() ?: 0 }.toMutableMap()
            val currentRound = (snapshot.child("round").value as? Long)?.toInt() ?: 1
            val maxRounds = (snapshot.child("maxRounds").value as? Long)?.toInt() ?: MAX_ROUNDS

            // Actualizar puntuación
            mutableScore[playerName] = (mutableScore[playerName] ?: 0) + 1

            // Guardar en Firebase
            roomRef.child("score").setValue(mutableScore).await()

            val scoreData = ScoreData(
                score = mutableScore,
                winner = playerName,
                round = currentRound,
                maxRounds = maxRounds,
                spawnId = spawnId
            )

            // Crear evento SCORE
            createEvent(roomId, "SCORE", mapOf(
                "score" to mutableScore,
                "winner" to playerName,
                "round" to currentRound,
                "maxRounds" to maxRounds,
                "spawnId" to spawnId
            ))

            // Verificar si el juego terminó
            if (currentRound >= maxRounds) {
                val champion = mutableScore.maxByOrNull { it.value }?.key ?: ""

                createEvent(roomId, "END", mapOf(
                    "champion" to champion,
                    "score" to mutableScore,
                    "roundsPlayed" to currentRound,
                    "maxRounds" to maxRounds
                ))

                roomRef.child("state").setValue("finished").await()
            } else {
                // Siguiente ronda
                val nextRound = currentRound + 1
                roomRef.child("round").setValue(nextRound).await()
                spawnTarget(roomId)
            }

            Log.d(TAG, "Hit registered for $playerName")
            scoreData
        } catch (e: Exception) {
            Log.e(TAG, "Error hitting target", e)
            throw e
        }
    }

    private suspend fun spawnTarget(roomId: String) {
        try {
            val width = 1080
            val height = 1920
            val rMin = 50
            val rMax = 100

            val r = rMin + Random.nextDouble() * (rMax - rMin)
            val margin = r + 16

            val cx = margin + Random.nextDouble() * (width - 2 * margin)
            val cy = margin + Random.nextDouble() * (height - 2 * margin)
            val ttlMs = 2500

            val spawnId = database.reference.push().key ?: System.currentTimeMillis().toString()

            createEvent(roomId, "SPAWN", mapOf(
                "spawnId" to spawnId,
                "cx" to cx,
                "cy" to cy,
                "r" to r,
                "ttlMs" to ttlMs
            ))

            Log.d(TAG, "Spawned target: $spawnId")
        } catch (e: Exception) {
            Log.e(TAG, "Error spawning target", e)
        }
    }

    private suspend fun createEvent(roomId: String, type: String, payload: Map<String, Any>) {
        try {
            val eventRef = eventsRef.child(roomId).push()
            val eventData = mapOf(
                "type" to type,
                "payload" to payload,
                "timestamp" to ServerValue.TIMESTAMP
            )
            eventRef.setValue(eventData).await()
            Log.d(TAG, "Event created: $type for room $roomId")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating event", e)
        }
    }

    fun observeEvents(roomId: String): Flow<GameEvent> = callbackFlow {
        currentRoomId = roomId
        val eventsRoomRef = eventsRef.child(roomId)

        eventListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Obtener solo el último evento
                val lastEvent = snapshot.children.lastOrNull()

                lastEvent?.let { eventSnapshot ->
                    try {
                        val type = eventSnapshot.child("type").value as? String ?: ""
                        val payloadSnapshot = eventSnapshot.child("payload")
                        val payload = snapshotToMap(payloadSnapshot)

                        Log.d(TAG, "Event received: $type")
                        trySend(GameEvent(type, payload)).isSuccess
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing event", e)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase listener cancelled: ${error.message}")
                close(error.toException())
            }
        }

        eventsRoomRef.addValueEventListener(eventListener!!)
        Log.d(TAG, "Started observing events for room: $roomId")

        awaitClose {
            eventListener?.let { eventsRoomRef.removeEventListener(it) }
            eventListener = null
            currentRoomId = null
            Log.d(TAG, "Stopped observing events")
        }
    }

    private fun snapshotToMap(snapshot: DataSnapshot): Map<String, Any> {
        val map = mutableMapOf<String, Any>()

        for (child in snapshot.children) {
            val key = child.key ?: continue
            val value = child.value ?: continue

            map[key] = when (value) {
                is Map<*, *> -> snapshotToMap(child)
                else -> value
            }
        }

        return map
    }

    fun stopObserving() {
        currentRoomId?.let { roomId ->
            eventListener?.let { listener ->
                eventsRef.child(roomId).removeEventListener(listener)
            }
        }
        eventListener = null
        currentRoomId = null
        Log.d(TAG, "Stopped observing")
    }
}