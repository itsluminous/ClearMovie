package com.itsluminous.clearmovie.ui.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.project.LaunchDecision
import com.itsluminous.clearmovie.domain.model.ProjectId
import com.itsluminous.clearmovie.ui.editor.EditorRoute
import com.itsluminous.clearmovie.ui.home.HomeEvent
import com.itsluminous.clearmovie.ui.home.HomeScreen
import com.itsluminous.clearmovie.ui.home.HomeViewModel
import com.itsluminous.clearmovie.ui.pick.MediaPickRoute
import com.itsluminous.clearmovie.ui.preset.PresetReviewRoute
import com.itsluminous.clearmovie.ui.preset.PresetSetupRoute
import com.itsluminous.clearmovie.ui.settings.SettingsRoute

/**
 * The app's navigation graph (requirement 1-4): Home and preset pick, then
 * setup or repair when a file is not ready, review, the photo and video
 * step, and the editor. The back stack survives rotation and process death.
 *
 * [launchDecision] runs once for a fresh start (never after a rotation or a
 * restored process, whose screens come back on their own): when the app
 * stopped with a video open in the editor, the editor opens again at once.
 */
@Composable
fun ClearMovieApp(
    launchDecision: (suspend () -> LaunchDecision)? = null,
    navController: NavHostController = rememberNavController(),
) {
    var decided by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (decided) return@LaunchedEffect
        decided = true
        val decision = launchDecision?.invoke()
        if (decision is LaunchDecision.ReopenEditor) {
            navController.navigate(Routes.editor(decision.id, broughtBack = decision.broughtBack))
        }
    }

    fun go(step: FlowStep) {
        navController.navigate(Routes.of(step))
    }

    fun openEditor(
        id: ProjectId,
        skipped: Int = 0,
    ) {
        navController.navigate(Routes.editor(id, skipped)) {
            // The new-video screens are done with; Back from the editor goes Home.
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = hiltViewModel()
            val presets by vm.presets.collectAsStateWithLifecycle()
            val videos by vm.videos.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            val resources = LocalResources.current
            LifecycleResumeEffect(vm) {
                vm.refresh()
                onPauseOrDispose {}
            }
            LaunchedEffect(vm) {
                vm.events.collect {
                    when (it) {
                        is HomeEvent.Go -> go(it.step)
                        HomeEvent.NotSaved -> snackbar.showSnackbar(resources.getString(R.string.new_video_not_saved))
                    }
                }
            }
            HomeScreen(
                presets = presets,
                videos = videos,
                busy = busy,
                onPreset = { vm.startWithPreset(it) },
                onStartBlank = { vm.startBlank() },
                onVideo = { openEditor(it) },
                onOpenSettings = dropUnlessResumed { navController.navigate(Routes.SETTINGS) },
                snackbar = snackbar,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenPreset = { navController.navigate(Routes.setup(it, newVideo = false)) },
            )
        }
        composable(
            Routes.SETUP,
            arguments =
                listOf(
                    navArgument(Routes.ARG_PRESET) { type = NavType.StringType },
                    navArgument(Routes.ARG_NEW_VIDEO) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
        ) {
            PresetSetupRoute(
                onGo = { step ->
                    navController.navigate(Routes.of(step)) {
                        // Back from the review returns Home, not to the finished setup.
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.REVIEW, arguments = listOf(navArgument(Routes.ARG_PROJECT) { type = NavType.StringType })) {
            PresetReviewRoute(onGo = ::go, onBack = { navController.popBackStack() })
        }
        composable(Routes.PICK, arguments = listOf(navArgument(Routes.ARG_PROJECT) { type = NavType.StringType })) {
            MediaPickRoute(
                onAdded = { id, skipped -> openEditor(id, skipped) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.EDITOR,
            arguments =
                listOf(
                    navArgument(Routes.ARG_PROJECT) { type = NavType.StringType },
                    navArgument(Routes.ARG_SKIPPED) {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                    navArgument(Routes.ARG_BROUGHT_BACK) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
        ) {
            EditorRoute(
                onClosed = { if (!navController.popBackStack(Routes.HOME, inclusive = false)) navController.navigate(Routes.HOME) },
                onAddMedia = { navController.navigate(Routes.pick(it)) },
                onFixPreset = { navController.navigate(Routes.setup(it, newVideo = false)) },
            )
        }
    }
}
