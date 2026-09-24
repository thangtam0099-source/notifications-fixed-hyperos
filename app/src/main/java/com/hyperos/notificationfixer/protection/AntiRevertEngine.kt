package com.hyperos.notificationfixer.protection

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hyperos.notificationfixer.data.local.SettingsDataStore
import com.hyperos.notificationfixer.data.model.AntiRevertInterval
import java.util.concurrent.TimeUnit

class AntiRevertEngine(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        const val WORK_NAME = "hyperos_anti_revert_worker"
    }

    suspend fun scheduleAntiRevert(interval: AntiRevertInterval) {
        val workManager = WorkManager.getInstance(context)
        if (interval == AntiRevertInterval.OFF) {
            workManager.cancelUniqueWork(WORK_NAME)
            settingsDataStore.setAntiRevertInterval(AntiRevertInterval.OFF)
            return
        }

        settingsDataStore.setAntiRevertInterval(interval)
        val intervalMinutes = maxOf(15L, interval.intervalMinutes)
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()

        val workRequest = PeriodicWorkRequestBuilder<RevertCheckWorker>(
            intervalMinutes, TimeUnit.MINUTES,
            5L, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
}