package com.pricecompare.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import java.net.URLEncoder
import java.util.Locale

enum class Category(
    val label: String,
    val platformIds: List<String>,
    /** Rank by ₹/kg, ₹/L, ₹/pc by default (packaged goods); otherwise by price. */
    val sortByUnit: Boolean,
    val example: String,
) {
    GROCERY(
        "Grocery",
        listOf("blinkit", "zepto", "instamart", "fkminutes", "dmart", "amazonfresh", "amazon", "flipkart"),
        sortByUnit = true, example = "e.g. amul butter 500 g",
    ),
    APPAREL(
        "Apparel",
        listOf("myntra", "ajio", "amazon", "flipkart"),
        sortByUnit = false, example = "e.g. levis 511 jeans",
    ),
    BEAUTY(
        "Beauty",
        listOf("nykaa", "purplle", "blinkit", "zepto", "instamart", "myntra", "amazon", "flipkart"),
        sortByUnit = true, example = "e.g. lakme sunscreen 50 ml",
    ),
    ELECTRONICS(
        "Electronics",
        listOf("croma", "reliancedigital", "amazon", "flipkart", "fkminutes", "blinkit", "zepto", "instamart"),
        sortByUnit = false, example = "e.g. boat airdopes 141",
    ),
}

/**
 * One shopping platform.
 * Search URL placeholders: `{q}` = URL-encoded query, `{slug}` = "blue-jeans" style.
 * Search URLs can be overridden at runtime from Setup (sites change them).
 *
 * [priceScale]: multiply API prices by this. 0 = auto-detect rupees vs paise.
 * [domains]: sites whose stored location is cleared when you move ([resetOnMove]),
 *            so they pick up the new phone location. Only for sites that don't need a sign-in.
 */
data class Platform(
    val id: String,
    val name: String,
    val homeUrl: String,
    val defaultSearchUrl: String,
    val color: Color,
    val priceScale: Double = 0.0,
    val setupHint: String,
    val categoryUrls: Map<Category, String> = emptyMap(),
    val domains: List<String> = emptyList(),
    val resetOnMove: Boolean = false,
) {
    val categories: List<Category> get() = Category.entries.filter { id in it.platformIds }
}

