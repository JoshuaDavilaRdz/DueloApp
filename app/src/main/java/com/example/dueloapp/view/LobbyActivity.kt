package com.example.dueloapp.view

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.dueloapp.R
import com.example.dueloapp.databinding.ActivityLobbyBinding
import com.example.dueloapp.viewmodel.GameViewModel

class LobbyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLobbyBinding
    private val viewModel: GameViewModel by viewModels()

    private lateinit var playerName: String
    private lateinit var roomCode: String
    private lateinit var roomId: String

    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLobbyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtener datos del intent
        playerName = intent.getStringExtra("PLAYER_NAME") ?: ""
        roomCode = intent.getStringExtra("ROOM_CODE") ?: ""
        roomId = intent.getStringExtra("ROOM_ID") ?: ""

        if (playerName.isEmpty() || roomCode.isEmpty() || roomId.isEmpty()) {
            Toast.makeText(this, "Error al cargar datos de la sala", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        viewModel.playerName = playerName

        // Configurar UI
        binding.tvRoomCode.text = "Sala: $roomCode"

        // Restaurar estado de la sala en el ViewModel
        viewModel.setRoomState(roomId, roomCode)

        setupObservers()

        // Iniciar observación de eventos
        viewModel.observeGameEvents()
    }

    private fun setupObservers() {
        viewModel.roomState.observe(this) { roomState ->
            // Actualizar lista de jugadores
            updatePlayersList(roomState.players)

            // Mostrar/ocultar mensaje de espera
            if (roomState.players.size < 2) {
                binding.tvWaitingMessage.visibility = View.VISIBLE
            } else {
                binding.tvWaitingMessage.visibility = View.GONE
            }

            // Manejar countdown
            if (roomState.countdownActive) {
                showCountdown(roomState.countdown)
            } else {
                hideCountdown()
            }

            // Navegar al juego cuando inicie
            if (roomState.gameStarted) {
                navigateToGame()
            }
        }

        viewModel.error.observe(this) { error ->
            if (error.isNotEmpty()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updatePlayersList(players: List<String>) {
        binding.layoutPlayersList.removeAllViews()

        players.forEachIndexed { index, player ->
            val playerView = TextView(this).apply {
                text = "${index + 1}. $player"
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))

                // Resaltar el jugador actual
                if (player == playerName) {
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.accent))
                }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
            }

            binding.layoutPlayersList.addView(playerView)
        }

        // Agregar placeholders para jugadores faltantes
        val missingPlayers = 2 - players.size
        for (i in 1..missingPlayers) {
            val placeholderView = TextView(this).apply {
                text = "${players.size + i}. Esperando..."
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                alpha = 0.5f

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
            }

            binding.layoutPlayersList.addView(placeholderView)
        }
    }

    private fun showCountdown(count: Int) {
        binding.tvCountdown.visibility = View.VISIBLE
        binding.tvCountdownLabel.visibility = View.VISIBLE
        binding.tvCountdown.text = count.toString()
        binding.cardPlayers.alpha = 0.3f

        // Animación simple de escala
        binding.tvCountdown.scaleX = 0.5f
        binding.tvCountdown.scaleY = 0.5f
        binding.tvCountdown.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(300)
            .withEndAction {
                binding.tvCountdown.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    private fun hideCountdown() {
        binding.tvCountdown.visibility = View.GONE
        binding.tvCountdownLabel.visibility = View.GONE
        binding.cardPlayers.alpha = 1f
    }

    private fun navigateToGame() {
        if (hasNavigated) {
            return // Evitar navegaciones múltiples
        }
        hasNavigated = true

        val intent = Intent(this, GameActivity::class.java)
        intent.putExtra("PLAYER_NAME", playerName)
        intent.putExtra("ROOM_CODE", roomCode)
        intent.putExtra("ROOM_ID", roomId)
        startActivity(intent)
        finish()
    }
}