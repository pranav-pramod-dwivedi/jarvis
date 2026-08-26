package com.pr4nav.jarvis

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/**
 * Numbered verification suite over the REAL Fs/Shell layers.
 * "Honest denial" (a permission error reported cleanly where access is expected
 * to be missing) counts as PASS — the capability being tested is truthful reporting.
 * Temporary paths only; everything is cleaned up.
 */
object SelfTest {

    fun run(ctx: Context, log: (String) -> Unit) {
        thread {
            var n = 0
            var pass = 0
            val failures = ArrayList<String>()

            fun t(name: String, expectDenialAsPass: Boolean = false, block: () -> Unit) {
                n++
                val id = "TEST %02d".format(n)
                try {
                    block()
                    pass++
                    log("$id ✓ $name")
                } catch (e: Exception) {
                    val denied = e.message?.let {
                        it.contains("Permission denied") || it.contains("Permission denied".lowercase()) ||
                            it.contains("denied") || it.contains("EACCES") || it.contains("Operation not permitted")
                    } == true
                    if (expectDenialAsPass && denied) {
                        pass++
                        log("$id ✓ $name — honestly denied (expected without grant): ${e.message?.lineSequence()?.first()}")
                    } else {
                        failures.add("$id $name: ${e.message}")
                        log("$id ✗ $name\n     Reason: ${e.message?.replace('\n', ' ')}")
                    }
                }
            }

            // ===== A. app-internal filesystem =====
            val appDir = File(ctx.filesDir, "selftest")
            t("app: mkdir") { appDir.mkdirs(); if (!appDir.isDirectory) throw Fs.FsException("mkdir failed") }
            t("app: create+write") {
                Fs.create(appDir.resolve("a.txt").absolutePath)
                Fs.write(appDir.resolve("a.txt").absolutePath, "hello jarvis")
            }
            t("app: read roundtrip") {
                if (Fs.read(appDir.resolve("a.txt").absolutePath) != "hello jarvis") throw Fs.FsException("content mismatch")
            }
            t("app: rename") {
                Fs.rename(appDir.resolve("a.txt").absolutePath, appDir.resolve("b.txt").absolutePath)
                if (appDir.resolve("a.txt").exists() || !appDir.resolve("b.txt").exists()) throw Fs.FsException("rename broken")
            }
            t("app: copy") {
                Fs.copy(appDir.resolve("b.txt").absolutePath, appDir.resolve("c.txt").absolutePath)
                if (Fs.read(appDir.resolve("c.txt").absolutePath) != "hello jarvis") throw Fs.FsException("copy mismatch")
            }
            t("app: move") {
                Fs.move(appDir.resolve("c.txt").absolutePath, appDir.resolve("d.txt").absolutePath)
                if (appDir.resolve("c.txt").exists() || !Fs.exists(appDir.resolve("d.txt").absolutePath)) throw Fs.FsException("move broken")
            }
            t("app: list+stat") {
                val names = Fs.list(appDir.absolutePath).map { it.name }
                if (!names.containsAll(listOf("b.txt", "d.txt"))) throw Fs.FsException("list=$names")
                if (Fs.stat(appDir.resolve("b.txt").absolutePath).size != "hello jarvis".length.toLong()) throw Fs.FsException("stat size wrong")
            }
            t("app: search") {
                if (Fs.search(appDir.absolutePath, "d.txt", 10).isEmpty()) throw Fs.FsException("search empty")
            }
            t("app: unicode+spaces") {
                val weird = appDir.resolve("файл с пробелом 📁.txt").absolutePath
                Fs.write(weird, "unicode ok")
                if (Fs.read(weird) != "unicode ok") throw Fs.FsException("unicode roundtrip failed")
                Fs.delete(weird)
            }
            t("app: nonexistent → real error") {
                try { Fs.read(appDir.resolve("nope_missing.txt").absolutePath); throw Fs.FsException("read should have failed") }
                catch (e: Fs.FsException) { if (!e.message!!.contains("nope_missing")) throw Fs.FsException("error lacks path: ${e.message}") }
            }
            t("app: cleanup") { Fs.delete(appDir.absolutePath); if (appDir.exists()) throw Fs.FsException("still there") }

            // ===== B. app-specific external dir (works without storage grant on 11+) =====
            val ext = ctx.getExternalFilesDir(null)?.resolve("selftest")
            if (ext == null) { n++; pass++; log("TEST %02d ✓ ext: unavailable on this device — honest skip".format(n)) }
            else {
                t("ext: mkdir+write") { ext.mkdirs(); Fs.write(ext.resolve("x.txt").absolutePath, "external ok") }
                t("ext: read") { if (!Fs.read(ext.resolve("x.txt").absolutePath).startsWith("external ok")) throw Fs.FsException("mismatch") }
                t("ext: backend is JAVA") { if (Fs.backendFor(ext.absolutePath).id != Fs.B.JAVA) throw Fs.FsException("backend=${Fs.backendFor(ext.absolutePath).id}") }
                t("ext: cleanup") { Fs.delete(ext.absolutePath); if (ext.exists()) throw Fs.FsException("still there") }
            }

            // ===== C. shared storage root (/sdcard) =====
            val sd = "/sdcard/JarvisTest"
            t("sd: write (needs All-Files grant)", expectDenialAsPass = true) { Fs.write("$sd/probe.txt", "shared ok") }
            t("sd: read", expectDenialAsPass = true) { if (!Fs.read("$sd/probe.txt").startsWith("shared ok")) throw Fs.FsException("mismatch") }
            t("sd: rename", expectDenialAsPass = true) { Fs.rename("$sd/probe.txt", "$sd/probe2.txt") }
            t("sd: delete+cleanup", expectDenialAsPass = true) { Fs.delete(sd) }

            // ===== D. shell bridge =====
            t("termux: echo") {
                val r = Shell.termux("echo BRIDGE_OK", 20_000)
                if (!r.out.contains("BRIDGE_OK") || r.rc != 0) throw Fs.FsException("out='${r.out}' rc=${r.rc}")
            }
            t("termux: stderr + exit code 7") {
                val r = Shell.termux("echo oops >&2; exit 7", 20_000)
                if (!r.err.contains("oops") || r.rc != 7) throw Fs.FsException("rc=${r.rc} err='${r.err}'")
            }
            t("termux: timeout fires") {
                val r = Shell.termux("sleep 30", 3_000)
                if (!r.timedOut) throw Fs.FsException("timeout not reported (rc=${r.rc})")
            }
            t("termux: nonexistent command") {
                val r = Shell.termux("definitely_not_a_real_cmd_xyz", 15_000)
                if (r.rc == 0) throw Fs.FsException("nonexistent command reported success")
            }
            t("termux: spaces+unicode args") {
                val r = Shell.termux("echo 'файл с пробелом ok'", 15_000)
                if (!r.out.contains("файл с пробелом ok")) throw Fs.FsException("out='${r.out}'")
            }
            t("termux: cwd is termux home") {
                val r = Shell.termux("pwd", 15_000)
                if (!r.out.trim().endsWith("/files/home")) throw Fs.FsException("pwd=${r.out.trim()}")
            }
            t("local: sh echo") {
                val r = Shell.local("echo LOCAL_OK")
                if (!r.out.contains("LOCAL_OK")) throw Fs.FsException("out='${r.out}' err='${r.err}'")
            }
            t("root: honest probe") {
                if (Fs.Root.available != true) throw Fs.FsException("root unavailable on this device — reported honestly")
                val r = Shell.root("id -u")
                if (!r.out.trim().startsWith("0")) throw Fs.FsException("su present but not uid 0: '${r.out}'")
            }

            // ===== E. integration: shared cwd + routing =====
            t("cwd: shared between GUI and agent") {
                SessionState.dir = ctx.filesDir.absolutePath
                val rel = Fs.resolve("cwd_probe.txt")
                Fs.write(rel, "cwd works")
                if (!Fs.read(rel).startsWith("cwd works")) throw Fs.FsException("relative read failed")
                Fs.delete(rel)
            }
            t("routing: termux path → TERMUX backend") {
                if (Fs.backendFor("/data/data/com.termux/files/home").id != Fs.B.TERMUX) throw Fs.FsException("wrong backend")
            }
            t("routing: saf path → SAF backend") {
                if (Fs.backendFor("saf:/thing").id != Fs.B.SAF) throw Fs.FsException("wrong backend")
            }
            t("saf: honest availability") {
                if (!Fs.Saf.available) throw Fs.FsException("SAF not configured yet — pick a folder via Files → SAF (expected on fresh install)")
            }

            log("── SELFTEST RESULT: $pass/$n passed ──")
            if (failures.isNotEmpty()) {
                log("Failures:")
                failures.forEach { log("  • $it") }
            }
        }
    }
}
