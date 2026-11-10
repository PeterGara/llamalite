package com.llamalite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder

/**
 * Vstavaný HTTP server (nahrada backend.py) - bezi na 127.0.0.1:19778.
 *  - servuje index.html + flags.json (assety APK)
 *  - API: scan/start/stop/settings/status/log/file/upload/convert_video
 *  - /proxy: preposle chat na llama-server (obchadza CORS) a streamuje odpoved
 */
class LocalServer(private val ctx: Context, private val port: Int) {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                while (running) {
                    val sock = try { ss.accept() } catch (e: Exception) { break }
                    Thread { handleClient(sock) }.start()
                }
            } catch (e: Exception) {
                // port obsadeny a pod.
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    // ---------- HTTP framing ----------
    private class Req(val method: String, val path: String, val qs: Map<String, String>,
                      val headers: Map<String, String>, val body: ByteArray)

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun readRequest(input: BufferedInputStream): Req? {
        val reqLine = readLine(input) ?: return null
        val parts = reqLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val ln = readLine(input) ?: break
            if (ln.isEmpty()) break
            val idx = ln.indexOf(':')
            if (idx > 0) headers[ln.substring(0, idx).trim().lowercase()] = ln.substring(idx + 1).trim()
        }
        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val qs = LinkedHashMap<String, String>()
        if (qIdx >= 0) {
            for (kv in target.substring(qIdx + 1).split("&")) {
                if (kv.isEmpty()) continue
                val e = kv.indexOf('=')
                val k = if (e >= 0) kv.substring(0, e) else kv
                val v = if (e >= 0) kv.substring(e + 1) else ""
                qs[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            }
        }
        val body = readBody(input, headers)
        return Req(method, path, qs, headers, body)
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): ByteArray {
        val te = headers["transfer-encoding"]?.lowercase() ?: ""
        if (te.contains("chunked")) {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: break
                val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
                if (size == 0) { readLine(input); break }
                val buf = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(buf, read, size - read)
                    if (n < 0) break
                    read += n
                }
                out.write(buf, 0, read)
                readLine(input) // trailing CRLF
            }
            return out.toByteArray()
        }
        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        if (len <= 0) return ByteArray(0)
        val buf = ByteArray(len.toInt())
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len.toInt() - read)
            if (n < 0) break
            read += n
        }
        return buf
    }

    private fun handleClient(sock: java.net.Socket) {
        try {
            val input = BufferedInputStream(sock.getInputStream())
            val output = BufferedOutputStream(sock.getOutputStream())
            val req = readRequest(input) ?: return
            dispatch(req, output)
            output.flush()
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun sendBytes(out: BufferedOutputStream, code: Int, ctype: String, body: ByteArray) {
        val head = StringBuilder()
        head.append("HTTP/1.1 $code OK\r\n")
        head.append("Content-Type: $ctype\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("Content-Length: ${body.size}\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        out.write(body)
    }

    private fun json(out: BufferedOutputStream, code: Int, obj: Any) {
        sendBytes(out, code, "application/json; charset=utf-8", obj.toString().toByteArray())
    }

    private fun dispatch(req: Req, out: BufferedOutputStream) {
        when {
            req.method == "OPTIONS" -> sendBytes(out, 204, "text/plain", ByteArray(0))
            req.method == "GET" -> handleGet(req, out)
            req.method == "POST" -> handlePost(req, out)
            else -> json(out, 405, JSONObject().put("error", "method"))
        }
    }

    // ---------- GET ----------
    private fun handleGet(req: Req, out: BufferedOutputStream) {
        when (req.path) {
            "/", "/index.html" -> asset(out, "index.html", "text/html; charset=utf-8")
            "/flags.json" -> asset(out, "flags.json", "application/json; charset=utf-8")
            "/api/health" -> json(out, 200, JSONObject().put("status", "ok").put("mem", mem()))
            "/api/list", "/api/scan" -> {
                val cfg = Config.load(ctx)
                val items = ModelScan.scan(cfg)
                val o = JSONObject()
                o.put("profiles", ModelScan.toJsonArray(items))
                if (req.path == "/api/list") {
                    o.put("running", LlamaServerManager.isRunning() && LlamaServerManager.portAlive(Config.LLAMA_PORT))
                    o.put("pid", -1)
                    o.put("current", 0)
                    o.put("mem", mem())
                }
                json(out, 200, o)
            }
            "/api/status" -> json(out, 200, JSONObject()
                .put("running", LlamaServerManager.isRunning() && LlamaServerManager.portAlive(Config.LLAMA_PORT))
                .put("mem", mem()))
            "/api/settings" -> json(out, 200, Config.load(ctx))
            "/api/start" -> {
                val id = req.qs["id"] ?: ""
                if (id.isEmpty()) { json(out, 400, JSONObject().put("status", "error").put("error", "chyba id")); return }
                val err = LlamaServerManager.start(ctx, Config.load(ctx), id)
                if (err.isEmpty())
                    json(out, 200, JSONObject().put("status", "ok").put("output", "spustene -> http://127.0.0.1:${Config.LLAMA_PORT}"))
                else
                    json(out, 500, JSONObject().put("status", "error").put("output", err))
            }
            "/api/stop" -> {
                LlamaServerManager.stop()
                json(out, 200, JSONObject().put("status", "ok").put("killed", 1).put("mem", mem()))
            }
            "/api/log" -> {
                val txt = try { LlamaServerManager.logFile(ctx).readLines().takeLast(80).joinToString("\n") } catch (e: Exception) { "(ziadny log)" }
                json(out, 200, JSONObject().put("log", txt))
            }
            "/api/file" -> {
                val p = File(req.qs["path"] ?: "")
                val up = MediaTools.uploadsDir(ctx)
                if (p.exists() && p.absolutePath.startsWith(up.absolutePath)) {
                    val ext = p.name.substringAfterLast('.', "").lowercase()
                    val ct = when (ext) {
                        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
                        "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "mp4" -> "video/mp4"
                        else -> "application/octet-stream"
                    }
                    sendBytes(out, 200, ct, p.readBytes())
                } else sendBytes(out, 404, "text/plain", "neexistuje".toByteArray())
            }
            else -> json(out, 404, JSONObject().put("error", "not found"))
        }
    }

    private fun asset(out: BufferedOutputStream, name: String, ctype: String) {
        try {
            // priorita: subor vo filesDir/web (da sa menit za behu, bez rebuildu),
            // inak asset zabaleny v APK
            val live = File(File(ctx.filesDir, "web"), name)
            val bytes = if (live.isFile) live.readBytes()
                        else ctx.assets.open(name).use { it.readBytes() }
            sendBytes(out, 200, ctype, bytes)
        } catch (e: Exception) {
            sendBytes(out, 404, "text/plain", "$name chyba".toByteArray())
        }
    }

    // ---------- POST ----------
    private fun handlePost(req: Req, out: BufferedOutputStream) {
        when (req.path) {
            "/api/settings" -> {
                try {
                    val obj = JSONObject(String(req.body, Charsets.UTF_8))
                    if (!obj.has("defaults")) throw IllegalArgumentException("chyba 'defaults'")
                    Config.save(ctx, obj)
                    json(out, 200, JSONObject().put("status", "ok"))
                } catch (e: Exception) {
                    json(out, 400, JSONObject().put("status", "error").put("error", e.message))
                }
            }
            "/upload" -> handleUpload(req, out)
            "/api/convert_video" -> {
                try {
                    val obj = JSONObject(String(req.body, Charsets.UTF_8))
                    val p = File(obj.optString("path", ""))
                    val up = MediaTools.uploadsDir(ctx)
                    if (!p.exists() || !p.absolutePath.startsWith(up.absolutePath))
                        throw IllegalArgumentException("neplatna cesta")
                    val res = MediaTools.videoExtract(p, obj.optInt("max_frames", 6), obj.optBoolean("audio", true))
                    val fa = JSONArray(); res.frames.forEach { fa.put(it.absolutePath) }
                    json(out, 200, JSONObject().put("status", "ok").put("frames", fa)
                        .put("audio", res.audio?.absolutePath ?: ""))
                } catch (e: Exception) {
                    json(out, 400, JSONObject().put("status", "error").put("error", e.message))
                }
            }
            "/proxy" -> handleProxy(req, out)
            else -> json(out, 404, JSONObject().put("error", "not found"))
        }
    }

    private fun handleUpload(req: Req, out: BufferedOutputStream) {
        val name = req.headers["x-filename"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "upload.bin"
        if (req.body.isEmpty()) { json(out, 400, JSONObject().put("status", "error").put("error", "prazdny subor")); return }
        val f = MediaTools.saveUpload(ctx, name, req.body)
        val kind = MediaTools.classify(name)
        val res = JSONObject()
        res.put("status", "ok"); res.put("path", f.absolutePath)
        res.put("name", f.name); res.put("kind", kind); res.put("size", req.body.size)
        when (kind) {
            "audio" -> if (name.substringAfterLast('.', "").lowercase() != "wav")
                MediaTools.toWav16k(f)?.let { res.put("converted", it.absolutePath) }
            "text" -> { val (txt, trunc) = MediaTools.readText(f); res.put("text", txt); res.put("truncated", trunc) }
            "video" -> {
                val vr = MediaTools.videoExtract(f, 6, true)
                val fa = JSONArray()
                vr.frames.forEach { fa.put(JSONObject().put("path", it.absolutePath).put("name", it.name)) }
                res.put("frames", fa); res.put("audio", vr.audio?.absolutePath ?: "")
            }
        }
        json(out, 200, res)
    }

    private fun handleProxy(req: Req, out: BufferedOutputStream) {
        val target = req.headers["x-target"] ?: ""
        if (!target.startsWith("http://127.0.0.1")) {
            json(out, 400, JSONObject().put("status", "error").put("error", "neplatny target (len 127.0.0.1)"))
            return
        }
        try {
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 0
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(req.body) }
            val code = conn.responseCode
            val ins = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            // streamni odpoved (chunked) na klienta
            val head = StringBuilder()
            head.append("HTTP/1.1 $code OK\r\n")
            head.append("Content-Type: application/json\r\n")
            head.append("Cache-Control: no-store\r\n")
            head.append("Access-Control-Allow-Origin: *\r\n")
            head.append("Transfer-Encoding: chunked\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            out.flush()
            val buf = ByteArray(4096)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write("${Integer.toHexString(n)}\r\n".toByteArray())
                out.write(buf, 0, n)
                out.write("\r\n".toByteArray())
                out.flush()
            }
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
            conn.disconnect()
        } catch (e: Exception) {
            json(out, 502, JSONObject().put("status", "error").put("error", e.message))
        }
    }

    private fun mem(): JSONObject {
        var total = 0L; var free = 0L; var avail = 0L
        try {
            for (ln in File("/proc/meminfo").readLines()) {
                val parts = ln.split(":")
                if (parts.size < 2) continue
                val kb = parts[1].trim().split(Regex("\\s+"))[0].toLongOrNull() ?: continue
                when (parts[0]) {
                    "MemTotal" -> total = kb / 1024
                    "MemFree" -> free = kb / 1024
                    "MemAvailable" -> avail = kb / 1024
                }
            }
        } catch (_: Exception) {}
        return JSONObject().put("total_mb", total).put("free_mb", free).put("avail_mb", avail)
    }
}