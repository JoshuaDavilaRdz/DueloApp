package com.example.dueloapp.view

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.dueloapp.R
import com.example.dueloapp.databinding.ActivityResultBinding
import com.example.dueloapp.viewmodel.GameViewModel

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private val viewModel: GameViewModel by viewModels()

    private lateinit var playerName: String
    private lateinit var roomCode: String
    private lateinit var roomId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtener datos del intent
        playerName = intent.getStringExtra("PLAYER_NAME") ?: ""
        roomCode = intent.getStringExtra("ROOM_CODE") ?: ""
        roomId = intent.getStringExtra("ROOM_ID") ?: ""

        if (playerName.isEmpty() || roomCode.isEmpty() || roomId.isEmpty()) {
            Toast.makeText(this, "Error al cargar resultados", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        viewModel.playerName = playerName

        // Restaurar estado de la sala
        viewModel.setRoomState(roomId, roomCode)

        setupObservers()
        setupListeners()

        // Si no tenemos datos del juego terminado, observar eventos
        if (viewModel.gameEnd.value == null) {
            viewModel.observeGameEvents()
        }
    }

    private fun setupListeners() {
        binding.btnPlayAgain.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        binding.btnExit.setOnClickListener {
            finishAffinity()
        }
    }

    private fun setupObservers() {
        viewModel.gameEnd.observe(this) { endData ->
            endData?.let {
                displayResults(it.champion, it.score, it.roundsPlayed, it.maxRounds)
            }
        }

        viewModel.roomState.observe(this) { roomState ->
            if (roomState.gameEnded && roomState.champion != null) {
                displayResults(
                    roomState.champion,
                    roomState.score,
                    roomState.round,
                    roomState.maxRounds
                )
            }
        }
    }

    private fun displayResults(
        champion: String,
        scores: Map<String, Int>,
        roundsPlayed: Int,
        maxRounds: Int
    ) {
        // Mostrar ganador
        binding.tvWinner.text = "🏆 $champion 🏆"

        // Mostrar rondas jugadas
        binding.tvRoundsPlayed.text = "Rondas jugadas: $roundsPlayed/$maxRounds"

        // Limpiar layout de scores anteriores
        binding.layoutScores.removeAllViews()

        // Ordenar jugadores por puntuación (descendente)
        val sortedScores = scores.entries.sortedByDescending { it.value }

        // Agregar scores dinámicamente
        sortedScores.forEachIndexed { index, entry ->
            val playerScoreView = TextView(this).apply {
                text = "${index + 1}. ${entry.key}: ${entry.value} puntos"
                textSize = 18f
                gravity = Gravity.CENTER

                // Resaltar al ganador
                if (entry.key == champion) {
                    setTextColor(ContextCompat.getColor(context, R.color.accent))
                    setTypeface(null, Typeface.BOLD)
                } else {
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
            }

            binding.layoutScores.addView(playerScoreView)
        }
    }
}