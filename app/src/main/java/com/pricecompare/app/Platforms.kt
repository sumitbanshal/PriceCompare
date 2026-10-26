package com.pricecompare.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import java.net.URLEncoder

/**
 * One shopping platform. `{q}` in [defaultSearchUrl] is replaced by the URL-encoded query.
 * Search URLs can be overridden at runtime from the Setup screen (sites change them).
 *
 * [priceScale]: multiply API prices by this. 0 = auto-detect (rupees vs paise) by comparing
 * API numbers with the prices shown on the page.
 */
data class Platform(
    val id: String,
    val name: String,
    val homeUrl: String,
    val defaultSearchUrl: String,
    val color: Color,
    val priceScale: Double = 0.0,
    val setupHint: String,
)

object Platforms {
    val all = listOf(
        Platform(
            id = "blinkit", name = "Blinkit",
            homeUrl = "https://blinkit.com/",
            defaultSearchUrl = "https://blinkit.com/s/?q={q}",
            color = Color(0xFFE5B800),
            setupHint = "Tap the location bar at the top and use 'Detect my location' or type your area.",
        ),
        Platform(
            id = "zepto", name = "Zepto",
            homeUrl = "https://www.zepto.com/",
            defaultSearchUrl = "https://www.zepto.com/search?query={q}",
            color = Color(0xFF5E1A8C),
            setupHint = "Choose 'Select Location' and allow current location, or search your address.",
        ),
        Platform(
            id = "instamart", name = "Swiggy Instamart",
            homeUrl = "https://www.swiggy.com/instamart",
            defaultSearchUrl = "https://www.swiggy.com/instamart/search?custom_back=true&query={q}",
            color = Color(0xFFFC8019),
            setupHint = "Set your delivery location; signing in to Swiggy helps keep it.",
        ),
        Platform(
            id = "fkminutes", name = "Flipkart Minutes",
            homeUrl = "https://www.flipkart.com/",
            defaultSearchUrl = "https://www.flipkart.com/search?q={q}&marketplace=HYPERLOCAL",
            color = Color(0xFF1E5FD8),
            setupHint = "Sign in and set your pincode/address. Minutes only shows if it serves your area.",
        ),
        Platform(
            id = "flipkart", name = "Flipkart",
            homeUrl = "https://www.flipkart.com/",
            defaultSearchUrl = "https://www.flipkart.com/search?q={q}",
            color = Color(0xFF2874F0),
            setupHint = "Shares the Flipkart sign-in and pincode with Flipkart Minutes.",
        ),
        Platform(
            id = "amazonfresh", name = "Amazon Fresh",
            homeUrl = "https://www.amazon.in/",
            defaultSearchUrl = "https://www.amazon.in/s?k={q}&i=nowstore",
            color = Color(0xFF3F8F2F),
            setupHint = "Sign in to Amazon and set your delivery pincode (top of the page).",
        ),
        Platform(
            id = "amazon", name = "Amazon",
            homeUrl = "https://www.amazon.in/",
            defaultSearchUrl = "https://www.amazon.in/s?k={q}",
            color = Color(0xFF232F3E),
            setupHint = "Shares the Amazon sign-in and pincode with Amazon Fresh.",
        ),
        Platform(
            id = "dmart", name = "DMart Ready",
            homeUrl = "https://www.dmart.in/",
            defaultSearchUrl = "https://www.dmart.in/search?searchTerm={q}",
            color = Color(0xFF00843D),
            setupHint = "Enter your pincode when asked. DMart Ready serves limited areas.",
        ),
    )

    fun byId(id: String) = all.first { it.id == id }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pricecompare", Context.MODE_PRIVATE)

    fun isEnabled(p: Platform) = sp.getBoolean("enabled_${p.id}", true)
    fun setEnabled(p: Platform, value: Boolean) = sp.edit().putBoolean("enabled_${p.id}", value).apply()

    fun searchTemplate(p: Platform): String = sp.getString("url_${p.id}", null) ?: p.defaultSearchUrl
    fun setSearchTemplate(p: Platform, value: String?) {
        val e = sp.edit()
        if (value.isNullOrBlank() || value == p.defaultSearchUrl) e.remove("url_${p.id}") else e.putString("url_${p.id}", value.trim())
        e.apply()
    }

    fun searchUrl(p: Platform, query: String): String =
        searchTemplate(p).replace("{q}", URLEncoder.encode(query.trim(), "UTF-8"))

    var lastQuery: String
        get() = sp.getString("last_query", "") ?: ""
        set(v) = sp.edit().putString("last_query", v).apply()
}
