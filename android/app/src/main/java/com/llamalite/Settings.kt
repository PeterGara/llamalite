package com.llamalite

import android.content.Context
import java.io.File

/** Jedno naskenovane .gguf (model + automaticky priradeny mmproj). */
data class ModelEntry(
    val id: String,
    val name: String,
    val path: String,
    val mmproj: String,
    val sizeMb: Long,
    val broken: Boolean
)

/** Vsetky nastavenia appky (uklada sa do SharedPreferences). */
data class Settings(
    var modelPath: String = "",
    var mmprojPath: String = "",
    var alias: String = "",
    var ctx: Int = 16384,
    var threads: Int = 4,
    var loadMode: String = "mmap",      // auto | mmap | mlock | mmap+mlock
    var flashAttn: String = "on",       // on | off | auto
    var ctk: String = "q8_0",
    var ctv: String = "q8_0",
    var repack: Boolean = true,
    var reasoning: Boolean = false,
    var port: Int = 8080,
    var extraArgs: String = ""
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences("llamalite", Context.MODE_PRIVATE).edit().apply {
            putString("modelPath", modelPath); putString("mmprojPath", mmprojPath)
            putString("alias", alias); putInt("ctx", ctx2()); putInt("threads", threads)
            putString("loadMode", loadMode); putString("flashAttn", flashAttn)
            putString("ctk", ctk); putString("ctv", ctv); putBoolean("repack", repack)
            putBoolean("reasoning", reasoning); putInt("port", port); putString("extraArgs", extraArgs)
        }.apply()
    }

    private fun ctx2() = ctx

    companion object {
        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences("llamalite", Context.MODE_PRIVATE)
            val s = Settings()
            s.modelPath = p.getString("modelPath", "") ?: ""
            s.mmprojPath = p.getString("mmprojPath", "") ?: ""
            s.alias = p.getString("alias", "") ?: ""
            s.ctx = p.getInt("ctx", 16384); s.threads = p.getInt("threads", 4)
            s.loadMode = p.getString("loadMode", "mmap") ?: "mmap"
            s.flashAttn = p.getString("flashAttn", "on") ?: "on"
            s.ctk = p.getString("ctk", "q8_0") ?: "q8_0"; s.ctv = p.getString("ctv", "q8_0") ?: "q8_0"
            s.repack = p.getBoolean("repack", true); s.reasoning = p.getBoolean("reasoning", false)
            s.port = p.getInt("port", 8080); s.extraArgs = p.getString("extraArgs", "") ?: ""
            return s
        }
    }
}

object ModelScanner {
    private val MMPROJ_HINTS = listOf("mmproj", "projector")

    fun isMmproj(name: String): Boolean {
        val n = name.lowercase(); return MMPROJ_HINTS.any { n.contains(it) }
    }

    fun headerOk(path: String): Boolean = try {
        File(path).inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b) == "GGUF" }
    } catch (e: Exception) { false }

    /** Rekurzivne najde .gguf v roots (maxdepth), priradi mmproj. */
    fun scan(roots: List<File>, maxDepth: Int = 3): List<ModelEntry> {
        val files = ArrayList<File>()
        for (r in roots) walk(r, 0, maxDepth, files)
        val ggufs = files.filter { it.name.lowercase().endsWith(".gguf") }
        val mm = ggufs.filter { isMmproj(it.name) }
        val mains = ggufs.filter { !isMmproj(it.name) }
        val out = ArrayList<ModelEntry>()
        val used = HashSet<String>()
        for (m in mains.sortedBy { it.name }) {
            val cand = mm.filter { it.parentFile == m.parentFile }
            val best = cand.maxByOrNull { commonTokens(m.name, it.name) }
            var id = m.name.removeSuffix(".gguf").ifEmpty { "model" }
            var i = 2; val base = id
            while (used.contains(id)) { id = "${base}_$i"; i++ }
            used.add(id)
            out.add(
                ModelEntry(
                    id = id, name = m.name, path = m.absolutePath,
                    mmproj = best?.absolutePath ?: "", sizeMb = m.length() / 1_000_000,
                    broken = !headerOk(m.absolutePath)
                )
            )
        }
        return out
    }

    private fun commonTokens(a: String, b: String): Int {
        val ta = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        val tb = b.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        return (ta intersect tb).sumOf { it.length }
    }

    private fun walk(f: File, depth: Int, maxDepth: Int, out: MutableList<File>) {
        if (depth > maxDepth || !f.exists()) return
        if (f.isFile) { out.add(f); return }
        val kids = f.listFiles() ?: return
        for (k in kids) {
            if (k.isDirectory) walk(k, depth + 1, maxDepth, out) else out.add(k)
        }
    }
}