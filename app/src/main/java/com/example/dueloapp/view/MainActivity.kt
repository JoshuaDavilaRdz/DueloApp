package com.example.dueloapp.view

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.dueloapp.databinding.ActivityMainBinding
import com.example.dueloapp.viewmodel.GameViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupObservers()
        setupListeners()
    }

    private fun setupListeners() {
        binding.btnJoinRoom.setOnClickListener {
            val playerName = binding.etPlayerName.text.toString().trim()
            val roomCode = binding.etRoomCode.text.toString().trim().uppercase()

            if (playerName.isEmpty()) {
                Toast.makeText(this, "Por favor ingresa tu nombre", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (roomCode.isEmpty()) {
                Toast.makeText(this, "Por favor ingresa el código de sala", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.playerName = playerName
            viewModel.joinRoom(roomCode)
        }
    }

    private fun setupObservers() {
        viewModel.roomState.observe(this) { roomState ->
            if (roomState.roomId.isNotEmpty()) {
                // Navegar a LobbyActivity
                val intent = Intent(this, LobbyActivity::class.java)
                intent.putExtra("PLAYER_NAME", viewModel.playerName)
                intent.putExtra("ROOM_CODE", roomState.code)
                intent.putExtra("ROOM_ID", roomState.roomId)
                startActivity(intent)
                finish()
            }
        }

        viewModel.loading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnJoinRoom.isEnabled = !isLoading
        }

        viewModel.error.observe(this) { error ->
            if (error.isNotEmpty()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }
}