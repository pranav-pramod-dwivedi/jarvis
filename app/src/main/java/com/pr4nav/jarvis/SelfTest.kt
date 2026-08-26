package com.pr4nav.jarvis

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/**
 * End-to-end verification of the filesystem layer + shell bridge.
 * Uses temporary files only, cleans up after. Every line logged is a REAL result.
 */
object SelfTest {

    fun run(ctx: Context, log: (String) -> Unit) {
        thread {
            val results = ArrayList<Pair<String, Boolean>>()
            fun check(name: String, block: () -> Unit) {
                try { block(); results.add(name to true); log("✓ $name") }
                catch (e: Exception) { results.add(name to false); log("✗ $name — ${e.message}") }
            }

            val sdcard = "/sdcard/JarvisTest"
            val appDir = File(ctx.filesDir, "selftest")

            // --- app-private dir ---
            check("app: create dir") { appDir.mkdirs(); if (!appDir.isDirectory) throw Fs.FsException("mkdir failed") }
            check("app: write file") { Fs.write(appDir.resolve("a.txt").absolutePath, "hello jarvis") }
            check("app: read file") {
                val c = Fs.read(appDir.resolve("a.txt").absolutePath)
                if (c != "hello jarvis") throw Fs.FsException("content mismatch: '$c'")
            }
            check("app: rename") {
                Fs.rename(appDir.resolve("a.txt").absolutePath, appDir.resolve("b.txt").absolutePath)
                if (!appDir.resolve("b.txt").exists()) throw Fs.FsException("renamed file missing")
            }
            check("app: copy") {
                Fs.copy(appDir.resolve("b.txt").absolutePath, appDir.resolve("c.txt").absolutePath)
                if (Fs.read(appDir.resolve("c.txt").absolutePath) != "hello jarvis") throw Fs.FsException("copy mismatch")
            }
            check("app: list") {
                val names = Fs.list(appDir.absolutePath).map { it.name }
                if (!names.containsAll(listOf("b.txt", "c.txt"))) throw Fs.FsException("list incomplete: $names")
            }
            check("app: stat") {
                val e = Fs.stat(appDir.resolve("b.txt").absolutePath)
                if (e.size != "hello jarvis".length.toLong()) throw Fs.FsException("stat size ${e.size}")
            }
            check("app: search") {
                val hits = Fs.search(appDir.absolutePath, "b.txt", 10)
                if (hits.isEmpty()) throw Fs.FsException("search found nothing")
            }
            check("app: delete") {
                Fs.delete(appDir.absolutePath)
                if (appDir.exists()) throw Fs.FsException("dir still exists")
            }

            // --- shared storage /sdcard (needs All-Files or at least own media dir) ---
            check("sd: create dir") { Fs.mkdir(sdcard) }
            check("sd: write file") { Fs.write("$sdcard/probe.txt", "sdcard ok ${System.currentTimeMillis()}") }
            check("sd: read file") { if (!Fs.read("$sdcard/probe.txt").startsWith("sdcard ok")) throw Fs.FsException("mismatch") }
            check("sd: rename") {
                Fs.rename("$sdcard/probe.txt", "$sdcard/probe2.txt")
                if (!Fs.exists("$sdcard/probe2.txt")) throw Fs.FsException("missing after rename")
            }
            check("sd: copy+move") {
                Fs.copy("$sdcard/probe2.txt", "$sdcard/probe3.txt")
                Fs.move("$sdcard/probe3.txt", "$sdcard/probe4.txt")
                if (!Fs.exists("$sdcard/probe4.txt")) throw Fs.FsException("move failed")
            }
            check("sd: list") {
                val names = Fs.list(sdcard).map { it.name }
                if (!names.contains("probe2.txt")) throw Fs.FsException("probe2 not listed: $names")
            }
            check("sd: cleanup") {
                Fs.delete(sdcard)
                if (File(sdcard).exists()) throw Fs.FsException("cleanup failed")
            }

            // --- shell bridge ---
            check("shell: termux echo") {
                val r = Shell.termux("echo BRIDGE_OK", 20_000)
                if (!r.out.contains("BRIDGE_OK")) throw Fs.FsException("out='${r.out}' err='${r.err}'")
            }
            check("shell: termux pipe+rc") {
                val r = Shell.termux("echo hi | grep hi; exit 0", 20_000)
                if (r.rc != 0) throw Fs.FsException("rc=${r.rc}")
            }
            check("shell: termux stderr+nonzero") {
                val r = Shell.termux("echo oops >&2; exit 7", 20_000)
                if (!r.err.contains("oops") || r.rc != 7) throw Fs.FsException("rc=${r.rc} err='${r.err}'")
            }

            val pass = results.count { it.second }
            log("── selftest: $pass/${results.size} passed ──")
        }
    }
}
