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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

// ======================= Search =======================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    prefs: Prefs,
    controller: SearchController,
    area: String?,
    onOpenSetup: () -> Unit,
    onOpen: (Platform, String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(controller.query) }
    var byUnit by rememberSaveable { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current

    val results = controller.results.values.sortedBy { Platforms.all.indexOf(it.platform) }
    val ok = results.filter { it.status == Status.OK }
    val best = bestPerPlatform(ok, byUnit)
    val all = rankItems(ok.flatMap { it.items }, byUnit)
    val failed = results.filter { it.status != Status.OK && it.status != Status.LOADING && it.status != Status.WAITING }

    fun go() {
        keyboard?.hide()
        controller.search(text, Platforms.all.filter { prefs.isEnabled(it) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PriceCompare", fontWeight = FontWeight.SemiBold)
                        if (area != null) Text("Phone location: $area", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { IconButton(onClick = onOpenSetup) { Icon(Icons.Default.Settings, "Setup") } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("e.g. amul butter 500 g") },
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
                    FilterChip(selected = byUnit, onClick = { byUnit = true }, label = { Text("Cheapest per kg / L / pc") })
                    FilterChip(selected = !byUnit, onClick = { byUnit = false }, label = { Text("Lowest price") })
                }
            }

            if (results.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Compare one product across 8 apps", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "First time? Open Setup (gear icon) and set the same delivery location on each app, " +
                                    "and sign in to Amazon and Flipkart. Then search here.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onOpenSetup) { Text("Open Setup") }
                        }
                    }
                }
            } else {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        results.forEach { r ->
                            StatusChip(r) {
                                // let the user fix location / login on the page itself, then retry
                                onOpen(r.platform, prefs.searchUrl(r.platform, controller.query))
                            }
                        }
                    }
                }
                if (failed.isNotEmpty() && !controller.running) {
                    item {
                        TextButton(onClick = { failed.forEach { controller.retry(it.platform) } }) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Retry ${failed.size} app${if (failed.size > 1) "s" else ""} (tap a chip first to fix location or login)")
                        }
                    }
                }
            }

            if (best.isNotEmpty()) {
                item { SectionTitle("Best match on each app") }
                items(best, key = { "best-" + it.platformId }) { item ->
                    ItemCard(item, highlight = item === best.first()) {
                        onOpen(Platforms.byId(item.platformId), item.url ?: prefs.searchUrl(Platforms.byId(item.platformId), controller.query))
                    }
                }
            }
            if (all.size > best.size) {
                item { SectionTitle("All matches (${all.size})") }
                items(all, key = { it.platformId + "|" + it.name + "|" + it.quantity + "|" + it.price }) { item ->
                    ItemCard(item, highlight = false) {
                        onOpen(Platforms.byId(item.platformId), item.url ?: prefs.searchUrl(Platforms.byId(item.platformId), controller.query))
                    }
                }
            }
        }
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
private fun ItemCard(item: Item, highlight: Boolean, onClick: () -> Unit) {
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
                item.unitPriceLabel()?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

// ======================= Setup =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(prefs: Prefs, area: String?, onBack: () -> Unit, onOpen: (Platform) -> Unit) {
    BackHandler(onBack = onBack)
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
                    "Open each app once and set the same delivery location" +
                        (area?.let { " (your phone is at $it)" } ?: "") +
                        ". Location buttons on the sites can use your phone's GPS. Sign-ins and locations are kept between searches.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(Platforms.all, key = { it.id }) { p ->
                val enabled = remember(version) { prefs.isEnabled(p) }
                val custom = remember(version) { prefs.searchTemplate(p) != p.defaultSearchUrl }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(p.color)
                            Spacer(Modifier.width(8.dp))
                            Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { prefs.setEnabled(p, it); version++ })
                        }
                        Text(p.setupHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { onOpen(p) }) { Text("Open & set location") }
                            TextButton(onClick = { editing = p }) { Text(if (custom) "Search URL (custom)" else "Search URL") }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { confirmClear = true }) {
                    Text("Sign out everywhere & clear saved locations", color = MaterialTheme.colorScheme.error)
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
                        "Put {q} where the search words go. If results stop working, search on the site, copy the address of the results page, and replace your words with {q}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = value.contains("{q}") && value.startsWith("http"),
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
            text = { Text("This signs you out of all 8 apps inside PriceCompare and forgets their delivery locations.") },
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

// ======================= Browser (visible, for setup and opening products) =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(platform: Platform, url: String, onClose: () -> Unit) {
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
