package io.lingo.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Lingo — bilingual YouTube subtitle player as a native Android app.
 *
 * Why native: a WebView runs on the user's own residential/mobile IP and the
 * native layer issues HTTP with NO CORS restriction, so it can fetch YouTube's
 * caption endpoints directly — the exact path that is blocked from Cloudflare/
 * datacenter IPs and forbidden by CORS in a plain web page.
 *
 * Two caption strategies, both exposed to the page via the `LingoNative` bridge:
 *   1) direct: httpGet(watch page) -> baseUrl -> httpGet(timedtext)  [fast path]
 *   2) capture: the embedded YouTube player itself requests /api/timedtext with
 *      a valid poToken; shouldInterceptRequest records that signed URL so the
 *      page can re-fetch the full transcript even when YouTube gates timedtext.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val io = Executors.newFixedThreadPool(4)

    // Latest pot-signed timedtext URL observed from the real player (strategy 2).
    private val capturedTimedText = AtomicReference<String>("")

    private val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = UA
            // The YouTube IFrame player needs these.
            javaScriptCanOpenWindowsAutomatically = true
        }
        WebView.setWebContentsDebuggingEnabled(true)

        // Serve bundled assets over a real https origin so the YouTube IFrame
        // player accepts the embed (file:// origin is often rejected).
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                val u = uri.toString()
                // Record (but do not block) the player's own caption request — it
                // carries a valid poToken the page can reuse for the full fetch.
                if (u.contains("/api/timedtext")) {
                    capturedTimedText.set(u)
                }
                return assetLoader.shouldInterceptRequest(uri)
            }

            // Keep YouTube navigations inside the WebView.
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false
        }

        web.addJavascriptInterface(Bridge(), "LingoNative")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    inner class Bridge {
        /**
         * Native HTTP GET (no CORS, runs on the device's own IP). Result is
         * delivered back to JS asynchronously via window.__lingoResolve so the
         * page's UI thread never blocks.
         */
        @JavascriptInterface
        fun httpGet(url: String, headersJson: String?, reqId: String) {
            io.execute {
                var ok = false
                var body = ""
                var status = 0
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = true
                        connectTimeout = 15000
                        readTimeout = 20000
                        setRequestProperty("User-Agent", UA)
                        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                        setRequestProperty("Accept-Encoding", "gzip")
                        applyHeaders(this, headersJson)
                    }
                    status = conn.responseCode
                    val raw = if (status in 200..299) conn.inputStream else conn.errorStream
                    val stream = if ("gzip".equals(conn.contentEncoding, true))
                        GZIPInputStream(raw) else raw
                    body = stream.bufferedReaderUtf8().use { it.readText() }
                    ok = status in 200..299
                } catch (e: Exception) {
                    body = e.message ?: "error"
                }
                deliver(reqId, ok, status, body)
            }
        }

        /** Most recent pot-signed timedtext URL captured from the real player. */
        @JavascriptInterface
        fun getCapturedTimedtextUrl(): String = capturedTimedText.get()

        @JavascriptInterface
        fun clearCapturedTimedtext() = capturedTimedText.set("")
    }

    private fun applyHeaders(conn: HttpURLConnection, headersJson: String?) {
        if (headersJson.isNullOrBlank()) return
        try {
            val obj = JSONObject(headersJson)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                conn.setRequestProperty(k, obj.getString(k))
            }
        } catch (_: Exception) { /* ignore malformed header map */ }
    }

    private fun deliver(reqId: String, ok: Boolean, status: Int, body: String) {
        val payload = JSONObject()
            .put("reqId", reqId)
            .put("ok", ok)
            .put("status", status)
            .put("body", body)
            .toString()
        // base64 to dodge any escaping issues in the JS string literal.
        val b64 = android.util.Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        web.post {
            web.evaluateJavascript("window.__lingoResolveB64 && window.__lingoResolveB64('$b64')", null)
        }
    }

    private fun java.io.InputStream.bufferedReaderUtf8() =
        BufferedReader(InputStreamReader(this, Charsets.UTF_8))
}
