package com.pricecompare.app

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

// ======================= Search =======================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    controller: SearchController,
    requestLocationPermission: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpen: (Platform, String) -> Unit,
) {
    val prefs = controller.prefs
    val category = controller.category
    var text by rememberSaveable { mutableStateOf(controller.query) }
    // per category default: unit price for groceries/beauty, plain price for apparel/electronics
    var byUnitOverride by remember(category) { mutableStateOf<Boolean?>(null) }
    val byUnit = byUnitOverride ?: category.sortByUnit
    val keyboard = LocalSoftwareKeyboardController.current

    val order = Platforms.forCategory(category)
    val results = controller.results.values.sortedBy { order.indexOf(it.platform) }
    val ok = results.filter { it.status == Status.OK }
    val best = bestPerPlatform(ok, byUnit)
    val all = rankItems(ok.flatMap { it.items }, byUnit)
    val failed = results.filter { it.status != Status.OK && it.status != Status.LOADING && it.status != Status.WAITING }

    fun go() {
        keyboard?.hide()
        controller.search(text)
    }
    fun searchUrl(p: Platform) = prefs.searchUrl(p, controller.query, category)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PriceCompare", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = onOpenSetup) { Icon(Icons.Default.Settings, "Setup") } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { LocationBar(controller, requestLocationPermission) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Category.entries.forEach { c ->
                        FilterChip(
                            selected = c == category,
                            onClick = { controller.selectCategory(c) },
                            label = { Text(c.label) },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(category.example) },
                    trailingIcon = {
                        if (controller.running) {
                            IconButton(onClick = { controller.cancel() }) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = { go() }) { Icon(Icons.Default.Search, "Search") }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = byUnit, onClick = { byUnitOverride = true }, label = { Text("Cheapest per kg / L / pc") })
                    FilterChip(selected = !byUnit, onClick = { byUnitOverride = false }, label = { Text("Lowest price") })
                }
            }

            if (results.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${category.label}: compare across ${order.size} apps", style = MaterialTheme.typography.titleMedium)
                            Text(
                                order.joinToString(", ") { it.name } + ". Your phone's location is applied to every app automatically. " +
                                    "For Flipkart Minutes and Amazon Fresh, sign in once from Setup.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            } else {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        results.forEach { r ->
                            StatusChip(r) { onOpen(r.platform, searchUrl(r.platform)) }
                        }
                    }
                }
                if (failed.isNotEmpty() && !controller.running) {
                    item {
                        TextButton(onClick = { failed.forEach { controller.retry(it.platform) } }) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Retry ${failed.size} app${if (failed.size > 1) "s" else ""} (tap a chip to see the page)")
                        }
                    }
                }
            }

            if (best.isNotEmpty()) {
                item { SectionTitle("Best match on each app") }
                items(best, key = { "best-" + it.platformId }) { item ->
                    val p = Platforms.byId(item.platformId)
                    ItemCard(item, highlight = item === best.first(), showUnit = byUnit || category.sortByUnit) {
                        onOpen(p, item.url ?: searchUrl(p))
                    }
                }
            }
            if (all.size > best.size) {
                item { SectionTitle("All matches (${all.size})") }
                items(all, key = { it.platformId + "|" + it.name + "|" + it.quantity + "|" + it.price }) { item ->
                    val p = Platforms.byId(item.platformId)
                    ItemCard(item, highlight = false, showUnit = byUnit || category.sortByUnit) {
                        onOpen(p, item.url ?: searchUrl(p))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationBar(controller: SearchController, requestLocationPermission: () -> Unit) {
    val loc = controller.location
    var editing by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        loc != null -> "Delivering to ${loc.label}"
                        controller.locating -> "Finding your location…"
                        else -> "Location not set"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        controller.locationError != null && loc == null -> controller.locationError!!
                        loc?.manual == true -> "Pincode you entered · applied to all apps"
                        loc != null -> "From your phone · applied to all apps"
                        else -> "Needed so every app shows prices for the same place"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (controller.locating) {
                CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = {
                    if (controller.hasLocationPermission()) controller.refreshLocation(force = true)
                    else requestLocationPermission()
                }) { Icon(Icons.Default.Refresh, "Use phone location") }
            }
            IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Enter pincode") }
        }
    }

    if (editing) {
        var pin by remember { mutableStateOf(loc?.pincode ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Delivery location") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a pincode to compare prices there instead of your phone's location.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6); error = null },
                        label = { Text("Pincode") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = error != null,
                        supportingText = { error?.let { Text(it) } },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pin.length == 6 && !controller.locating, onClick = {
                    controller.usePincode(pin) { okay -> if (okay) editing = false else error = "Couldn't find that pincode" }
                }) { Text("Use pincode") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editing = false
                    if (controller.hasLocationPermission()) controller.refreshLocation(force = true)
                    else requestLocationPermission()
                }) { Text("Use phone location") }
            },
        )
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun StatusChip(r: PlatformResult, onClick: () -> Unit) {
    val label = when (r.status) {
        Status.OK -> "${r.platform.name} · ${r.items.size}"
        Status.ERROR -> "${r.platform.name} · ${r.message ?: "Error"}"
        else -> "${r.platform.name} · ${r.status.label}"
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            if (r.status == Status.LOADING) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            else Dot(if (r.status == Status.OK) r.platform.color else MaterialTheme.colorScheme.outline)
        },
    )
}

