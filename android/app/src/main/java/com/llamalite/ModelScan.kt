package com.llamalite

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Kotlin port skenera modelov (discover.py):
 *  - rekurzivne najde .gguf v scan_dirs (do scan_depth)
 *  - rozlisi hlavny model od mmproj (podla nazvu)
 *  - ku kazdemu modelu priradi mmproj z rovnakej zlozky (najlepsia zhoda nazvov)
 *  - overi GGUF hlavicku + velkost
 */
data class ScanItem(
    val id: String,
    val name: String,
    val path: String,
    val dir: String,
    val sizeMb: Double,
    val broken: Boolean,
    val mmproj: String,
    val mmprojName: String,
    val mmprojMb: Double,
    val vision: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("path", path); put("dir", dir)
        put("size_mb", sizeMb); put("broken", broken)
        put("mmproj", mmproj); put("mmproj_name", mmprojName); put("mmproj_mb", mmprojMb)
        put("vision", vision)
    }
}

object ModelScan {

    private val MMPROJ_HINTS = listOf("mmproj", "projector")

    private fun isMmproj(name: String): Boolean {
        val n = name.lowercase(); return MMPROJ_HINTS.any { n.contains(it) }
    }

    fun headerOkPublic(path: String): Boolean = headerOk(path)

    private fun headerOk(path: String): Boolean = try {
        File(path).inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" }
    } catch (e: Exception) { false }

    private fun tokens(name: String): Set<String> {
        val base = name.substringBeforeLast('.', name).lowercase()
        return base.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
    }

    private fun matchScore(model: String, mm: String): Int {
        val a = tokens(model); val b = tokens(mm)
        return (a intersect b).sumOf { it.length }
    }

    private fun walk(f: File, depth: Int, maxDepth: Int, out: MutableList<File>) {
        if (depth > maxDepth || !f.exists()) return
        if (f.isFile) { out.add(f); return }
        val kids = f.listFiles() ?: return
        for (k in kids) {
            if (k.isDirectory) walk(k, depth + 1, maxDepth, out) else out.add(k)
        }
    }

    private fun makeId(name: String, used: MutableSet<String>): String {
        val stem = name.substringBeforeLast('.', name)
        var safe = stem.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").trim('_')
        if (safe.isEmpty()) safe = "model"
        var cid = safe; var i = 2
        while (used.contains(cid)) { cid = "${safe}_$i"; i++ }
        used.add(cid); return cid
    }

    fun scan(cfg: JSONObject): List<ScanItem> {
        val dirs = cfg.optJSONArray("scan_dirs")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: listOf("/sdcard/Download")
        val depth = cfg.optInt("scan_depth", 3)

        val files = ArrayList<File>()
        val seenDirs = HashSet<String>()
        for (d in dirs) {
            val df = File(d).absoluteFile
            if (!df.isDirectory || seenDirs.contains(df.path)) continue
            seenDirs.add(df.path)
            walk(df, 0, depth, files)
        }
        val ggufs = files.filter { it.name.lowercase().endsWith(".gguf") }
        val mm = ggufs.filter { isMmproj(it.name) }
        val mains = ggufs.filter { !isMmproj(it.name) }

        val out = ArrayList<ScanItem>()
        val used = HashSet<String>()
        for (m in mains.sortedBy { it.name }) {
            val cand = mm.filter { it.parentFile == m.parentFile }
            var best: File? = null; var bestScore = -1
            for (c in cand) {
                val sc = matchScore(m.name, c.name)
                if (sc > bestScore) { bestScore = sc; best = c }
            }
            if (bestScore <= 0 && cand.size == 1) best = cand[0]
            val mmpSz = if (best != null && best.exists()) best.length().toDouble() else 0.0
            out.add(
                ScanItem(
                    id = makeId(m.name, used),
                    name = m.name,
                    path = m.absolutePath,
                    dir = m.parent ?: "",
                    sizeMb = Math.round(m.length() / 1e5) / 10.0,
                    broken = !headerOk(m.absolutePath),
                    mmproj = best?.absolutePath ?: "",
                    mmprojName = best?.name ?: "",
                    mmprojMb = Math.round(mmpSz / 1e5) / 10.0,
                    vision = best != null
                )
            )
        }
        return out
    }

    fun toJsonArray(items: List<ScanItem>): JSONArray {
        val a = JSONArray(); items.forEach { a.put(it.toJson()) }; return a
    }
}
