package com.example.myapplication

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class BluetoothViewModel : ViewModel() {

    private val _isConnected = MutableLiveData<Boolean>()
    val isConnected: LiveData<Boolean> get() = _isConnected

    fun updateConnectionStatus(isConnected: Boolean) {
        _isConnected.postValue(isConnected)
    }
}
