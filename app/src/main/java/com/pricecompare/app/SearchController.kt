package com.pricecompare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale

class SearchController(
    private val engine: ScrapeEngine,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    /** platformId -> latest result */
    val results = mutableStateMapOf<String, PlatformResult>()
    var running by mutableStateOf(false)
        private set
    var query by mutableStateOf(prefs.lastQuery)
        private set

    private var job: Job? = null
    private val parallel = Semaphore(3) // 3 WebViews at a time keeps memory reasonable

    fun search(q: String, platforms: List<Platform>) {
        val trimmed = q.trim()
        if (trimmed.isEmpty() || platforms.isEmpty()) return
        job?.cancel()
        query = trimmed
        prefs.lastQuery = trimmed
        results.clear()
        platforms.forEach { results[it.id] = PlatformResult(it, Status.WAITING) }
        running = true
        job = scope.launch {
            try {
                platforms.map { p -> launch { runOne(p, trimmed) } }.joinAll()
            } finally {
                running = false
            }
        }
    }

    fun retry(p: Platform) {
        val q = query.ifBlank { return }
        scope.launch { runOne(p, q) }
    }

    fun cancel() {
        job?.cancel()
        running = false
        results.entries.filter { it.value.status == Status.WAITING || it.value.status == Status.LOADING }
            .forEach { results[it.key] = it.value.copy(status = Status.ERROR, message = "Cancelled") }
    }

    private suspend fun runOne(p: Platform, q: String) {
        parallel.withPermit {
            results[p.id] = PlatformResult(p, Status.LOADING)
            results[p.id] = engine.scrape(p, prefs.searchUrl(p, q), q)
        }
    }
}

// ---------- ranking ----------

/** The unit (kg / L / pc) most results are measured in; only those are comparable by unit price. */
fun dominantUnit(items: List<Item>): String? =
    items.mapNotNull { it.unitBase }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

fun rankItems(items: List<Item>, byUnitPrice: Boolean, dominant: String? = dominantUnit(items)): List<Item> =
    items.sortedWith(
        compareBy<Item>(
            { !it.inStock },
            { if (byUnitPrice && !(it.unitBase == dominant && it.unitPrice != null)) 1 else 0 },
            { if (byUnitPrice) it.unitPrice ?: it.price else it.price },
        )
    )

/** Best matching item on each platform: most relevant, in stock, then cheapest. */
fun bestPerPlatform(results: Collection<PlatformResult>, byUnitPrice: Boolean): List<Item> {
    val all = results.flatMap { it.items }
    val dominant = dominantUnit(all)
    val best = results.mapNotNull { r ->
        if (r.items.isEmpty()) return@mapNotNull null
        val top = r.items.maxOf { it.relevance }
        rankItems(r.items.filter { it.relevance >= top - 0.01 }, byUnitPrice, dominant).firstOrNull()
    }
    return rankItems(best, byUnitPrice, dominant)
}

// ---------- location ----------

object LocationHelper {
    @SuppressLint("MissingPermission") // checked just below
    /** Pincode + locality of the phone's last known position, so you can check each site matches. */
    suspend fun currentArea(context: Context): String? = withContext(Dispatchers.IO) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        } ?: return@withContext null
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(context, Locale("en", "IN")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            listOfNotNull(a?.subLocality ?: a?.locality, a?.postalCode).joinToString(" · ").ifBlank { null }
        } catch (e: Exception) {
            String.format(Locale.US, "%.4f, %.4f", loc.latitude, loc.longitude)
        }
    }
}
