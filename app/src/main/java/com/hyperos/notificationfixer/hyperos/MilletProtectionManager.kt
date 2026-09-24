package com.hyperos.notificationfixer.hyperos

import com.hyperos.notificationfixer.data.local.SafeModeDao
import com.hyperos.notificationfixer.data.model.MilletStatus
import com.hyperos.notificationfixer.data.model.SafeModeSnapshot
import com.hyperos.notificationfixer.shizuku.CommandResult
import com.hyperos.notificationfixer.shizuku.ShellCommandExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MilletProtectionManager(
    private val shellExecutor: ShellCommandExecutor,
    private val safeModeDao: SafeModeDao? = null
) {
    companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
        const val KEY_MILLET_SETTING = "MILLET_NO_RESTRICT_APP"
        const val UNSUPPORTED_MESSAGE = "Không thể sửa MILLET_NO_RESTRICT_APP trên phiên bản HyperOS này."
    }

    private var activeNamespace: String? = null // "system" or "global"

    suspend fun readMilletWhitelist(): MilletStatus = withContext(Dispatchers.IO) {
        val systemResult = shellExecutor.execute("settings get system $KEY_MILLET_SETTING", "Read Millet (System)")
        if (systemResult.isSuccess && isValidMilletOutput(systemResult.stdout)) {
            activeNamespace = "system"
            return@withContext parseMilletStatus(systemResult.stdout)
        }

        val globalResult = shellExecutor.execute("settings get global $KEY_MILLET_SETTING", "Read Millet (Global)")
        if (globalResult.isSuccess && isValidMilletOutput(globalResult.stdout)) {
            activeNamespace = "global"
            return@withContext parseMilletStatus(globalResult.stdout)
        }

        val dumpsysResult = shellExecutor.execute("dumpsys millet", "Read Millet Dumpsys")
        if (dumpsysResult.isSuccess && dumpsysResult.stdout.contains("whitelist", ignoreCase = true)) {
            val extracted = extractPackagesFromDumpsys(dumpsysResult.stdout)
            if (extracted.isNotEmpty()) {
                activeNamespace = "system"
                return@withContext MilletStatus(
                    whitelist = extracted,
                    isGmsWhitelisted = extracted.contains(GMS_PACKAGE),
                    isSupported = true,
                    rawOutput = dumpsysResult.stdout
                )
            }
        }

        if (systemResult.exitCode != 0 && globalResult.exitCode != 0) {
            return@withContext MilletStatus(
                whitelist = emptyList(),
                isGmsWhitelisted = false,
                isSupported = false,
                errorMessage = UNSUPPORTED_MESSAGE,
                rawOutput = "${systemResult.stderr}\n${globalResult.stderr}".trim()
            )
        }

        activeNamespace = "system"
        MilletStatus(whitelist = emptyList(), isGmsWhitelisted = false, isSupported = true, rawOutput = systemResult.stdout)
    }

    suspend fun addPackage(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        val currentStatus = readMilletWhitelist()
        if (!currentStatus.isSupported) {
            return@withContext CommandResult("settings put", -1, "", UNSUPPORTED_MESSAGE, false, UNSUPPORTED_MESSAGE)
        }

        val currentList = currentStatus.whitelist.toMutableList()
        backupOriginalWhitelist(currentStatus.rawOutput)

        if (currentList.contains(packageName)) {
            return@withContext CommandResult("check $packageName", 0, "Package $packageName đã tồn tại trong MILLET_NO_RESTRICT_APP", "", true)
        }

        currentList.add(packageName)
        val delimiter = if (currentStatus.rawOutput.contains(";")) ";" else ","
        val newContent = currentList.joinToString(delimiter)

        val targetNamespace = activeNamespace ?: "system"
        val writeCmd = "settings put $targetNamespace $KEY_MILLET_SETTING \"$newContent\""
        val writeResult = shellExecutor.execute(writeCmd, "Add $packageName to Millet")

        if (!writeResult.isSuccess) {
            return@withContext CommandResult(writeCmd, writeResult.exitCode, writeResult.stdout, writeResult.stderr, false, UNSUPPORTED_MESSAGE)
        }

        val verifyStatus = readMilletWhitelist()
        if (!verifyStatus.whitelist.contains(packageName)) {
            return@withContext CommandResult(writeCmd, -4, verifyStatus.rawOutput, "Ghi thành công nhưng kiểm tra lại không tìm thấy $packageName", false, "Xác minh thất bại")
        }

        CommandResult(writeCmd, 0, "Đã thêm $packageName vào MILLET_NO_RESTRICT_APP thành công.", "", true)
    }

    suspend fun removePackage(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        val currentStatus = readMilletWhitelist()
        val currentList = currentStatus.whitelist.toMutableList()
        currentList.remove(packageName)
        val newContent = currentList.joinToString(",")
        val targetNamespace = activeNamespace ?: "system"
        shellExecutor.execute("settings put $targetNamespace $KEY_MILLET_SETTING \"$newContent\"", "Remove $packageName from Millet")
    }

    suspend fun restoreOriginalWhitelist(): CommandResult = withContext(Dispatchers.IO) {
        val snapshot = safeModeDao?.getLatestSnapshot()
        val original = snapshot?.originalMilletWhitelist ?: return@withContext CommandResult("restore", 0, "Không có snapshot ban đầu", "", true)
        val targetNamespace = activeNamespace ?: "system"
        shellExecutor.execute("settings put $targetNamespace $KEY_MILLET_SETTING \"$original\"", "Restore Original Millet Whitelist")
    }

    private suspend fun backupOriginalWhitelist(raw: String) {
        try {
            if (safeModeDao?.getLatestSnapshot() == null) {
                safeModeDao?.saveSnapshot(SafeModeSnapshot("init_${System.currentTimeMillis()}", System.currentTimeMillis(), raw, "[]", "{}"))
            }
        } catch (_: Exception) {}
    }

    private fun isValidMilletOutput(output: String): Boolean = output.trim().isNotBlank() && output.trim() != "null"

    private fun parseMilletStatus(raw: String): MilletStatus {
        val clean = raw.trim()
        if (clean.isBlank() || clean == "null") return MilletStatus(whitelist = emptyList(), isGmsWhitelisted = false, isSupported = true, rawOutput = raw)
        val delimiter = if (clean.contains(";")) ";" else if (clean.contains(",")) "," else "\\s+"
        val list = clean.split(Regex(delimiter)).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return MilletStatus(whitelist = list, isGmsWhitelisted = list.contains(GMS_PACKAGE), isSupported = true, rawOutput = raw)
    }

    private fun extractPackagesFromDumpsys(output: String): List<String> {
        val packages = mutableListOf<String>()
        val packageRegex = Regex("([a-zA-Z0-9_]+\\.[a-zA-Z0-9_]+[a-zA-Z0-9_.]*)")
        for (line in output.lines()) {
            if (line.contains("whitelist", true) || line.contains("no_restrict", true)) {
                packageRegex.findAll(line).forEach { match ->
                    if (match.value.contains(".")) packages.add(match.value)
                }
            }
        }
        return packages.distinct()
    }
}