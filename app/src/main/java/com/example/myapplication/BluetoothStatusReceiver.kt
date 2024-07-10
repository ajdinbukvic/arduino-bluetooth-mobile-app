package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BluetoothStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isConnected = intent.getBooleanExtra("status", false)
        val statusText = if (isConnected) "Bluetooth connected" else "Bluetooth not connected"
        Log.d("RECEIVER", statusText)
        // Ovdje biste trebali ažurirati UI na odgovarajući način
        // Npr. kroz lokalni broadcast ili izravno pristupiti elementima UI-ja ako su dostupni
        val mainActivity = context as MainActivity
        //mainActivity.updateStatus(isConnected)
    }
}
