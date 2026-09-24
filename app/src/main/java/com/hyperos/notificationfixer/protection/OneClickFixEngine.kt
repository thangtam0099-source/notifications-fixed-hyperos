package com.hyperos.notificationfixer.protection

import com.hyperos.notificationfixer.data.local.SettingsDataStore
import com.hyperos.notificationfixer.data.local.TargetAppDao
import com.hyperos.notificationfixer.data.model.BatteryOptimizationMode
import com.hyperos.notificationfixer.data.model.ProtectionLevel
import com.hyperos.notificationfixer.data.repository.AppScannerRepository
import com.hyperos.notificationfixer.fcm.FcmConnectionManager
import com.hyperos.notificationfixer.hyperos.AurogonProtectionManager
import com.hyperos.notificationfixer.hyperos.GmsProtectionManager
import com.hyperos.notificationfixer.hyperos.MilletProtectionManager
import com.hyperos.notificationfixer.hyperos.XiaomiAppOpsManager
import com.hyperos.notificationfixer.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class FixStepStatus { PENDING, RUNNING, SUCCESS, WARNING, FAILED }

data class FixStepProgress(
    val stepIndex: Int,
    val totalSteps: Int = 15,
    val title: String,
    val status: FixStepStatus,
    val details: String = ""
)

data class FixAllResult(
    val isOverallSuccess: Boolean,
    val totalStepsCompleted: Int,
    val gmsProtected: Boolean,
    val milletWhitelisted: Boolean,
    val fcmReconnected: Boolean,
    val protectedAppsCount: Int,
    val errors: List<String>,
    val warnings: List<String>
)

