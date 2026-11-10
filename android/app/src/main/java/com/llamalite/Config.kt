package com.llamalite

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Ulozenie celeho configu (settings.json) v appke (filesDir/config.json).
 * Struktura je rovnaka ako vo web verzii - web form ju cita/zapisuje cez /api/settings.
 */
object Config {

    const val WEB_PORT = 19778
    const val LLAMA_PORT = 19777

    private fun file(ctx: Context) = File(ctx.filesDir, "config.json")

    fun defaultJson(): String = """
{
  "server_url": "http://127.0.0.1:$LLAMA_PORT/v1",
  "scan_dirs": [ "/sdcard/Download", "/sdcard/Download/Operit/models" ],
  "scan_depth": 3,
  "chat": { "max_tokens": 2048, "video_frames": 6, "video_audio": true },
  "defaults": {
    "ctx-size": 16384,
    "threads": 4,
    "threads-batch": 4,
    "batch-size": 2048,
    "ubatch-size": 512,
    "parallel": 1,
    "flash-attn": "on",
    "repack": true,
    "load-mode": "mmap",
    "cache-type-k": "q8_0",
    "cache-type-v": "q8_0",
    "mmproj": "auto",
    "image-min-tokens": 512,
    "jinja": true,
    "warmup": false,
    "reasoning": "off",
    "reasoning-format": "deepseek",
    "temp": 0.8,
    "top-k": 40,
    "top-p": 0.95,
    "min-p": 0.05,
    "repeat-penalty": 1.0,
    "seed": -1,
    "alias": "",
    "host": "127.0.0.1",
    "port": $LLAMA_PORT,
    "extra_args": ""
  },
  "overrides": {}
}
""".trimIndent()

    fun load(ctx: Context): JSONObject {
        val f = file(ctx)
        if (!f.exists()) {
            val o = JSONObject(defaultJson())
            save(ctx, o)
            return o
        }
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            val o = JSONObject(defaultJson())
            save(ctx, o)
            o
        }
    }

    fun save(ctx: Context, obj: JSONObject) {
        val tmp = File(ctx.filesDir, "config.json.tmp")
        tmp.writeText(obj.toString(2))
        tmp.renameTo(file(ctx))
    }
}