@Composable
private fun ItemCard(item: Item, highlight: Boolean, showUnit: Boolean, onClick: () -> Unit) {
    val p = Platforms.byId(item.platformId)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (highlight) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp)).background(p.color)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) { Text(p.name, color = Color.White, style = MaterialTheme.typography.labelSmall) }
                    if (highlight) {
                        Spacer(Modifier.width(6.dp))
                        Text("Best deal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.quantity?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!item.inStock) Text("Out of stock", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatRupees(item.price), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (item.mrp != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(formatRupees(item.mrp), style = MaterialTheme.typography.labelSmall, textDecoration = TextDecoration.LineThrough)
                        item.discountPct?.takeIf { it > 0 }?.let {
                            Text("$it% off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (showUnit) item.unitPriceLabel()?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

// ======================= Setup =======================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(controller: SearchController, onBack: () -> Unit, onOpen: (Platform) -> Unit) {
    BackHandler(onBack = onBack)
    val prefs = controller.prefs
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Platform?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Location is applied to every app automatically" +
                        (controller.location?.let { " (now: ${it.label})" } ?: "") +
                        ". Sign in to Flipkart and Amazon once here for Flipkart Minutes and Amazon Fresh. " +
                        "Tap a category under an app to include or skip it there.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(Platforms.all, key = { it.id }) { p ->
                val custom = remember(version) { prefs.customTemplate(p) != null }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(p.color)
                            Spacer(Modifier.width(8.dp))
                            Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        }
                        Text(p.setupHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.categories.forEach { c ->
                                val on = remember(version) { prefs.isEnabled(c, p) }
                                FilterChip(selected = on, onClick = { prefs.setEnabled(c, p, !on); version++ }, label = { Text(c.label) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { onOpen(p) }) { Text("Open / sign in") }
                            TextButton(onClick = { editing = p }) { Text(if (custom) "Search URL (custom)" else "Search URL") }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { confirmClear = true }) {
                    Text("Sign out everywhere & clear site data", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    editing?.let { p ->
        var value by remember(p) { mutableStateOf(prefs.searchTemplate(p)) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${p.name} search URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Put {q} where the search words go ({slug} for words-joined-by-dashes). If results stop working, search on the site, copy the address of the results page, and replace your words with {q}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = (value.contains("{q}") || value.contains("{slug}")) && value.startsWith("http"),
                    onClick = { prefs.setSearchTemplate(p, value); editing = null; version++ },
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { prefs.setSearchTemplate(p, null); editing = null; version++ }) { Text("Reset") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear everything?") },
            text = { Text("This signs you out of all apps inside PriceCompare and clears their saved data. Your location will be applied again on the next search.") },
            confirmButton = {
                TextButton(onClick = {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    confirmClear = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

// ======================= Browser (visible, opens at your location) =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(platform: Platform, url: String, pageScript: String, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf(platform.name) }
    var progress by remember { mutableIntStateOf(0) }

    val close = {
        CookieManager.getInstance().flush()
        onClose()
    }
    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else close()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Default.Close, "Close") } },
                actions = { IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "Reload") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        configureWebView(this)
                        // phone location for "detect location", pincode popups and Amazon's pincode
                        val injected = installPageScript(this, pageScript)
                        webChromeClient = object : WebChromeClient() {
                            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                                callback?.invoke(origin, true, true)
                            }
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme ?: return true
                                return scheme != "http" && scheme != "https"
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                title = platform.name
                                if (!injected && pageScript.isNotBlank()) view.evaluateJavascript(pageScript, null)
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                title = view.title?.takeIf { it.isNotBlank() } ?: platform.name
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