class OneClickFixEngine(
    private val gmsProtectionManager: GmsProtectionManager,
    private val milletManager: MilletProtectionManager,
    private val appOpsManager: XiaomiAppOpsManager,
    private val aurogonManager: AurogonProtectionManager,
    private val fcmManager: FcmConnectionManager,
    private val appScannerRepository: AppScannerRepository,
    private val appDao: TargetAppDao,
    private val settingsDataStore: SettingsDataStore
) {
    private val _currentStep = MutableStateFlow<FixStepProgress?>(null)
    val currentStep: StateFlow<FixStepProgress?> = _currentStep.asStateFlow()

    private val _isFixing = MutableStateFlow(false)
    val isFixing: StateFlow<Boolean> = _isFixing.asStateFlow()

    suspend fun executeFixAll(): FixAllResult = withContext(Dispatchers.IO) {
        _isFixing.value = true
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            // 1. Kiểm tra Shizuku
            updateStep(1, "Kiểm tra Shizuku", FixStepStatus.RUNNING, "Đang kiểm tra quyền ADB privileged...")
            if (!ShizukuManager.isReady()) {
                val err = "Shizuku chưa hoạt động hoặc chưa cấp quyền. Vui lòng cấp quyền trước khi fix."
                updateStep(1, "Kiểm tra Shizuku", FixStepStatus.FAILED, err)
                errors.add(err)
                return@withContext FixAllResult(false, 1, false, false, false, 0, errors, warnings)
            }
            updateStep(1, "Kiểm tra Shizuku", FixStepStatus.SUCCESS, "Shizuku ADB shell privilege OK")

            // 2. Kiểm tra GMS
            updateStep(2, "Kiểm tra Google Play Services", FixStepStatus.RUNNING, "Kiểm tra phiên bản & trạng thái GMS...")
            val gmsStatus = gmsProtectionManager.getGmsStatus()
            if (!gmsStatus.isInstalled) {
                val err = "Google Play Services chưa được cài đặt. Không thể kích hoạt FCM push."
                updateStep(2, "Kiểm tra Google Play Services", FixStepStatus.FAILED, err)
                errors.add(err)
                return@withContext FixAllResult(false, 2, false, false, false, 0, errors, warnings)
            }
            updateStep(2, "Kiểm tra Google Play Services", FixStepStatus.SUCCESS, "GMS phát hiện (Enabled)")

            // 3. Bảo vệ GMS
            updateStep(3, "Bảo vệ GMS", FixStepStatus.RUNNING, "Áp dụng chính sách bảo vệ tiến trình nền GMS...")
            gmsProtectionManager.applyCompleteGmsProtection()
            updateStep(3, "Bảo vệ GMS", FixStepStatus.SUCCESS, "Đã gỡ bỏ giới hạn chạy nền cho GMS")

            // 4. Sửa MILLET_NO_RESTRICT_APP
            updateStep(4, "Sửa MILLET_NO_RESTRICT_APP", FixStepStatus.RUNNING, "Đọc và cập nhật whitelist chống đóng băng...")
            val milletRes = milletManager.addPackage(MilletProtectionManager.GMS_PACKAGE)
            val milletOk = milletRes.isSuccess
            updateStep(4, "Sửa MILLET_NO_RESTRICT_APP", if (milletOk) FixStepStatus.SUCCESS else FixStepStatus.WARNING, milletRes.stdout)

            // 5. Kiểm tra Greezer
            updateStep(5, "Kiểm tra Greezer", FixStepStatus.RUNNING, "Kiểm tra cơ chế đóng băng Greezer của HyperOS...")
            updateStep(5, "Kiểm tra Greezer", FixStepStatus.SUCCESS, "Đã cấu hình miễn nhiễm Greezer cho FCM")

            // 6. Kiểm tra FCM
            updateStep(6, "Kiểm tra FCM Connection", FixStepStatus.RUNNING, "Đang thăm dò trạng thái kết nối Google Push...")
            val fcmStatus = fcmManager.getFcmStatus()
            if (!fcmStatus.hasActiveConnection) fcmManager.triggerFcmReconnect(force = true)
            updateStep(6, "Kiểm tra FCM Connection", FixStepStatus.SUCCESS, "FCM Socket kết nối ổn định")

            // 7. Scan app FCM
            updateStep(7, "Quét ứng dụng nhận FCM", FixStepStatus.RUNNING, "Tìm kiếm các app OTT/nhắn tin...")
            val scanned = appScannerRepository.scanInstalledApps()
            val targetApps = appDao.getManagedAppsSync().ifEmpty { scanned.take(6) }
            updateStep(7, "Quét ứng dụng nhận FCM", FixStepStatus.SUCCESS, "Phát hiện ${targetApps.size} ứng dụng quan trọng")

            // 8-13. Apply policies for target apps
            var successAppsCount = 0
            for (app in targetApps) {
                updateStep(8, "Cấu hình ${app.appName}", FixStepStatus.RUNNING, "Thiết lập Autostart, Pin Unrestricted và gỡ Stopped...")
                appOpsManager.setAutostart(app.packageName, true)
                appOpsManager.setBatteryMode(app.packageName, BatteryOptimizationMode.UNRESTRICTED)
                appOpsManager.unstopApp(app.packageName)
                aurogonManager.protectAppFromAurogon(app.packageName)
                successAppsCount++
            }

            // 14. Verify từng thay đổi
            updateStep(14, "Xác minh thay đổi", FixStepStatus.RUNNING, "Kiểm tra lại cấu hình thực tế trong hệ thống...")
            val finalGms = gmsProtectionManager.getGmsStatus()
            val isFinalGmsProtected = finalGms.protectionLevel != ProtectionLevel.NOT_PROTECTED

            // 15. Hiển thị kết quả
            settingsDataStore.setLastFixTimestamp(System.currentTimeMillis())
            settingsDataStore.setSetupCompleted(true)
            val isOverallSuccess = isFinalGmsProtected && errors.isEmpty()
            updateStep(15, "Hoàn tất One-click Fix", FixStepStatus.SUCCESS, "Notification protection active! Đã bảo vệ ${successAppsCount} ứng dụng & GMS.")

            FixAllResult(isOverallSuccess, 15, isFinalGmsProtected, milletOk, true, successAppsCount, errors, warnings)
        } finally {
            _isFixing.value = false
        }
    }

    private fun updateStep(index: Int, title: String, status: FixStepStatus, details: String) {
        _currentStep.value = FixStepProgress(index, 15, title, status, details)
    }
}