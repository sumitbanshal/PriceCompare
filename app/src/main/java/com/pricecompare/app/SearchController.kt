package com.pricecompare.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class SearchController(
    private val context: Context,
    private val engine: ScrapeEngine,
    private val sync: LocationSync,
    val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    /** platformId -> latest result, for the current category */
    val results = mutableStateMapOf<String, PlatformResult>()
    var running by mutableStateOf(false)
        private set
    var query by mutableStateOf(prefs.lastQuery)
        private set
    var category by mutableStateOf(prefs.category)
        private set

    // ---- location ----
    var location by mutableStateOf(DeviceLocation.fromJson(prefs.deviceLocationJson))
        private set
    var locating by mutableStateOf(false)
        private set
    var locationError by mutableStateOf<String?>(null)
        private set

    private data class Snapshot(val query: String, val fingerprint: String?, val results: Map<String, PlatformResult>)
    private val cache = mutableMapOf<Category, Snapshot>()

    private var job: Job? = null
    private val parallel = Semaphore(3) // 3 WebViews at a time keeps memory reasonable

    fun hasLocationPermission() = LocationRepo.hasPermission(context)

    /** Re-read the phone's location (skipped when the user pinned a pincode, unless [force]). */
    fun refreshLocation(force: Boolean = false) {
        if (locating) return
        if (location?.manual == true && !force) return
        if (!LocationRepo.hasPermission(context)) {
            locationError = "Location permission is off"
            return
        }
        locating = true
        locationError = null
        scope.launch {
            try {
                val loc = LocationRepo.fromDevice(context)
                if (loc == null) locationError = "Couldn't get a location fix. Turn on location, or enter a pincode."
                else applyLocation(loc)
            } finally {
                locating = false
            }
        }
    }

    fun usePincode(pin: String, onDone: (Boolean) -> Unit) {
        locating = true
        scope.launch {
            try {
                val loc = LocationRepo.fromPincode(context, pin)
                if (loc != null) applyLocation(loc)
                onDone(loc != null)
            } finally {
                locating = false
            }
        }
    }

    private fun applyLocation(loc: DeviceLocation) {
        val moved = location?.fingerprint != loc.fingerprint
        location = loc
        locationError = null
        prefs.deviceLocationJson = loc.toJson().toString()
        if (moved) cache.clear() // prices/availability differ by area
    }

    /** Script + cookies so a visible page opens at the same location. */
    fun prepareForBrowser(p: Platform): String {
        sync.prepare(p, location)
        return sync.pageScript(location)
    }

    // ---- searching ----

    fun enabledPlatforms(c: Category = category) = Platforms.forCategory(c).filter { prefs.isEnabled(c, it) }

    fun selectCategory(c: Category) {
        if (c == category) return
        if (!running) cache[category] = Snapshot(query, location?.fingerprint, results.toMap())
        job?.cancel()
        running = false
        category = c
        prefs.category = c
        results.clear()
        val snap = cache[c]
        if (snap != null && snap.query == query && snap.fingerprint == location?.fingerprint) results.putAll(snap.results)
        else if (query.isNotBlank()) search(query)
    }

    fun search(q: String) {
        val trimmed = q.trim()
        val platforms = enabledPlatforms()
        if (trimmed.isEmpty() || platforms.isEmpty()) return
        job?.cancel()
        if (trimmed != query) cache.clear()
        query = trimmed
        prefs.lastQuery = trimmed
        results.clear()
        platforms.forEach { results[it.id] = PlatformResult(it, Status.WAITING) }
        running = true
        val cat = category
        job = scope.launch {
            try {
                platforms.map { p -> launch { runOne(p, trimmed, cat) } }.joinAll()
            } finally {
                if (category == cat) running = false
            }
        }
    }

    fun retry(p: Platform) {
        val q = query.ifBlank { return }
        val cat = category
        scope.launch { runOne(p, q, cat) }
    }

    fun cancel() {
        job?.cancel()
        running = false
        results.entries.filter { it.value.status == Status.WAITING || it.value.status == Status.LOADING }
            .forEach { results[it.key] = it.value.copy(status = Status.ERROR, message = "Cancelled") }
    }

    private suspend fun runOne(p: Platform, q: String, cat: Category) {
        parallel.withPermit {
            if (category != cat) return
            results[p.id] = PlatformResult(p, Status.LOADING)
            val loc = location
            sync.prepare(p, loc)
            val r = engine.scrape(p, prefs.searchUrl(p, q, cat), q, sync.pageScript(loc))
            if (category == cat) results[p.id] = r
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
