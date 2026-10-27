package com.pricecompare.app

import android.Manifest
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope

sealed interface Screen {
    data object Search : Screen
    data object Setup : Screen
    data class Browser(val platform: Platform, val url: String, val pageScript: String, val back: Screen) : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var controller: SearchController

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.refreshLocation(force = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hidden scraper WebViews live in `scraperHost`, drawn underneath the opaque Compose UI.
        val root = FrameLayout(this)
        val scraperHost = FrameLayout(this)
        root.addView(scraperHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val prefs = Prefs(this)
        val engine = ScrapeEngine(this, scraperHost)
        controller = SearchController(this, engine, LocationSync(this, prefs), prefs, lifecycleScope)

        val compose = ComposeView(this).apply {
            setContent {
                AppTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App(controller, requestLocationPermission = { askForLocation() })
                    }
                }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        if (LocationRepo.hasPermission(this)) controller.refreshLocation() else askForLocation()
    }

    private fun askForLocation() {
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun onResume() {
        super.onResume()
        // pick up a new area when you come back to the app (not when a pincode was typed in)
        val loc = controller.location
        if (LocationRepo.hasPermission(this) && (loc == null || System.currentTimeMillis() - loc.time > 5 * 60_000)) {
            controller.refreshLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush() // keep site logins/locations
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF7BD88F), secondary = Color(0xFFFFC857))
    else lightColorScheme(primary = Color(0xFF0B7A3B), secondary = Color(0xFFB7791F))
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(controller: SearchController, requestLocationPermission: () -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Search) }

    fun open(p: Platform, url: String, back: Screen) {
        screen = Screen.Browser(p, url, controller.prepareForBrowser(p), back)
    }

    when (val s = screen) {
        Screen.Search -> SearchScreen(
            controller = controller,
            requestLocationPermission = requestLocationPermission,
            onOpenSetup = { screen = Screen.Setup },
            onOpen = { p, url -> open(p, url, Screen.Search) },
        )
        Screen.Setup -> SetupScreen(
            controller = controller,
            onBack = { screen = Screen.Search },
            onOpen = { p -> open(p, p.homeUrl, Screen.Setup) },
        )
        is Screen.Browser -> BrowserScreen(
            platform = s.platform,
            url = s.url,
            pageScript = s.pageScript,
            onClose = { screen = s.back },
        )
    }
}
