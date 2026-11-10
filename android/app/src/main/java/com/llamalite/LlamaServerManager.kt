package com.llamalite

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Drzi jeden bezci llama-server proces + jeho argv/log.
 * Binarka je zabalena v APK ako jniLibs/arm64-v8a/libllamaserver.so
 * a Android ju extrahuje do nativeLibraryDir (odtial sa da spustit).
 */
object LlamaServerManager {

    @Volatile private var proc: Process? = null
    @Volatile var running: Boolean = false
        private set
    @Volatile var lastError: String = ""
        private set

    fun binPath(ctx: Context): String =
        File(ctx.applicationInfo.nativeLibraryDir, "libllamaserver.so").absolutePath

    fun logFile(ctx: Context): File = File(ctx.filesDir, "server.log")

    /** Poskladá argv pre llama-server z nastavení. */
    fun buildArgs(s: Settings): List<String> {
        val a = ArrayList<String>()
        a += "-m"; a += s.modelPath
        if (s.mmprojPath.isNotBlank() && File(s.mmprojPath).exists()) {
            a += "--mmproj"; a += s.mmprojPath
        }
        a += "--ctx-size"; a += s.ctx.toString()
        a += "--threads"; a += s.threads.toString()
        a += "--threads-batch"; a += s.threads.toString()
        a += "--parallel"; a += "1"
        a += "--load-mode"; a += s.loadMode
        a += "--flash-attn"; a += s.flashAttn
        a += "--cache-type-k"; a += s.ctk
        a += "--cache-type-v"; a += s.ctv
        if (s.repack) a += "--repack" else a += "--no-repack"
        a += "--no-warmup"
        a += "--jinja"
        if (s.reasoning) { a += "--reasoning"; a += "on"; a += "--reasoning-format"; a += "deepseek" }
        else { a += "--reasoning"; a += "off" }
        a += "--host"; a += "127.0.0.1"
        a += "--port"; a += s.port.toString()
        if (s.alias.isNotBlank()) { a += "--alias"; a += s.alias }
        for (tok in s.extraArgs.split(Regex("\\s+")).filter { it.isNotBlank() }) a += tok
        return a
    }

    /** Spusti server; vrati "" ak OK, inak chybu. Blokuje kym nenabehne (max ~180 s). */
    @Synchronized
    fun start(ctx: Context, s: Settings): String {
        stop()
        val bin = binPath(ctx)
        val binFile = File(bin)
        if (!binFile.exists()) return "Binarka chyba: $bin (az APK obsahuje llama-server)"
        binFile.setExecutable(true)
        if (!File(s.modelPath).exists()) return "Model neexistuje: ${s.modelPath}"

        val args = buildArgs(s)
        val lf = logFile(ctx)
        lf.writeText("argv: " + args.joinToString(" ") + "\n")

        return try {
            val pb = ProcessBuilder(listOf(bin) + args)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
            pb.directory(ctx.filesDir)
            val p = pb.start()
            proc = p
            // zapisuj log do suboru vlaknom
            Thread {
                try {
                    val out = FileOutputStream(lf, true)
                    p.inputStream.copyTo(out)
                } catch (_: Exception) {}
            }.start()

            // cakaj na "listening on"
            var ok = false
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 180_000) {
                Thread.sleep(700)
                if (lf.exists() && lf.readText().contains("listening on")) { ok = true; break }
                if (!p.isAlive) break
            }
            running = ok
            if (ok) "" else tailLog(ctx)
        } catch (e: Exception) {
            running = false
            "Spustenie zlyhalo: ${e.message}"
        }
    }

    @Synchronized
    fun stop() {
        val p = proc
        proc = null
        running = false
        try {
            p?.destroy()
            Thread.sleep(300)
            if (p?.isAlive == true) p.destroyForcibly()
        } catch (_: Exception) {}
    }

    fun tailLog(ctx: Context, n: Int = 15): String {
        return try {
            logFile(ctx).readLines().takeLast(n).joinToString("\n")
        } catch (e: Exception) { lastError }
    }

    /** True, ak binarka je v APK pritomna. */
    fun hasBinary(ctx: Context): Boolean = File(binPath(ctx)).exists()
}
