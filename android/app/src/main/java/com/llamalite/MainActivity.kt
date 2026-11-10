package com.llamalite

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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
 * Dolezite casti:
 *  - onShowFileChooser  -> bez tohto sa v WebView neda pridat subor (📎)
 *  - placeholder text   -> vidno, ze apka zije, kym sa nacita UI
 *  - JS chyby do logu aj na obrazovku -> ziadne hadanie naslepo
 */
class MainActivity : AppCompatActivity() {

    private var web: WebView? = null
    private var server: LocalServer? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var hintView: TextView? = null

    private val REQ_PICK = 1001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0E1014.toInt())
        setContentView(root)

        val hint = TextView(this)
        hint.text = "llamalite sa spusta\u2026"
        hint.setTextColor(0xFF8B93A7.toInt())
        hint.textSize = 15f
        hint.setPadding(40, 120, 40, 0)
        hintView = hint
        root.addView(hint, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // server PRED WebView
        val srv = LocalServer(applicationContext, Config.WEB_PORT)
        srv.start()
        server = srv

        // dev: UI zo sdcard (bez rebuildu)
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

        // ---- Chrome client: JS konzola + VYBER SUBORU (file chooser) ----
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                android.util.Log.e("LLAMALITE_JS", "${m?.message()} @${m?.lineNumber()}")
                if (m?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    hint.visibility = TextView.VISIBLE
                    hint.text = "JS chyba: ${m.message()?.take(250)}"
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                // ukonci predosly callback, aby nezostal visiet
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                return try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"                     // hocijaky subor
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, "Vyber subor"),
                        REQ_PICK
                    )
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    hint.visibility = TextView.VISIBLE
                    hint.text = "nepodarilo sa otvorit vyber suboru: ${e.message}"
                    false
                }
            }
        }

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hint.visibility = TextView.GONE
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                hint.visibility = TextView.VISIBLE
                hint.text = "chyba nacitania: ${error?.description}"
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                // subor/odkaz mimo lokalneho servera -> system
                if (u.scheme == "file" || u.scheme == "content") return false
                if (u.host != "127.0.0.1" && u.host != "localhost") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }

        root.addView(w, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        web = w

        Thread {
            try {
                val cfg = Config.load(applicationContext)
                ModelScan.scan(cfg)
            } catch (_: Exception) {}
        }.start()

        w.loadUrl("http://127.0.0.1:${Config.WEB_PORT}/")
    }

    // vysledok vyberu suboru -> vratit do WebView
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PICK) {
            val cb = filePathCallback
            filePathCallback = null
            if (cb == null) { super.onActivityResult(requestCode, resultCode, data); return }

            var uris: Array<Uri>? = null
            if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null && clip.itemCount > 0) {
                    uris = Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                } else {
                    data.data?.let { uris = arrayOf(it) }
                }
            }
            cb.onReceiveValue(uris)   // null = zrusene
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        try { filePathCallback?.onReceiveValue(null) } catch (_: Exception) {}
        filePathCallback = null
        try { web?.destroy() } catch (_: Exception) {}
        web = null
        server?.stop()
        server = null
        super.onDestroy()
    }
}