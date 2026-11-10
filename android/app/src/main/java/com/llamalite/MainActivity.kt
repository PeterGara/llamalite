package com.llamalite

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Tenky WebView obal.
 *
 * Predvolene otvori llamalite web (http://127.0.0.1:8181).
 * Adresu vies zmenit:  raz klikni a podrz (long-press) kdekoľvek -> dialog na zadanie URL.
 * Ulozi sa do SharedPreferences ("url").
 *
 * Tip: llama-server ma aj vlastne vstavane WebUI na http://127.0.0.1:8080
 *      - staci sem zadat http://127.0.0.1:8080
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("llamalite", MODE_PRIVATE)
        var url = prefs.getString("url", "http://127.0.0.1:8181") ?: "http://127.0.0.1:8181"

        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.webViewClient = WebViewClient()
        web.loadUrl(url)

        web.setOnLongClickListener {
            val edit = android.widget.EditText(this).apply { setText(url) }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("URL")
                .setView(edit)
                .setPositiveButton("OK") { _, _ ->
                    url = edit.text.toString().trim()
                    prefs.edit().putString("url", url).apply()
                    web.loadUrl(url)
                }
                .setNegativeButton("Zrušiť", null)
                .show()
            true
        }
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }
}