object Platforms {
    val all = listOf(
        Platform(
            id = "blinkit", name = "Blinkit",
            homeUrl = "https://blinkit.com/",
            defaultSearchUrl = "https://blinkit.com/s/?q={q}",
            color = Color(0xFFE5B800),
            setupHint = "Location is set automatically. Sign-in not needed.",
            domains = listOf("blinkit.com"), resetOnMove = true,
        ),
        Platform(
            id = "zepto", name = "Zepto",
            homeUrl = "https://www.zepto.com/",
            defaultSearchUrl = "https://www.zepto.com/search?query={q}",
            color = Color(0xFF5E1A8C),
            setupHint = "Location is set automatically. Sign-in not needed.",
            domains = listOf("zepto.com", "www.zepto.com"), resetOnMove = true,
        ),
        Platform(
            id = "instamart", name = "Swiggy Instamart",
            homeUrl = "https://www.swiggy.com/instamart",
            defaultSearchUrl = "https://www.swiggy.com/instamart/search?custom_back=true&query={q}",
            color = Color(0xFFFC8019),
            setupHint = "Location is set automatically. Signing in to Swiggy helps it stick.",
        ),
        Platform(
            id = "fkminutes", name = "Flipkart Minutes",
            homeUrl = "https://www.flipkart.com/",
            defaultSearchUrl = "https://www.flipkart.com/search?q={q}&marketplace=HYPERLOCAL",
            color = Color(0xFF1E5FD8),
            setupHint = "Needs a Flipkart sign-in with a saved address near you; only shows where Minutes delivers.",
        ),
        Platform(
            id = "flipkart", name = "Flipkart",
            homeUrl = "https://www.flipkart.com/",
            defaultSearchUrl = "https://www.flipkart.com/search?q={q}",
            color = Color(0xFF2874F0),
            setupHint = "Pincode is filled in automatically when Flipkart asks. Shares sign-in with Minutes.",
        ),
        Platform(
            id = "amazonfresh", name = "Amazon Fresh",
            homeUrl = "https://www.amazon.in/",
            defaultSearchUrl = "https://www.amazon.in/s?k={q}&i=nowstore",
            color = Color(0xFF3F8F2F),
            setupHint = "Pincode is set automatically. Signing in to Amazon may be needed for Fresh.",
        ),
        Platform(
            id = "amazon", name = "Amazon",
            homeUrl = "https://www.amazon.in/",
            defaultSearchUrl = "https://www.amazon.in/s?k={q}",
            color = Color(0xFF232F3E),
            setupHint = "Pincode is set automatically.",
            categoryUrls = mapOf(
                Category.APPAREL to "https://www.amazon.in/s?k={q}&i=apparel",
                Category.BEAUTY to "https://www.amazon.in/s?k={q}&i=beauty",
                Category.ELECTRONICS to "https://www.amazon.in/s?k={q}&i=electronics",
            ),
        ),
        Platform(
            id = "dmart", name = "DMart Ready",
            homeUrl = "https://www.dmart.in/",
            defaultSearchUrl = "https://www.dmart.in/search?searchTerm={q}",
            color = Color(0xFF00843D),
            setupHint = "Pincode is filled in automatically. DMart Ready serves limited areas.",
            domains = listOf("www.dmart.in", "dmart.in"), resetOnMove = true,
        ),
        Platform(
            id = "myntra", name = "Myntra",
            homeUrl = "https://www.myntra.com/",
            defaultSearchUrl = "https://www.myntra.com/{slug}?rawQuery={q}",
            color = Color(0xFFFF3F6C),
            setupHint = "Prices don't depend on location; pincode only affects delivery.",
        ),
        Platform(
            id = "ajio", name = "AJIO",
            homeUrl = "https://www.ajio.com/",
            defaultSearchUrl = "https://www.ajio.com/search/?text={q}",
            color = Color(0xFF2C4152),
            setupHint = "Prices don't depend on location; pincode only affects delivery.",
        ),
        Platform(
            id = "nykaa", name = "Nykaa",
            homeUrl = "https://www.nykaa.com/",
            defaultSearchUrl = "https://www.nykaa.com/search/result/?q={q}",
            color = Color(0xFFFC2779),
            setupHint = "Prices don't depend on location; pincode only affects delivery.",
        ),
        Platform(
            id = "purplle", name = "Purplle",
            homeUrl = "https://www.purplle.com/",
            defaultSearchUrl = "https://www.purplle.com/search?q={q}",
            color = Color(0xFF7B3FE4),
            setupHint = "Prices don't depend on location; pincode only affects delivery.",
        ),
        Platform(
            id = "croma", name = "Croma",
            homeUrl = "https://www.croma.com/",
            defaultSearchUrl = "https://www.croma.com/searchB?q={q}%3Arelevance&text={q}",
            color = Color(0xFF0F9D8A),
            setupHint = "Pincode is filled in automatically when Croma asks.",
        ),
        Platform(
            id = "reliancedigital", name = "Reliance Digital",
            homeUrl = "https://www.reliancedigital.in/",
            defaultSearchUrl = "https://www.reliancedigital.in/products?q={q}",
            color = Color(0xFFE42529),
            setupHint = "Pincode is filled in automatically when the site asks.",
        ),
    )

    fun byId(id: String) = all.first { it.id == id }
    fun forCategory(c: Category) = c.platformIds.map { byId(it) }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pricecompare", Context.MODE_PRIVATE)

    fun isEnabled(c: Category, p: Platform) = sp.getBoolean("enabled_${c.name}_${p.id}", true)
    fun setEnabled(c: Category, p: Platform, value: Boolean) = sp.edit().putBoolean("enabled_${c.name}_${p.id}", value).apply()

    fun customTemplate(p: Platform): String? = sp.getString("url_${p.id}", null)
    fun searchTemplate(p: Platform, c: Category? = null): String =
        customTemplate(p) ?: c?.let { p.categoryUrls[it] } ?: p.defaultSearchUrl
    fun setSearchTemplate(p: Platform, value: String?) {
        val e = sp.edit()
        if (value.isNullOrBlank() || value == p.defaultSearchUrl) e.remove("url_${p.id}") else e.putString("url_${p.id}", value.trim())
        e.apply()
    }

    fun searchUrl(p: Platform, query: String, c: Category? = null): String {
        val q = query.trim()
        val slug = q.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return searchTemplate(p, c)
            .replace("{q}", URLEncoder.encode(q, "UTF-8").replace("+", "%20"))
            .replace("{slug}", slug)
    }

    var lastQuery: String
        get() = sp.getString("last_query", "") ?: ""
        set(v) = sp.edit().putString("last_query", v).apply()

    var category: Category
        get() = runCatching { Category.valueOf(sp.getString("category", null) ?: "") }.getOrDefault(Category.GROCERY)
        set(v) = sp.edit().putString("category", v.name).apply()

    /** Last device location (JSON), and which location each platform was last set to. */
    var deviceLocationJson: String?
        get() = sp.getString("device_loc", null)
        set(v) = sp.edit().putString("device_loc", v).apply()

    fun appliedFingerprint(p: Platform): String? = sp.getString("applied_${p.id}", null)
    fun setAppliedFingerprint(p: Platform, fp: String) = sp.edit().putString("applied_${p.id}", fp).apply()
}
