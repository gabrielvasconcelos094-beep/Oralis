package app.oralis.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.oralis.AppContainer
import app.oralis.ui.create.CreateScreen
import app.oralis.ui.generation.GenerationScreen
import app.oralis.ui.home.HomeScreen
import app.oralis.ui.library.LibraryScreen
import app.oralis.ui.player.PlayerScreen
import app.oralis.ui.settings.LicensesScreen
import app.oralis.ui.settings.ModelScreen
import app.oralis.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val CREATE = "create"
    const val GENERATION = "generation/{id}"
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val PLAYER_ID = "player/{id}"
    const val SETTINGS = "settings"
    const val MODEL = "model"
    const val LICENSES = "licenses"
    fun generation(id: String) = "generation/$id"
    fun player(id: String) = "player/$id"
}

@Composable
fun OralisNavHost(
    container: AppContainer,
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
) {
    val nav = rememberNavController()

    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        when {
            route == "generation" -> {
                val live = container.generationRepository.live.value
                if (live != null) nav.navigate(Routes.generation(live.audioId)) else nav.navigate(Routes.LIBRARY)
            }
            else -> nav.navigate(route) { launchSingleTop = true }
        }
        onRouteConsumed()
    }

    // Surface (not a plain Box) so every screen inherits the right content colour (dark theme).
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 } },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 } },
        ) {
            composable(Routes.HOME) {
                HomeScreen(container, nav)
            }
            composable(Routes.CREATE) {
                CreateScreen(container, nav, sharedText, onSharedTextConsumed)
            }
            composable(Routes.GENERATION, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                GenerationScreen(container, nav, entry.arguments?.getString("id").orEmpty())
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(container, nav)
            }
            composable(Routes.PLAYER) {
                PlayerScreen(container, nav, playId = null)
            }
            composable(Routes.PLAYER_ID, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                PlayerScreen(container, nav, playId = entry.arguments?.getString("id"))
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(container, nav)
            }
            composable(Routes.MODEL) {
                ModelScreen(container, nav)
            }
            composable(Routes.LICENSES) {
                LicensesScreen(nav)
            }
        }
    }
}
