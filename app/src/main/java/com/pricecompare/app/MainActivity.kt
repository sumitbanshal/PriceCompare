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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    data class Browser(val platform: Platform, val url: String, val back: Screen) : Screen
}

class MainActivity : ComponentActivity() {

    private var locationGrant by mutableIntStateOf(0)

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationGrant++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hidden scraper WebViews live in `scraperHost`, drawn underneath the opaque Compose UI.
        val root = FrameLayout(this)
        val scraperHost = FrameLayout(this)
        root.addView(scraperHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val prefs = Prefs(this)
        val engine = ScrapeEngine(this, scraperHost)
        val controller = SearchController(engine, prefs, lifecycleScope)

        val compose = ComposeView(this).apply {
            setContent {
                AppTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App(prefs, controller, locationGrant)
                    }
                }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
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
fun App(prefs: Prefs, controller: SearchController, locationGrant: Int) {
    var screen by remember { mutableStateOf<Screen>(Screen.Search) }
    var area by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(locationGrant) { area = LocationHelper.currentArea(context) }

    when (val s = screen) {
        Screen.Search -> SearchScreen(
            prefs = prefs,
            controller = controller,
            area = area,
            onOpenSetup = { screen = Screen.Setup },
            onOpen = { p, url -> screen = Screen.Browser(p, url, Screen.Search) },
        )
        Screen.Setup -> SetupScreen(
            prefs = prefs,
            area = area,
            onBack = { screen = Screen.Search },
            onOpen = { p -> screen = Screen.Browser(p, p.homeUrl, Screen.Setup) },
        )
        is Screen.Browser -> BrowserScreen(
            platform = s.platform,
            url = s.url,
            onClose = { screen = s.back },
        )
    }
}
