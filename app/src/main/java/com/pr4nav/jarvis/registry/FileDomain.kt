package com.pr4nav.jarvis.registry

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pr4nav.jarvis.Fs
import java.io.File

object FileDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        CapabilityDef(
            id = "file.list",
            category = "filesystem",
            name = "List Files in Directory",
            description = "List files and directories in the specified path",
            aliases = listOf("list files", "ls files", "show files in", "directory contents"),
            optionalParams = listOf("path"),
            backend = BackendType.FILESYSTEM,
            execute = { _, params ->
                val path = (params["path"] as? String) ?: "/storage/emulated/0"
                val items = Fs.list(path)
                val summary = if (items.isEmpty()) "📁 Empty directory: $path"
                else "📁 Found ${items.size} files in $path (e.g. ${items.take(4).joinToString { it.name }})"
                CapabilityExecutionResult.ok(summary, items)
            }
        ),

        CapabilityDef(
            id = "file.read",
            category = "filesystem",
            name = "Read File Content",
            description = "Read text content of a file",
            aliases = listOf("read file", "cat file", "open file content", "view file"),
            requiredParams = listOf("path"),
            backend = BackendType.FILESYSTEM,
            execute = { _, params ->
                val path = (params["path"] as? String) ?: ""
                if (path.isBlank()) CapabilityExecutionResult.fail("File path required.")
                else {
                    try {
                        val content = Fs.read(path)
                        CapabilityExecutionResult.ok("📄 Read ${content.length} chars from $path:\n${content.take(300)}...", content)
                    } catch (e: Exception) {
                        CapabilityExecutionResult.fail("Failed reading file: ${e.message}")
                    }
                }
            }
        ),

        CapabilityDef(
            id = "file.write",
            category = "filesystem",
            name = "Write File Content",
            description = "Write or overwrite text content to a file",
            aliases = listOf("write to file", "save file", "write file"),
            requiredParams = listOf("path", "content"),
            risk = RiskLevel.MEDIUM,
            backend = BackendType.FILESYSTEM,
            execute = { _, params ->
                val path = (params["path"] as? String) ?: ""
                val content = (params["content"] as? String) ?: ""
                if (path.isBlank()) CapabilityExecutionResult.fail("File path required.")
                else {
                    try {
                        Fs.write(path, content)
                        CapabilityExecutionResult.ok("💾 Successfully wrote ${content.length} bytes to $path.")
                    } catch (e: Exception) {
                        CapabilityExecutionResult.fail("Failed writing file: ${e.message}")
                    }
                }
            }
        ),

        CapabilityDef(
            id = "file.search",
            category = "filesystem",
            name = "Search Files",
            description = "Search for files by query name or extension",
            aliases = listOf("find file", "search for file", "find all files", "locate file", "find files matching"),
            optionalParams = listOf("query", "path"),
            backend = BackendType.FILESYSTEM,
            execute = { _, params ->
                val q = (params["query"] as? String) ?: (params["pattern"] as? String) ?: ""
                val path = (params["path"] as? String) ?: "/storage/emulated/0"
                val results = Fs.search(path, q, 10)
                if (results.isEmpty()) CapabilityExecutionResult.ok("🔍 No files matching \"$q\" found in $path.")
                else CapabilityExecutionResult.ok("🔍 Found ${results.size} files matching \"$q\":\n${results.joinToString("\n") { "• ${it.path}" }}", results)
            }
        ),

        CapabilityDef(
            id = "file.stat",
            category = "filesystem",
            name = "File Metadata / Stat",
            description = "Inspect file existence, size, and modified timestamp",
            aliases = listOf("file info", "file size", "stat file"),
            requiredParams = listOf("path"),
            backend = BackendType.FILESYSTEM,
            execute = { _, params ->
                val path = (params["path"] as? String) ?: ""
                val f = File(path)
                if (!f.exists()) CapabilityExecutionResult.fail("File does not exist: $path")
                else CapabilityExecutionResult.ok("📄 $path: Size: ${f.length()} bytes, Directory: ${f.isDirectory}, Modified: ${java.util.Date(f.lastModified())}")
            }
        ),

        CapabilityDef(
            id = "file.delete",
            category = "filesystem",
            name = "Delete File",
            description = "Permanently remove a file or directory",
            aliases = listOf("delete file", "remove file", "rm file"),
            requiredParams = listOf("path"),
            risk = RiskLevel.HIGH,
            requiresConfirmation = true,
            backend = BackendType.FILESYSTEM,
            execute = { _, params ->
                val path = (params["path"] as? String) ?: ""
                val confirmed = params["confirmed"] as? Boolean ?: false
                if (!confirmed) {
                    CapabilityExecutionResult.confirmationRequired("file.delete", "Are you sure you want to permanently delete $path?")
                } else {
                    try {
                        Fs.delete(path)
                        CapabilityExecutionResult.ok("🗑️ Deleted $path.")
                    } catch (e: Exception) {
                        CapabilityExecutionResult.fail("Failed to delete $path: ${e.message}")
                    }
                }
            }
        ),

        CapabilityDef(
            id = "file.downloads.open",
            category = "filesystem",
            name = "Open Downloads Directory",
            description = "Show the device Downloads folder",
            aliases = listOf("show downloads", "open downloads", "my downloads", "view downloads"),
            backend = BackendType.FILESYSTEM,
            execute = { _, _ ->
                val path = "/storage/emulated/0/Download"
                val items = Fs.list(path)
                CapabilityExecutionResult.ok("📥 Downloads contains ${items.size} items (e.g. ${items.take(4).joinToString { it.name }}).", items)
            }
        ),

        CapabilityDef(
            id = "file.dcim.open",
            category = "filesystem",
            name = "Open DCIM Photos Directory",
            description = "Show camera photos and screenshots directory",
            aliases = listOf("show photos folder", "open camera photos", "open dcim"),
            backend = BackendType.FILESYSTEM,
            execute = { _, _ ->
                val path = "/storage/emulated/0/DCIM"
                val items = Fs.list(path)
                CapabilityExecutionResult.ok("📸 DCIM contains ${items.size} folders/items.", items)
            }
        )
    )
}
