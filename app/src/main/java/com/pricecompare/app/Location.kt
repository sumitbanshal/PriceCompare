package com.pricecompare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

data class DeviceLocation(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val pincode: String?,
    val area: String?,
    val city: String?,
    val state: String?,
    val address: String?,
    /** true when the user typed a pincode instead of using GPS */
    val manual: Boolean,
    val time: Long,
) {
    /** Changes when you move to a different pincode (or ~1 km without one). */
    val fingerprint: String get() = pincode ?: String.format(Locale.US, "%.2f,%.2f", lat, lng)

    val label: String
        get() = listOfNotNull(area ?: city, pincode).joinToString(" · ")
            .ifBlank { String.format(Locale.US, "%.4f, %.4f", lat, lng) }

    fun toJson(): JSONObject = JSONObject()
        .put("lat", lat).put("lng", lng).put("acc", accuracy.toDouble())
        .put("pincode", pincode ?: JSONObject.NULL).put("area", area ?: JSONObject.NULL)
        .put("city", city ?: JSONObject.NULL).put("state", state ?: JSONObject.NULL)
        .put("address", address ?: JSONObject.NULL).put("manual", manual).put("time", time)
        .put("fp", fingerprint)

    companion object {
        fun fromJson(s: String?): DeviceLocation? = runCatching {
            val o = JSONObject(s ?: return null)
            fun str(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
            DeviceLocation(
                o.getDouble("lat"), o.getDouble("lng"), o.optDouble("acc", 50.0).toFloat(),
                str("pincode"), str("area"), str("city"), str("state"), str("address"),
                o.optBoolean("manual"), o.optLong("time"),
            )
        }.getOrNull()
    }
}

object LocationRepo {

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Fresh fix from the phone (falls back to the last known one), with pincode and area. */
    suspend fun fromDevice(context: Context): DeviceLocation? {
        if (!hasPermission(context)) return null
        val fix = freshFix(context) ?: lastKnown(context) ?: return null
        return describe(context, fix.latitude, fix.longitude, fix.accuracy, manual = false)
    }

    /** Location for a typed pincode (when GPS is off or wrong). */
    suspend fun fromPincode(context: Context, pincode: String): DeviceLocation? = withContext(Dispatchers.IO) {
        val pin = pincode.filter { it.isDigit() }
        if (pin.length != 6) return@withContext null
        val a = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale("en", "IN")).getFromLocationName("$pin, India", 1)?.firstOrNull()
        }.getOrNull() ?: return@withContext null
        DeviceLocation(
            a.latitude, a.longitude, 1000f, pin,
            a.subLocality ?: a.locality, a.locality ?: a.subAdminArea, a.adminArea,
            a.getAddressLine(0), manual = true, time = System.currentTimeMillis(),
        )
    }

    private suspend fun describe(context: Context, lat: Double, lng: Double, acc: Float, manual: Boolean): DeviceLocation =
        withContext(Dispatchers.IO) {
            val a = runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale("en", "IN")).getFromLocation(lat, lng, 1)?.firstOrNull()
            }.getOrNull()
            DeviceLocation(
                lat, lng, acc,
                a?.postalCode?.filter { it.isDigit() }?.takeIf { it.length == 6 },
                a?.subLocality ?: a?.locality,
                a?.locality ?: a?.subAdminArea,
                a?.adminArea,
                a?.getAddressLine(0),
                manual, System.currentTimeMillis(),
            )
        }

    @SuppressLint("MissingPermission") // callers check hasPermission()
    private suspend fun freshFix(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val order = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val provider = order.firstOrNull { it in enabled } ?: return null
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }.getOrNull()
    }
}

/**
 * Pushes the device location into the sites: a page script (geolocation + popup filler + Amazon pincode),
 * location cookies for sites that read them, and clearing a site's old location after you move.
 */
class LocationSync(private val context: Context, private val prefs: Prefs) {

    private val locationJs: String by lazy {
        context.assets.open("location.js").bufferedReader().use { it.readText() }
    }

    /** Script to run at document start on every platform page. Empty when location is unknown. */
    fun pageScript(loc: DeviceLocation?): String =
        if (loc == null) "" else "window.__PC_LOC_CFG=${loc.toJson()};\n$locationJs\n"

    /** Call before loading a platform (search or visible browser). */
    fun prepare(p: Platform, loc: DeviceLocation?) {
        if (loc == null) return
        val applied = prefs.appliedFingerprint(p)
        if (applied != null && applied != loc.fingerprint && p.resetOnMove) clearSite(p)
        setLocationCookies(p, loc)
        prefs.setAppliedFingerprint(p, loc.fingerprint)
    }

    private fun setLocationCookies(p: Platform, loc: DeviceLocation) {
        val cm = CookieManager.getInstance()
        val year = "; path=/; max-age=31536000"
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        when (p.id) {
            "blinkit" -> {
                val u = "https://blinkit.com"
                cm.setCookie(u, "gr_1_lat=${loc.lat}$year")
                cm.setCookie(u, "gr_1_lon=${loc.lng}$year")
                loc.city?.let { cm.setCookie(u, "gr_1_locality=${enc(it)}$year") }
                (loc.area ?: loc.city)?.let { cm.setCookie(u, "gr_1_landmark=${enc(it)}$year") }
            }
            "instamart" -> {
                val u = "https://www.swiggy.com"
                val json = JSONObject()
                    .put("lat", loc.lat).put("lng", loc.lng)
                    .put("address", loc.address ?: loc.label).put("area", loc.area ?: "")
                    .put("showUserDefaultAddressHint", false).put("id", "").put("annotation", "").put("name", "")
                cm.setCookie(u, "userLocation=${enc(json.toString())}; domain=.swiggy.com$year")
                cm.setCookie(u, "lat=${loc.lat}; domain=.swiggy.com$year")
                cm.setCookie(u, "lng=${loc.lng}; domain=.swiggy.com$year")
            }
        }
        cm.flush()
    }

    /** Forget a (no-sign-in) site's stored location so it asks again and gets the new one. */
    private fun clearSite(p: Platform) {
        val cm = CookieManager.getInstance()
        for (d in p.domains) {
            val url = "https://$d"
            val names = cm.getCookie(url)?.split(";")?.map { it.substringBefore("=").trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            for (n in names) {
                cm.setCookie(url, "$n=; max-age=0; path=/")
                cm.setCookie(url, "$n=; max-age=0; path=/; domain=$d")
                cm.setCookie(url, "$n=; max-age=0; path=/; domain=.${d.removePrefix("www.")}")
            }
            WebStorage.getInstance().deleteOrigin(url)
        }
        cm.flush()
    }
}
