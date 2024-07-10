package com.example.myapplication

interface ConnectionStatusListener {
    fun updateConnectionStatusUI(isConnected: Boolean)
}