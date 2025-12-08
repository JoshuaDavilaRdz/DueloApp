package com.example.dueloapp

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Inicializar Firebase
        FirebaseApp.initializeApp(this)

        // Habilitar persistencia offline 
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)

        android.util.Log.d("MyApplication", "Firebase initialized successfully")
    }
}