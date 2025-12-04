package com.example.dueloapp.model

data class RoomState(
    val roomId: String = "",
    val code: String = "",
    val score: Map<String, Int> = emptyMap(),
    val round: Int = 0,
    val maxRounds: Int = 5,
    val gameStarted: Boolean = false,
    val gameEnded: Boolean = false,
    val champion: String? = null
)