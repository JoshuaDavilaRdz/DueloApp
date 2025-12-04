package com.example.dueloapp.model

data class GameEvent(
    val type: String,
    val payload: Map<String, Any>
)

data class SpawnData(
    val spawnId: String,
    val cx: Double,
    val cy: Double,
    val r: Double,
    val ttlMs: Int
)

data class ScoreData(
    val score: Map<String, Int>,
    val winner: String,
    val round: Int,
    val maxRounds: Int,
    val spawnId: String
)

data class EndData(
    val champion: String,
    val score: Map<String, Int>,
    val roundsPlayed: Int,
    val maxRounds: Int
)

data class StartData(
    val score: Map<String, Int>,
    val round: Int,
    val maxRounds: Int
)