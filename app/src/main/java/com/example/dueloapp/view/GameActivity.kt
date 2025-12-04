package com.example.dueloapp.view

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.dueloapp.databinding.ActivityGameBinding
import com.example.dueloapp.viewmodel.GameViewModel

class GameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBinding
    private val viewModel: GameViewModel by viewModels()

    private lateinit var playerName: String
    private lateinit var roomCode: String
    private lateinit var roomId: String

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtener datos del intent
        playerName = intent.getStringExtra("PLAYER_NAME") ?: ""
        roomCode = intent.getStringExtra("ROOM_CODE") ?: ""
        roomId = intent.getStringExtra("ROOM_ID") ?: ""

        if (playerName.isEmpty() || roomCode.isEmpty() || roomId.isEmpty()) {
            Toast.makeText(this, "Error al cargar datos del juego", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        viewModel.playerName = playerName

        // Restaurar estado de la sala
        viewModel.setRoomState(roomId, roomCode)

        setupCanvas()
        setupObservers()

        // Iniciar observación de eventos si no está iniciada
        if (viewModel.currentSpawn.value == null) {
            viewModel.observeGameEvents()
        }
    }

    private fun setupCanvas() {
        binding.gameCanvas.setOnTargetHitListener { spawnId ->
            viewModel.hitTarget(spawnId)
            binding.tvStatus.text = "¡Tocaste!"
        }
    }

    private fun setupObservers() {
        viewModel.roomState.observe(this) { roomState ->
            updateScoreBoard(roomState.score)
            updateRoundInfo(roomState.round, roomState.maxRounds)

            if (roomState.gameEnded) {
                // Pequeño delay antes de ir a resultados
                handler.postDelayed({
                    navigateToResults()
                }, 1000)
            }
        }

        viewModel.currentSpawn.observe(this) { spawn ->
            binding.gameCanvas.setSpawn(spawn)
            if (spawn != null) {
                binding.tvStatus.text = "¡Toca el círculo!"
            }
        }

        viewModel.lastScore.observe(this) { scoreData ->
            scoreData?.let {
                val winnerText = if (it.winner == playerName) {
                    "¡Ganaste esta ronda!"
                } else {
                    "${it.winner} ganó esta ronda"
                }
                binding.tvStatus.text = winnerText

                // Limpiar el mensaje después de 2 segundos
                handler.postDelayed({
                    binding.tvStatus.text = "Preparado..."
                }, 2000)
            }
        }

        viewModel.error.observe(this) { error ->
            if (error.isNotEmpty()) {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateScoreBoard(scores: Map<String, Int>) {
        val playersList = scores.keys.toList()

        if (playersList.size >= 1) {
            val player1 = playersList[0]
            binding.tvPlayer1Name.text = player1
            binding.tvPlayer1Score.text = scores[player1]?.toString() ?: "0"
        }

        if (playersList.size >= 2) {
            val player2 = playersList[1]
            binding.tvPlayer2Name.text = player2
            binding.tvPlayer2Score.text = scores[player2]?.toString() ?: "0"
        }
    }

    private fun updateRoundInfo(round: Int, maxRounds: Int) {
        binding.tvRoundInfo.text = "Ronda $round/$maxRounds"
    }

    private fun navigateToResults() {
        val intent = Intent(this, ResultActivity::class.java)
        intent.putExtra("PLAYER_NAME", playerName)
        intent.putExtra("ROOM_CODE", roomCode)
        intent.putExtra("ROOM_ID", roomId)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}