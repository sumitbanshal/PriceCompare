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
import org.json.JSONArray
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

/** Runs [script] at the start of every page this WebView loads (falls back to onPageStarted). */
fun installPageScript(wv: WebView, script: String): Boolean {
    if (script.isBlank()) return true
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))
        return true
    }
    return false
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

    /** [locationScript]: from [LocationSync.pageScript]; runs before the extractor on every page. */
    suspend fun scrape(p: Platform, url: String, query: String, locationScript: String, timeoutMs: Long = 24_000): PlatformResult =
        withContext(Dispatchers.Main) {
            val start = SystemClock.elapsedRealtime()
            val cfg = JSONObject().put("platform", p.id).put("query", query).put("priceScale", p.priceScale)
            val bootstrap = "$locationScript\nwindow.__PC_CFG=$cfg;\n$extractorJs"
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
        var reloaded = false
        var deadline = timeoutMs
        while (true) {
            delay(1200)
            val elapsed = SystemClock.elapsedRealtime() - start
            val probe = eval(
                wv,
                "JSON.stringify([window.__PC ? window.__PC.snapshot() : -1," +
                    " window.__PCLOC ? window.__PCLOC.acted : 0," +
                    " window.__PCLOC && window.__PCLOC.lastActionAt ? Date.now() - window.__PCLOC.lastActionAt : 1e9])",
            )
            val arr = runCatching { JSONArray(JSONTokener(probe ?: "").nextValue() as String) }.getOrNull()
            var count = arr?.optInt(0, -1) ?: -1
            val locActions = arr?.optInt(1, 0) ?: 0
            val sinceLocAction = arr?.optLong(2, 1_000_000_000L) ?: 1_000_000_000L
            if (count == -1 && state.finished) {
                // document-start injection unavailable or page replaced the window: inject late
                eval(wv, bootstrap + ";0")
                count = 0
            }
            // the site is busy taking our location: give it time
            if (locActions > 0 && sinceLocAction < 4_000) {
                deadline = maxOf(deadline, elapsed + 8_000).coerceAtMost(timeoutMs + 16_000)
                if (elapsed < deadline) {
                    stable = 0
                    continue
                }
            }
            // location got set but the search results never came: run the search again once
            if (!reloaded && locActions > 0 && count <= 0 && state.finished && sinceLocAction > 3_000) {
                reloaded = true
                wv.loadUrl(url)
                deadline = maxOf(deadline, elapsed + 14_000)
                last = -2; stable = 0; scrolls = 0
                continue
            }
            // nudge lazy-loaded grids
            if (state.finished && scrolls < 2 && elapsed > 2500L * (scrolls + 1)) {
                eval(wv, "window.scrollBy(0, Math.max(800, window.innerHeight * 1.5)); 0")
                scrolls++
            }
            stable = if (count == last) stable + 1 else 0
            last = count
            val done = when {
                elapsed >= deadline -> true
                count > 0 && stable >= 2 && elapsed >= 4_000 -> true
                state.finished && count <= 0 && stable >= 4 && elapsed >= 7_000 && locActions == 0 -> true
                state.finished && count <= 0 && stable >= 5 && reloaded -> true
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
        state.docStartInjected = installPageScript(wv, bootstrap)
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
