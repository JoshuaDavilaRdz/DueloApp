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

    // 1. Inicializamos la base de datos PRIMERO
    private val database = FirebaseDatabase.getInstance()

    // 2. Luego definimos las referencias que dependen de 'database'
    private val roomsRef = database.getReference("rooms")
    private val eventsRef = database.getReference("events")
    private val historyRef = database.getReference("history") // Nueva referencia para el historial

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
        val roomRef = roomsRef.child(roomId)
        val countdownRef = roomRef.child("countdownStarted")

        val snapshot = countdownRef.get().await()
        if (snapshot.value == true) {
            Log.d(TAG, "Countdown already in progress for room: $roomId")
            return
        }

        try {
            countdownRef.setValue(true).await()
            Log.d(TAG, "Starting countdown for room: $roomId")

            for (count in 5 downTo 1) {
                createEvent(roomId, "COUNTDOWN", mapOf("count" to count))
                delay(1000)
            }

            startGame(roomId)
            countdownRef.setValue(false).await()
        } catch (e: Exception) {
            Log.e(TAG, "Error in countdown", e)
            countdownRef.setValue(false).await()
        }
    }

    suspend fun startGame(roomId: String): Boolean {
        return try {
            val roomRef = roomsRef.child(roomId)

            val updates = mapOf(
                "state" to "playing",
                "round" to 1,
                "score" to emptyMap<String, Int>(),
                "maxRounds" to MAX_ROUNDS
            )
            roomRef.updateChildren(updates).await()

            createEvent(roomId, "START", mapOf(
                "score" to emptyMap<String, Int>(),
                "round" to 1,
                "maxRounds" to MAX_ROUNDS
            ))

            delay(500)
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

                // Esta parte nos permite guardar el historial (|-/)
                val currentState = snapshot.child("state").value as? String
                if (currentState != "finished") {
                    // Obtenemos el código de la sala para guardarlo en el historial
                    val code = snapshot.child("code").value as? String ?: "???"
                    saveGameHistory(code, champion, mutableScore, maxRounds)
                }

                createEvent(roomId, "END", mapOf(
                    "champion" to champion,
                    "score" to mutableScore,
                    "roundsPlayed" to currentRound,
                    "maxRounds" to maxRounds
                ))

                roomRef.child("state").setValue("finished").await()
            } else {
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

    private suspend fun saveGameHistory(roomCode: String, winner: String, scores: Map<String, Int>, rounds: Int) {
        try {
            val historyId = historyRef.push().key ?: return
            val historyItem = GameHistory(
                id = historyId,
                roomCode = roomCode,
                timestamp = System.currentTimeMillis(),
                winner = winner,
                scores = scores,
                roundsPlayed = rounds
            )
            historyRef.child(historyId).setValue(historyItem).await()
            Log.d(TAG, "Game history saved: $historyId")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving history", e)
        }
    }
    //Ver historial más chido xd
    suspend fun getGameHistory(): List<GameHistory> {
        return try {
            Log.d(TAG, "--- Iniciando lectura de historial ---")

            // 1. Obtiene los datos
            val snapshot = historyRef.orderByChild("timestamp").limitToLast(20).get().await()
            Log.d(TAG, "Snapshot recibido. Cantidad de hijos: ${snapshot.childrenCount}")

            val historyList = mutableListOf<GameHistory>()

            // 2. Intenta convertir cada hijo
            for (child in snapshot.children) {
                try {
                    // Imprimimos el JSON crudo para ver qué llega
                    Log.d(TAG, "Procesando hijo: ${child.key}, Valor: ${child.value}")

                    val item = child.getValue(GameHistory::class.java)

                    if (item != null) {
                        historyList.add(item)
                        Log.d(TAG, "-> Item convertido con éxito: ${item.winner}")
                    } else {
                        Log.e(TAG, "-> ERROR: El item es NULL tras convertir. Revisa GameHistory.kt")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "-> ERROR al convertir item individual: ${e.message}")
                }
            }

            Log.d(TAG, "--- Lectura finalizada. Items válidos: ${historyList.size} ---")

            // Invertimos para ver el más reciente arriba siempre
            historyList.reversed()
        } catch (e: Exception) {
            Log.e(TAG, "ERROR CRÍTICO leyendo historial", e)
            emptyList()
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