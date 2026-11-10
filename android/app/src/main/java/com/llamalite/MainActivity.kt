package com.llamalite

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Jedno riesenie: apka obsahuje cely web (chat + upload + vsetky nastavenia)
 * a vstavany HTTP server + llama-server. UI sa zobrazuje vo WebView.
 * Stranka sa servuje z lokalneho servera na 127.0.0.1:19778.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var server: LocalServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val ws: WebSettings = web.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.allowFileAccess = true
        ws.cacheMode = WebSettings.LOAD_NO_CACHE
        web.setBackgroundColor(0xFF0E1014.toInt())

        // kazdy request smeruj na lokalny server (priehradka v apke)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host == "127.0.0.1" && url.port == Config.WEB_PORT) {
                    // nechavame na server (rovnaka adresa) - return null = normalny fetch
                }
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                // externe odkazy otvor v prehliadaci, lokalne vo WebView
                if (u.host != "127.0.0.1") {
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }

        // spusti vstavany server (idempotentne - ak bezi, len sa pripoji)
        val srv = LocalServer(applicationContext, Config.WEB_PORT)
        srv.start()
        server = srv

        // prva inicializacia configu + model scan na pozadi (aby web hned videl data)
        Thread {
            try {
                val cfg = Config.load(applicationContext)
                ModelScan.scan(cfg)
                Config.save(applicationContext, cfg)
            } catch (_: Exception) {}
        }.start()

        web.loadUrl("http://127.0.0.1:${Config.WEB_PORT}/")
    }

    override fun onDestroy() {
        // server nechavame bezat pocas zivota procesu; pri zatvoreni uvolnime
        server?.stop()
        super.onDestroy()
    }
}