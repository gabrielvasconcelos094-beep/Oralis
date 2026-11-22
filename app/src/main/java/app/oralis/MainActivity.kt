package app.oralis

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oralis.data.settings.Settings
import app.oralis.ui.OralisNavHost
import app.oralis.ui.theme.OralisTheme

class MainActivity : ComponentActivity() {

    /** One-shot navigation request from notifications or shared text. */
    private var pendingRoute by mutableStateOf<String?>(null)
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val container = (application as OralisApp).container
        container.playerConnection.attach()
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (settings.theme) {
                app.oralis.data.settings.ThemeMode.SYSTEM -> systemDark
                app.oralis.data.settings.ThemeMode.LIGHT -> false
                app.oralis.data.settings.ThemeMode.DARK -> true
            }
            androidx.compose.runtime.DisposableEffect(dark) {
                // System bar icons follow the app theme, not only the system setting.
                val style = androidx.activity.SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                ) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            OralisTheme(settings.theme) {
                OralisNavHost(
                    container = container,
                    pendingRoute = pendingRoute,
                    onRouteConsumed = { pendingRoute = null },
                    sharedText = sharedText,
                    onSharedTextConsumed = { sharedText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute = it }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                sharedText = it
                pendingRoute = "create"
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
