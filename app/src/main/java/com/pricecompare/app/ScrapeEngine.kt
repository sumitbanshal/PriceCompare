package com.pricecompare.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Shared WebView settings for the hidden scrapers and the visible setup browser (same cookie jar). */
@SuppressLint("SetJavaScriptEnabled")
fun configureWebView(wv: WebView) {
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        setGeolocationEnabled(true)
        // Some sites refuse the WebView marker; present as regular mobile Chrome.
        userAgentString = userAgentString
            .replace("; wv)", ")")
            .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    wv.webChromeClient = object : WebChromeClient() {
        // Lets "detect my location" on the sites use the phone's GPS (app holds the permission).
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, true, true)
        }
    }
}

/**
 * Loads a platform's search page in an off-screen WebView, lets the page call its own APIs
 * (captured by extractor.js), then asks the extractor for the merged result.
 * WebViews are added to [host], which sits behind the opaque Compose UI so they render normally.
 */
class ScrapeEngine(private val context: Context, private val host: FrameLayout) {

    private val extractorJs: String by lazy {
        context.assets.open("extractor.js").bufferedReader().use { it.readText() }
    }

    private class PageState {
        @Volatile var finished = false
        var docStartInjected = false
    }

    suspend fun scrape(p: Platform, url: String, query: String, timeoutMs: Long = 22_000): PlatformResult =
        withContext(Dispatchers.Main) {
            val start = SystemClock.elapsedRealtime()
            val cfg = JSONObject().put("platform", p.id).put("query", query).put("priceScale", p.priceScale)
            val bootstrap = "window.__PC_CFG=$cfg;\n$extractorJs"
            val state = PageState()
            val wv = createWebView(bootstrap, state)
            try {
                val json = drive(wv, state, url, bootstrap, start, timeoutMs)
                val took = SystemClock.elapsedRealtime() - start
                if (json == null) PlatformResult(p, Status.ERROR, message = "Page didn't respond", millis = took)
                else parseFinish(json, p, took)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                PlatformResult(p, Status.ERROR, message = e.message, millis = SystemClock.elapsedRealtime() - start)
            } finally {
                wv.stopLoading()
                host.removeView(wv)
                wv.destroy()
            }
        }

    private suspend fun drive(
        wv: WebView, state: PageState, url: String, bootstrap: String, start: Long, timeoutMs: Long,
    ): String? {
        wv.loadUrl(url)
        var last = -2
        var stable = 0
        var scrolls = 0
        while (true) {
            delay(1200)
            val elapsed = SystemClock.elapsedRealtime() - start
            var count = eval(wv, "window.__PC ? window.__PC.snapshot() : -1")?.toIntOrNull() ?: -1
            if (count == -1 && state.finished) {
                // document-start injection unavailable or page replaced the window: inject late
                eval(wv, bootstrap + ";0")
                count = 0
            }
            // nudge lazy-loaded grids
            if (state.finished && scrolls < 2 && elapsed > 2500L * (scrolls + 1)) {
                eval(wv, "window.scrollBy(0, Math.max(800, window.innerHeight * 1.5)); 0")
                scrolls++
            }
            stable = if (count == last) stable + 1 else 0
            last = count
            val done = when {
                elapsed >= timeoutMs -> true
                count > 0 && stable >= 2 && elapsed >= 4_000 -> true
                state.finished && count <= 0 && stable >= 4 && elapsed >= 7_000 -> true
                else -> false
            }
            if (done) break
        }
        val raw = eval(wv, "(function(){try{return window.__PC ? window.__PC.finish() : null}catch(e){return JSON.stringify({status:'error',items:[],notes:[String(e)]})}})()")
            ?: return null
        val decoded = JSONTokener(raw).nextValue()
        return decoded as? String
    }

    private suspend fun eval(wv: WebView, js: String): String? = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r) }
    }

    private fun createWebView(bootstrap: String, state: PageState): WebView {
        val wv = WebView(context)
        configureWebView(wv)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, bootstrap, setOf("*"))
            state.docStartInjected = true
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                state.finished = false
                if (!state.docStartInjected) view.evaluateJavascript(bootstrap, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                state.finished = true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https" // block app deep links / intent://
            }
        }
        host.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return wv
    }
}
