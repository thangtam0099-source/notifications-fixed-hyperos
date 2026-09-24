package com.hyperos.notificationfixer.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

sealed class ShizukuState {
    object NotInstalled : ShizukuState()
    object NotRunning : ShizukuState()
    object PermissionNotGranted : ShizukuState()
    object Ready : ShizukuState()
}

object ShizukuManager {
    private const val REQUEST_CODE_SHIZUKU = 1001

    private val _shizukuState = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val shizukuState: StateFlow<ShizukuState> = _shizukuState.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { checkStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { _shizukuState.value = ShizukuState.NotRunning }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        _shizukuState.value = if (grantResult == PackageManager.PERMISSION_GRANTED) ShizukuState.Ready else ShizukuState.PermissionNotGranted
    }

    fun init(context: Context) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            checkStatus()
        } catch (_: Exception) {
            _shizukuState.value = ShizukuState.NotRunning
        }
    }

    fun checkStatus() {
        if (!Shizuku.pingBinder()) {
            _shizukuState.value = ShizukuState.NotRunning
            return
        }

        try {
            _shizukuState.value = if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                ShizukuState.Ready else ShizukuState.PermissionNotGranted
        } catch (_: Exception) {
            _shizukuState.value = ShizukuState.PermissionNotGranted
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) {
            try {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
                }
            } catch (_: Exception) {
                _shizukuState.value = ShizukuState.PermissionNotGranted
            }
        }
    }

    fun isReady(): Boolean = _shizukuState.value == ShizukuState.Ready
}