package com.example.dueloapp.repository

import android.util.Log
import com.example.dueloapp.model.*
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.random.Random
import kotlinx.coroutines.delay

class GameRepository {

    private val TAG = "GameRepository"
    private val database = FirebaseDatabase.getInstance()
    private val roomsRef = database.getReference("rooms")
    private val eventsRef = database.getReference("events")

    private var childEventListener: ChildEventListener? = null
    private var currentRoomId: String? = null

    private val MAX_ROUNDS = 5

    suspend fun joinRoom(code: String, playerName: String): String {
        return try {
            val normalizedCode = code.uppercase().trim()
            val roomRef = roomsRef.child(normalizedCode)
            val snapshot = roomRef.get().await()

            if (snapshot.exists()) {
                // Sala existe - verificar estado
                val state = snapshot.child("state").value as? String
                val currentPlayers = snapshot.child("players").value as? List<*>
                val playersList = currentPlayers?.mapNotNull { it as? String }?.toMutableList() ?: mutableListOf()

                // Si el juego ya terminó o hay 2 jugadores diferentes, reiniciar sala
                if (state == "finished" || playersList.size >= 2) {
                    // Reiniciar sala para nueva partida
                    playersList.clear()
                    playersList.add(playerName)

                    roomRef.updateChildren(mapOf(
                        "players" to playersList,
                        "state" to "lobby",
                        "score" to emptyMap<String, Int>(),
                        "round" to 0
                    )).await()

                    // Limpiar eventos antiguos
                    eventsRef.child(normalizedCode).removeValue().await()

                    Log.d(TAG, "Room reset for new game: $normalizedCode")
                } else {
                    // Agregar jugador si no está ya
                    if (!playersList.contains(playerName)) {
                        playersList.add(playerName)
                        roomRef.child("players").setValue(playersList).await()
                    }
                }

                // Emitir evento de actualización de jugadores
                createEvent(normalizedCode, "PLAYERS_UPDATE", mapOf("players" to playersList))

                // Si hay 2 jugadores, iniciar countdown automáticamente
                if (playersList.size >= 2) {
                    startCountdown(normalizedCode)
                }

                Log.d(TAG, "Joined existing room: $normalizedCode, players: $playersList")
            } else {
                // Crear nueva sala
                val roomData = mapOf(
                    "code" to normalizedCode,
                    "state" to "lobby",
                    "players" to listOf(playerName),
                    "score" to emptyMap<String, Int>(),
                    "round" to 0,
                    "maxRounds" to MAX_ROUNDS,
                    "createdAt" to ServerValue.TIMESTAMP
                )
                roomRef.setValue(roomData).await()

                // Emitir evento inicial de jugadores
                createEvent(normalizedCode, "PLAYERS_UPDATE", mapOf("players" to listOf(playerName)))

                Log.d(TAG, "Created new room: $normalizedCode")
            }

            normalizedCode
        } catch (e: Exception) {
            Log.e(TAG, "Error joining room", e)
            throw e
        }
    }

    private suspend fun startCountdown(roomId: String) {
        // Evitar múltiples countdowns simultáneos
        val roomRef = roomsRef.child(roomId)
        val countdownRef = roomRef.child("countdownStarted")

        // Verificar si ya hay un countdown en progreso
        val snapshot = countdownRef.get().await()
        if (snapshot.value == true) {
            Log.d(TAG, "Countdown already in progress for room: $roomId")
            return
        }

        try {
            // Marcar que el countdown ya empezó
            countdownRef.setValue(true).await()
            Log.d(TAG, "Starting countdown for room: $roomId")

            for (count in 5 downTo 1) {
                createEvent(roomId, "COUNTDOWN", mapOf("count" to count))
                delay(1000)
            }

            // Iniciar juego después del countdown
            startGame(roomId)

            // Limpiar flag
            countdownRef.setValue(false).await()
        } catch (e: Exception) {
            Log.e(TAG, "Error in countdown", e)
            countdownRef.setValue(false).await()
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

            // Dar un pequeño delay antes de generar el primer objetivo
            delay(500)

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

        // Usar ChildEventListener para detectar nuevos eventos
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val type = snapshot.child("type").value as? String ?: ""
                    val payloadSnapshot = snapshot.child("payload")
                    val payload = snapshotToMap(payloadSnapshot)

                    Log.d(TAG, "Event received (child added): $type")
                    trySend(GameEvent(type, payload)).isSuccess
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing child event", e)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase child listener cancelled: ${error.message}")
                close(error.toException())
            }
        }

        childEventListener = listener
        eventsRoomRef.addChildEventListener(listener)
        Log.d(TAG, "Started observing events for room: $roomId")

        awaitClose {
            Log.d(TAG, "Closing event observation")
            eventsRoomRef.removeEventListener(listener)
            childEventListener = null
            currentRoomId = null
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
        try {
            if (currentRoomId != null && childEventListener != null) {
                eventsRef.child(currentRoomId!!).removeEventListener(childEventListener!!)
                Log.d(TAG, "Stopped observing events")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping observation: ${e.message}")
        } finally {
            childEventListener = null
            currentRoomId = null
        }
    }
}