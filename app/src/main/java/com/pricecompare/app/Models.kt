package com.pricecompare.app

import org.json.JSONObject

data class Item(
    val platformId: String,
    val name: String,
    val price: Double,
    val mrp: Double?,
    val discountPct: Int?,
    val quantity: String?,
    val unitBase: String?,     // "kg", "L" or "pc"
    val unitPrice: Double?,    // ₹ per unitBase
    val inStock: Boolean,
    val url: String?,
    val source: String,        // api / ssr / dom
    val relevance: Double,
)

enum class Status(val label: String) {
    WAITING("Waiting"),
    LOADING("Searching…"),
    OK("OK"),
    NEEDS_LOCATION("Set location"),
    NEEDS_LOGIN("Sign in"),
    NOT_SERVICEABLE("Not in your area"),
    NO_RESULTS("No match"),
    ERROR("Error"),
}

data class PlatformResult(
    val platform: Platform,
    val status: Status,
    val items: List<Item> = emptyList(),
    val message: String? = null,
    val apiHits: Int = 0,
    val millis: Long = 0,
)

private fun JSONObject.optDoubleOrNull(k: String): Double? =
    if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

private fun JSONObject.optStringOrNull(k: String): String? =
    if (isNull(k) || !has(k)) null else optString(k).takeIf { it.isNotBlank() }

fun parseFinish(json: String, p: Platform, millis: Long): PlatformResult {
    val o = JSONObject(json)
    val arr = o.optJSONArray("items")
    val items = buildList {
        if (arr != null) for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val price = j.optDoubleOrNull("price") ?: continue
            add(
                Item(
                    platformId = p.id,
                    name = j.optString("name"),
                    price = price,
                    mrp = j.optDoubleOrNull("mrp"),
                    discountPct = if (j.isNull("discountPct") || !j.has("discountPct")) null else j.optInt("discountPct"),
                    quantity = j.optStringOrNull("quantity"),
                    unitBase = j.optStringOrNull("unitBase"),
                    unitPrice = j.optDoubleOrNull("unitPrice"),
                    inStock = j.optBoolean("inStock", true),
                    url = j.optStringOrNull("url"),
                    source = j.optString("source"),
                    relevance = j.optDouble("relevance", 0.0),
                )
            )
        }
    }
    val status = when (o.optString("status")) {
        "ok" -> if (items.isEmpty()) Status.NO_RESULTS else Status.OK
        "needs_location" -> Status.NEEDS_LOCATION
        "needs_login" -> Status.NEEDS_LOGIN
        "not_serviceable" -> Status.NOT_SERVICEABLE
        "no_results" -> Status.NO_RESULTS
        else -> if (items.isEmpty()) Status.NO_RESULTS else Status.OK
    }
    return PlatformResult(p, status, items, apiHits = o.optInt("apiHits"), millis = millis)
}

fun formatRupees(v: Double): String =
    if (v == Math.floor(v) && v < 100000) "₹" + v.toLong() else "₹" + String.format("%.2f", v)

fun Item.unitPriceLabel(): String? {
    val up = unitPrice ?: return null
    val base = unitBase ?: return null
    return formatRupees(up) + "/" + base
}
