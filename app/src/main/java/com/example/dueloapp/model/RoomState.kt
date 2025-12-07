package com.example.dueloapp.model

data class RoomState(
    val roomId: String = "",
    val code: String = "",
    val players: List<String> = emptyList(),
    val score: Map<String, Int> = emptyMap(),
    val round: Int = 0,
    val maxRounds: Int = 5,
    val gameStarted: Boolean = false,
    val gameEnded: Boolean = false,
    val champion: String? = null,
    val countdown: Int = 0,
    val countdownActive: Boolean = false
)