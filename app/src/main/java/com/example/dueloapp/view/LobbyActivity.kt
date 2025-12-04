package com.example.dueloapp.view

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.dueloapp.databinding.ActivityLobbyBinding
import com.example.dueloapp.viewmodel.GameViewModel

class LobbyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLobbyBinding
    private val viewModel: GameViewModel by viewModels()

    private lateinit var playerName: String
    private lateinit var roomCode: String
    private lateinit var roomId: String

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
        binding.tvPlayerName.text = "Jugador: $playerName"

        // Restaurar estado de la sala en el ViewModel
        viewModel.setRoomState(roomId, roomCode)

        setupObservers()
        setupListeners()

        // Iniciar observación de eventos
        viewModel.observeGameEvents()
    }

    private fun setupListeners() {
        binding.btnStartGame.setOnClickListener {
            viewModel.startGame()
        }
    }

    private fun setupObservers() {
        viewModel.roomState.observe(this) { roomState ->
            if (roomState.gameStarted) {
                // Navegar a GameActivity
                val intent = Intent(this, GameActivity::class.java)
                intent.putExtra("PLAYER_NAME", playerName)
                intent.putExtra("ROOM_CODE", roomCode)
                intent.putExtra("ROOM_ID", roomId)
                startActivity(intent)
                finish()
            }
        }

        viewModel.error.observe(this) { error ->
            if (error.isNotEmpty()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }
}