package com.pr4nav.jarvis.needle

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object NeedleInstaller {

    private const val TAG = "NeedleInstaller"

    data class InstallationStatus(
        val isInstalled: Boolean,
        val isModelPresent: Boolean,
        val isToolsPresent: Boolean,
        val binaryFile: File,
        val modelFile: File,
        val toolsFile: File,
        val errorMessage: String? = null
    )

    fun getNeedleDir(context: Context): File = File(context.filesDir, "needle").apply { mkdirs() }

    fun installIfNeeded(context: Context): InstallationStatus {
        val dir = getNeedleDir(context)
        val binFile = File(dir, "needle")
        val modelFile = File(dir, "needle2.cact")
        val toolsFile = File(dir, "tools.json")

        try {
            // 1. Copy binary from assets if missing or size differs
            if (!binFile.exists() || binFile.length() == 0L) {
                copyAsset(context, "needle/needle-arm64", binFile)
                binFile.setExecutable(true, false)
            } else {
                binFile.setExecutable(true, false)
            }

            // 2. Copy .cact weights if missing or size differs
            if (!modelFile.exists() || modelFile.length() == 0L) {
                copyAsset(context, "needle/needle2.cact", modelFile)
            }

            // 3. Generate/update tools.json
            val schemaJson = NeedleToolCatalog.generateSchemasJson()
            toolsFile.writeText(schemaJson)

            val installed = binFile.exists() && binFile.canExecute()
            val modelOk = modelFile.exists() && modelFile.length() > 1_000_000L
            val toolsOk = toolsFile.exists() && toolsFile.length() > 50L

            Log.i(TAG, "Needle installation verified: bin=$installed (${binFile.length()}b), model=$modelOk, tools=$toolsOk")
            syncToTermux(binFile)
            return InstallationStatus(installed, modelOk, toolsOk, binFile, modelFile, toolsFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install Needle assets: ${e.message}", e)
            return InstallationStatus(
                isInstalled = binFile.exists(),
                isModelPresent = modelFile.exists(),
                isToolsPresent = toolsFile.exists(),
                binaryFile = binFile,
                modelFile = modelFile,
                toolsFile = toolsFile,
                errorMessage = e.message
            )
        }
    }

    private fun copyAsset(context: Context, assetPath: String, destFile: File) {
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy $assetPath from assets (may be missing or not bundled yet): ${e.message}")
        }
    }

    private fun syncToTermux(binFile: File) {
        kotlin.concurrent.thread {
            try {
                if (binFile.exists() && binFile.canExecute()) {
                    com.pr4nav.jarvis.Shell.root("cp ${binFile.absolutePath} /data/data/com.termux/files/usr/bin/needle && chmod 755 /data/data/com.termux/files/usr/bin/needle", 5000)
                }
            } catch (_: Exception) {}
        }
    }
}
