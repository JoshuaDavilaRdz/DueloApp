package com.example.dueloapp.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dueloapp.model.*
import com.example.dueloapp.repository.GameRepository
import kotlinx.coroutines.launch

class GameViewModel : ViewModel() {

    private val TAG = "GameViewModel"
    private val repository = GameRepository()

    private val _roomState = MutableLiveData<RoomState>()
    val roomState: LiveData<RoomState> = _roomState

    private val _currentSpawn = MutableLiveData<SpawnData?>()
    val currentSpawn: LiveData<SpawnData?> = _currentSpawn

    private val _lastScore = MutableLiveData<ScoreData?>()
    val lastScore: LiveData<ScoreData?> = _lastScore

    private val _gameEnd = MutableLiveData<EndData?>()
    val gameEnd: LiveData<EndData?> = _gameEnd

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    var playerName: String = ""

    fun setRoomState(roomId: String, code: String) {
        _roomState.value = _roomState.value?.copy(
            roomId = roomId,
            code = code
        ) ?: RoomState(
            roomId = roomId,
            code = code
        )
    }

    fun joinRoom(code: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                val roomId = repository.joinRoom(code)
                _roomState.value = RoomState(roomId = roomId, code = code)
                Log.d(TAG, "Room joined: $roomId")
            } catch (e: Exception) {
                _error.value = "Error al unirse a la sala: ${e.message}"
                Log.e(TAG, "Join room error", e)
            } finally {
                _loading.value = false
            }
        }
    }

    fun startGame() {
        viewModelScope.launch {
            try {
                val roomId = _roomState.value?.roomId ?: return@launch
                _loading.value = true
                repository.startGame(roomId)
                Log.d(TAG, "Game started")
            } catch (e: Exception) {
                _error.value = "Error al iniciar el juego: ${e.message}"
                Log.e(TAG, "Start game error", e)
            } finally {
                _loading.value = false
            }
        }
    }

    fun hitTarget(spawnId: String) {
        viewModelScope.launch {
            try {
                val roomId = _roomState.value?.roomId ?: return@launch
                if (playerName.isEmpty()) return@launch

                val scoreData = repository.hitTarget(roomId, spawnId, playerName)
                _lastScore.value = scoreData
                Log.d(TAG, "Target hit by $playerName")
            } catch (e: Exception) {
                Log.e(TAG, "Hit target error: ${e.message}")
            }
        }
    }

    fun observeGameEvents() {
        viewModelScope.launch {
            try {
                val roomId = _roomState.value?.roomId ?: return@launch

                repository.observeEvents(roomId).collect { event ->
                    Log.d(TAG, "Event received: ${event.type}")
                    handleEvent(event)
                }
            } catch (e: Exception) {
                _error.value = "Error al observar eventos: ${e.message}"
                Log.e(TAG, "Observe events error", e)
            }
        }
    }

    private fun handleEvent(event: GameEvent) {
        when (event.type) {
            "START" -> handleStart(event.payload)
            "SPAWN" -> handleSpawn(event.payload)
            "SCORE" -> handleScore(event.payload)
            "END" -> handleEnd(event.payload)
        }
    }

    private fun handleStart(payload: Map<String, Any>) {
        val score = (payload["score"] as? Map<String, Any>)?.mapValues {
            (it.value as? Number)?.toInt() ?: 0
        } ?: emptyMap()

        val round = (payload["round"] as? Number)?.toInt() ?: 1
        val maxRounds = (payload["maxRounds"] as? Number)?.toInt() ?: 5

        _roomState.value = _roomState.value?.copy(
            score = score,
            round = round,
            maxRounds = maxRounds,
            gameStarted = true,
            gameEnded = false
        )

        Log.d(TAG, "Game started - Round: $round/$maxRounds")
    }

    private fun handleSpawn(payload: Map<String, Any>) {
        val spawnId = payload["spawnId"] as? String ?: return
        val cx = (payload["cx"] as? Number)?.toDouble() ?: return
        val cy = (payload["cy"] as? Number)?.toDouble() ?: return
        val r = (payload["r"] as? Number)?.toDouble() ?: return
        val ttlMs = (payload["ttlMs"] as? Number)?.toInt() ?: 2500

        val spawnData = SpawnData(spawnId, cx, cy, r, ttlMs)
        _currentSpawn.value = spawnData

        Log.d(TAG, "New spawn: $spawnId at ($cx, $cy) radius $r")
    }

    private fun handleScore(payload: Map<String, Any>) {
        val score = (payload["score"] as? Map<String, Any>)?.mapValues {
            (it.value as? Number)?.toInt() ?: 0
        } ?: emptyMap()

        val winner = payload["winner"] as? String ?: ""
        val round = (payload["round"] as? Number)?.toInt() ?: 0
        val maxRounds = (payload["maxRounds"] as? Number)?.toInt() ?: 5
        val spawnId = payload["spawnId"] as? String ?: ""

        val scoreData = ScoreData(score, winner, round, maxRounds, spawnId)
        _lastScore.value = scoreData

        // Actualizar estado de la sala
        _roomState.value = _roomState.value?.copy(
            score = score,
            round = round
        )

        // Limpiar spawn actual
        _currentSpawn.value = null

        Log.d(TAG, "Score updated - Winner: $winner, Round: $round/$maxRounds, Scores: $score")
    }

    private fun handleEnd(payload: Map<String, Any>) {
        val champion = payload["champion"] as? String ?: ""
        val score = (payload["score"] as? Map<String, Any>)?.mapValues {
            (it.value as? Number)?.toInt() ?: 0
        } ?: emptyMap()
        val roundsPlayed = (payload["roundsPlayed"] as? Number)?.toInt() ?: 0
        val maxRounds = (payload["maxRounds"] as? Number)?.toInt() ?: 5

        val endData = EndData(champion, score, roundsPlayed, maxRounds)
        _gameEnd.value = endData

        // Actualizar estado de la sala
        _roomState.value = _roomState.value?.copy(
            gameEnded = true,
            champion = champion,
            score = score
        )

        // Limpiar spawn actual
        _currentSpawn.value = null

        Log.d(TAG, "Game ended - Champion: $champion, Final scores: $score")
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopObserving()
        Log.d(TAG, "ViewModel cleared")
    }
}