package com.llamalite

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Jedno riesenie: apka obsahuje cely web (chat + upload + vsetky nastavenia)
 * a vstavany HTTP server + llama-server. UI sa zobrazuje vo WebView.
 *
 * Dolezite: WebView je vlozeny do FrameLayout-u s dark backgroundom, aby bolo
 * vidno, ze sa nieco deje, kym server nabehne.
 */
class MainActivity : AppCompatActivity() {

    private var web: WebView? = null
    private var server: LocalServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // koren: cierny FrameLayout, do neho neskor WebView + placeholder
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0E1014.toInt())
        setContentView(root)

        val hint = TextView(this)
        hint.text = "llamalite sa spusta\u2026"
        hint.setTextColor(0xFF8B93A7.toInt())
        hint.textSize = 16f
        hint.x = 40f; hint.y = 120f
        root.addView(hint, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // server startujeme PRED WebView
        val srv = LocalServer(applicationContext, Config.WEB_PORT)
        srv.start()
        server = srv

        // dev: ak je na sdcard pripravene UI, skopiruj ho (bez rebuildu)
        try {
            val src = java.io.File("/sdcard/Download/llamalite-ui")
            if (src.isDirectory) {
                val dst = java.io.File(applicationContext.filesDir, "web")
                dst.mkdirs()
                src.listFiles()?.forEach { f ->
                    if (f.isFile && (f.name.endsWith(".html") || f.name.endsWith(".json"))) {
                        try { f.copyTo(java.io.File(dst, f.name), overwrite = true) } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        // WebView vytvarame az po nastaveni vsetkeho
        val w: WebView
        try {
            w = WebView(this)
        } catch (e: Throwable) {
            hint.text = "WebView sa nepodarilo vytvorit:\n${e.message}"
            return
        }
        val ws: WebSettings = w.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        ws.databaseEnabled = true
        ws.cacheMode = WebSettings.LOAD_NO_CACHE
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        w.setBackgroundColor(0xFF0E1014.toInt())
        w.setWebChromeClient(object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.e("LLAMALITE_JS", "${m?.message()} @${m?.lineNumber()}")
                if (m?.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    hint.visibility = TextView.VISIBLE
                    hint.text = "JS chyba: ${m.message().take(250)}"
                }
                return true
            }
        })

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hint.visibility = TextView.GONE   // stranka sa nacitala -> skry placeholder
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                hint.visibility = TextView.VISIBLE
                hint.text = "chyba nacitania: ${error?.description}"
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.host != "127.0.0.1" && u.host != "localhost") {
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }

        root.addView(w, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        web = w

        // config inicializacia + scan (aby web hned videl data)
        Thread {
            try {
                val cfg = Config.load(applicationContext)
                ModelScan.scan(cfg)
            } catch (_: Exception) {}
        }.start()

        // nacitaj az po vsetkom; server uz bezi
        w.loadUrl("http://127.0.0.1:${Config.WEB_PORT}/")
    }

    override fun onDestroy() {
        try { web?.destroy() } catch (_: Exception) {}
        web = null
        server?.stop()
        server = null
        super.onDestroy()
    }
}