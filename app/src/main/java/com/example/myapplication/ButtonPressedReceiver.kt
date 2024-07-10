package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ButtonPressedReceiver() : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("BroadcastReceiver", "Broadcast received")
        val phoneNumber = intent?.getStringExtra("phoneNumber")
        phoneNumber?.let {
            val mainActivity = context as MainActivity
            //mainActivity.makePhoneCall(it)
        }
    }
}