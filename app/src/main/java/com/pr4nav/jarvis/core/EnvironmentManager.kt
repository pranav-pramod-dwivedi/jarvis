package com.pr4nav.jarvis.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Execution Environment enum representing distinct runtime domains.
 */
enum class ExecutionEnvironment {
    ANDROID_APP,
    TERMUX,
    UBUNTU_PROOT,
    OPENCODE_WORKSPACE,
    SHARED_STORAGE
}

/**
 * Central Environment and Path Authority.
 * Eliminates random hardcoded paths and establishes explicit path ownership across environments.
 */
object EnvironmentManager {

    // Android App Internal Storage
    fun appFilesDir(context: Context): File = context.filesDir
    fun appCacheDir(context: Context): File = context.cacheDir
    fun appModelsDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    fun appKokoroDir(context: Context): File {
        val dir = File(context.filesDir, "kokoro")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Shared External Storage (/sdcard)
    fun sharedStorageDir(): File = Environment.getExternalStorageDirectory()
    fun sharedStoragePath(): String = sharedStorageDir().absolutePath

    // Termux Environment Paths
    const val TERMUX_BASE = "/data/data/com.termux/files"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_USR = "/data/data/com.termux/files/usr"
    const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
    const val TERMUX_TMP = "/data/data/com.termux/files/usr/tmp"

    // Ubuntu PRoot Environment Paths
    const val UBUNTU_BASE = "/data/data/com.termux/files/home/ubuntu"
    const val UBUNTU_ROOT_IN_PROOT = "/root"
    const val UBUNTU_HOME_IN_PROOT = "/home"
    const val UBUNTU_TMP_IN_PROOT = "/tmp"

    // OpenCode Workspace Paths
    const val OPENCODE_WORKSPACE = "/sdcard"

    /**
     * Resolves a canonical path for a target environment.
     */
    fun resolveCanonicalPath(env: ExecutionEnvironment, relativeOrAbsolutePath: String, context: Context? = null): String {
        val trimmed = relativeOrAbsolutePath.trim()
        return when (env) {
            ExecutionEnvironment.ANDROID_APP -> {
                if (trimmed.startsWith("/")) trimmed
                else if (context != null) File(context.filesDir, trimmed).absolutePath
                else trimmed
            }
            ExecutionEnvironment.SHARED_STORAGE -> {
                if (trimmed.startsWith("/sdcard") || trimmed.startsWith("/storage/emulated/0")) trimmed
                else if (trimmed.startsWith("/")) trimmed
                else File(sharedStorageDir(), trimmed).absolutePath
            }
            ExecutionEnvironment.TERMUX -> {
                if (trimmed.startsWith(TERMUX_BASE)) trimmed
                else if (trimmed.startsWith("~")) trimmed.replaceFirst("~", TERMUX_HOME)
                else if (trimmed.startsWith("/")) trimmed
                else "$TERMUX_HOME/$trimmed"
            }
            ExecutionEnvironment.UBUNTU_PROOT -> {
                if (trimmed.startsWith("/")) trimmed
                else if (trimmed.startsWith("~")) trimmed.replaceFirst("~", "/root")
                else "/root/$trimmed"
            }
            ExecutionEnvironment.OPENCODE_WORKSPACE -> {
                if (trimmed.startsWith("/")) trimmed
                else "$OPENCODE_WORKSPACE/$trimmed"
            }
        }
    }

    /**
     * Checks if Termux is installed on the host.
     */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if Ubuntu PRoot directory is present in Termux.
     */
    fun isUbuntuInstalled(): Boolean {
        val f = File(UBUNTU_BASE)
        return f.exists() && f.isDirectory
    }
}
