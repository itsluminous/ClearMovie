package com.itsluminous.clearmovie.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.itsluminous.clearmovie.ui.home.HomeScreen
import com.itsluminous.clearmovie.ui.settings.SettingsRoute

/** Navigation route ids (not shown to the user). */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
}

/** The app's navigation graph. The back stack survives rotation and process death. */
@Composable
fun ClearMovieApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            // dropUnlessResumed: a double tap during the transition navigates once.
            HomeScreen(onOpenSettings = dropUnlessResumed { navController.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
}
