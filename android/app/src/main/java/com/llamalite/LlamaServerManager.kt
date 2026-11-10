package com.llamalite

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Drzi bezci llama-server proces (binarka v APK ako jniLibs/arm64-v8a/libllamaserver.so,
 * Android ju extrahuje do nativeLibraryDir). Start/Stop/Log + zistenie stavu.
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

    fun isRunning(): Boolean = running

    /** Skontroluje, ci na porte odpoveda llama-server (/v1/models). */
    fun portAlive(port: Int): Boolean = try {
        val c = URL("http://127.0.0.1:$port/v1/models").openConnection() as HttpURLConnection
        c.connectTimeout = 1200; c.readTimeout = 1200
        val code = c.responseCode
        c.disconnect()
        code in 200..299
    } catch (e: Exception) { false }

    @Synchronized
    fun start(ctx: Context, cfg: org.json.JSONObject, mid: String): String {
        stop()
        val bin = File(binPath(ctx))
        if (!bin.exists()) return "Binarka chyba: ${bin.absolutePath} (APK musi obsahovat llama-server)"

        val cmd = try { Cli.build(cfg, mid) } catch (e: Exception) { return e.message ?: "chyba pri skladani príkazu" }
        val lf = logFile(ctx)
        lf.writeText("argv: " + cmd.argv.joinToString(" ") + "\n")

        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + cmd.argv)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
            pb.directory(ctx.filesDir)
            val p = pb.start()
            proc = p
            Thread {
                try { FileOutputStream(lf, true).use { out -> p.inputStream.copyTo(out) } } catch (_: Exception) {}
            }.start()

            var ok = false
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 180_000) {
                Thread.sleep(700)
                if (lf.exists() && lf.readText().contains("listening on")) { ok = true; break }
                if (!p.isAlive) break
            }
            running = ok
            if (ok) "" else (tailLog(ctx) ?: "server sa nenastartoval")
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
            Thread.sleep(400)
            if (p?.isAlive == true) p.destroyForcibly()
        } catch (_: Exception) {}
        // poistka: pozabi pripadne sirotske llama-server procesy (v ramci nasho uid)
        try {
            for (pid in File("/proc").list() ?: emptyArray()) {
                if (!pid.all { it.isDigit() }) continue
                val cl = File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
                if (cl.contains("libllamaserver.so")) {
                    try { android.os.Process.killProcess(pid.toInt()) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    fun tailLog(ctx: Context, n: Int = 15): String = try {
        logFile(ctx).readLines().takeLast(n).joinToString("\n")
    } catch (e: Exception) { lastError }
}