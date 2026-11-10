package com.llamalite

import org.json.JSONObject
import java.io.File

/**
 * Kotlin port mkcmd.py: zo settings (defaults + overrides) posklada argv pre llama-server.
 *  - <flag_key> je nazov prepinaca BEZ uvodnych pomlciek (napr. "ctx-size")
 *  - true -> --flag, false -> vynecha (alebo negativny prepinac), cislo/text -> --flag HODNOTA
 *  - specialne: "mmproj" (auto|off|cesta), "extra_args" (rozdelene ako shell), "alias", "host", "port"
 */
object Cli {

    private val INTERNAL = setOf(
        "extra_args", "mmproj", "alias", "bin", "server_url", "host", "port"
    )

    // key -> (flag_true, flag_false or null)
    private val NEG = mapOf(
        "repack" to Pair("repack", "no-repack"),
        "warmup" to Pair("warmup", "no-warmup"),
        "jinja" to Pair("jinja", "no-jinja"),
        "kv-offload" to Pair("kv-offload", "no-kv-offload"),
        "context-shift" to Pair("context-shift", "no-context-shift"),
        "mmproj-auto" to Pair("mmproj-auto", "no-mmproj-auto"),
        "mmproj-offload" to Pair("mmproj-offload", "no-mmproj-offload"),
        "op-offload" to Pair("op-offload", "no-op-offload"),
        "cont-batching" to Pair("cont-batching", "no-cont-batching"),
        "kv-unified" to Pair("kv-unified", "no-kv-unified"),
        "cache-idle-slots" to Pair("cache-idle-slots", "no-cache-idle-slots"),
        "metrics" to Pair("metrics", null),
        "swa-full" to Pair("swa-full", null)
    )

    /** Rozdeli "extra_args" na shell-like tokeny (uvodzovky). */
    private fun splitShell(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = ' '
        var esc = false
        for (c in s) {
            if (esc) { cur.append(c); esc = false; continue }
            when {
                c == '\\' -> esc = true
                quote != ' ' && c == quote -> quote = ' '
                quote == ' ' && (c == '"' || c == '\'') -> quote = c
                quote == ' ' && c.isWhitespace() -> { if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) } }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun buildFlags(settings: JSONObject): List<String> {
        val args = ArrayList<String>()
        for (key in settings.keys()) {
            if (INTERNAL.contains(key)) continue
            val v = settings.opt(key) ?: continue
            if (v == JSONObject.NULL) continue
            if (settings.isNull(key)) continue

            if (NEG.containsKey(key)) {
                val (tf, ff) = NEG[key]!!
                when (v) {
                    is Boolean -> {
                        if (v) args.add("--$tf") else if (ff != null) args.add("--$ff")
                    }
                    else -> {
                        val s = v.toString()
                        if (s.isNotEmpty() && s != "false") { args.add("--$tf"); args.add(s) }
                    }
                }
                continue
            }

            when (v) {
                is Boolean -> if (v) args.add("--$key")
                else -> {
                    val s = v.toString()
                    if (s.isEmpty()) continue
                    args.add("--$key"); args.add(s)
                }
            }
        }
        return args
    }

    data class Cmd(val argv: List<String>, val alias: String, val host: String, val port: Int)

    /** Posklada plny argv pre dany model (id z ModelScan alebo rovno cesta .gguf). */
    fun build(cfg: JSONObject, mid: String): Cmd {
        val scan = ModelScan.scan(cfg)
        val item = scan.firstOrNull { it.id == mid }
        val mpath = if (File(mid).isFile && mid.lowercase().endsWith(".gguf")) mid
                    else item?.path ?: throw IllegalArgumentException("neznamy model '$mid'")
        if (!File(mpath).isFile) throw IllegalArgumentException("model neexistuje: $mpath")
        if (!ModelScan.headerOkPublic(mpath)) throw IllegalArgumentException("'$mpath' nie je platny GGUF (poskodeny)")

        val s = JSONObject(cfg.optJSONObject("defaults")?.toString() ?: "{}")
        cfg.optJSONObject("overrides")?.optJSONObject(mid)?.let { ov ->
            for (k in ov.keys()) s.put(k, ov.get(k))
        }

        val mm = s.optString("mmproj", "auto")
        var mmprojPath = ""
        if (mm.isNotEmpty() && mm != "off") {
            mmprojPath = when {
                mm == "auto" -> item?.mmproj ?: ""
                File(mm).isFile -> mm
                else -> ""
            }
        }

        val a = ArrayList<String>()
        a.add("-m"); a.add(mpath)
        if (mmprojPath.isNotEmpty()) { a.add("--mmproj"); a.add(mmprojPath) }
        a.addAll(buildFlags(s))
        val extra = s.optString("extra_args", "").trim()
        if (extra.isNotEmpty()) a.addAll(splitShell(extra))

        var alias = s.optString("alias", "")
        if (alias.isEmpty()) alias = if (!File(mid).isFile) mid else File(mpath).name.substringBeforeLast('.')
        if (alias.isNotEmpty()) { a.add("--alias"); a.add(alias) }

        val host = s.optString("host", "").ifEmpty { cfg.optString("host", "127.0.0.1") }
        val port = if (s.has("port") && !s.isNull("port")) s.optInt("port", Config.LLAMA_PORT)
                   else cfg.optInt("port", Config.LLAMA_PORT)
        a.add("--host"); a.add(host)
        a.add("--port"); a.add(port.toString())
        return Cmd(a, alias, host, port)
    }
}
