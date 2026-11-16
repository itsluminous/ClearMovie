package com.itsluminous.clearmovie.ui.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.itsluminous.clearmovie.ui.share.ShareDialogs
import com.itsluminous.clearmovie.ui.share.ShareNav
import com.itsluminous.clearmovie.ui.share.ShareRoute
import com.itsluminous.clearmovie.ui.share.ShareViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's navigation graph (requirement 1-4): Home and preset pick, then
 * setup or repair when a file is not ready, review, the photo and video
 * step, and the editor. The back stack survives rotation and process death.
 *
 * [launchDecision] runs once for a fresh start (never after a rotation or a
 * restored process, whose screens come back on their own): when the app
 * stopped with a video open in the editor, the editor opens again at once
 * ([ReopenAtLaunch]).
 *
 * [share] is the activity's share flow (cm-share): a share goes to its own
 * preset pick and then straight to the editor, skipping the review and the
 * picker; its questions and messages show over whatever screen is open.
 */
@Composable
fun ClearMovieApp(
    launchDecision: (suspend () -> LaunchDecision)? = null,
    navController: NavHostController = rememberNavController(),
    share: ShareViewModel? = null,
) {
    ReopenAtLaunch(launchDecision) {
        navController.navigate(Routes.editor(it.id, broughtBack = it.broughtBack)) {
            // As openEditor: whatever was tapped while the decision was read is
            // dropped, so Back from the reopened editor goes Home.
            popUpTo(Routes.HOME)
            launchSingleTop = true
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

    /** Home, once: the share can ask for it from two places (its own screen and its flow), and Home already showing stays as it is. */
    fun goHome() {
        if (navController.currentDestination?.route == Routes.HOME) return
        if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
            navController.navigate(Routes.HOME) { launchSingleTop = true }
        }
    }

    fun onShare(nav: ShareNav) {
        when (nav) {
            ShareNav.ToPick -> {
                navController.navigate(Routes.SHARE) {
                    popUpTo(Routes.HOME)
                    launchSingleTop = true
                }
            }

            is ShareNav.ToEditor -> {
                val top = navController.currentBackStackEntry
                val showing =
                    top?.destination?.route == Routes.EDITOR && top.arguments?.getString(Routes.ARG_PROJECT) == nav.projectId.value
                // Already on screen: no navigation happens, so tell the share directly (it would wait for one).
                if (showing) share?.screenShown(nav.projectId) else openEditor(nav.projectId)
            }

            is ShareNav.ToSetup -> {
                navController.navigate(Routes.setup(nav.presetId, newVideo = true, shareDraft = nav.draft)) { launchSingleTop = true }
            }

            ShareNav.Home -> {
                goHome()
            }

            ShareNav.AskForAccess -> {
                Unit
            }
        }
    }

    if (share != null) {
        // The share knows which video the editor shows (a share whose video it is, is then done).
        LaunchedEffect(navController, share) {
            navController.currentBackStackEntryFlow.collect { entry ->
                val editing =
                    entry.arguments
                        ?.takeIf { entry.destination.route == Routes.EDITOR }
                        ?.getString(Routes.ARG_PROJECT)
                        ?.let { id -> runCatching { ProjectId(id) }.getOrNull() }
                share.screenShown(editing)
            }
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
                    navArgument(Routes.ARG_SHARE_DRAFT) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
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
        composable(Routes.SHARE) {
            if (share != null) ShareRoute(share, onIdle = ::goHome) else LaunchedEffect(Unit) { goHome() }
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
                onFixPreset = { navController.navigate(Routes.setup(it, newVideo = false)) },
            )
        }
    }
    if (share != null) ShareDialogs(share, onNav = ::onShare)
}

/**
 * Runs [launchDecision] once for a fresh start and hands a reopen to
 * [reopen] (storage D2: the app stopped with a video open in the editor).
 *
 * [reopen] navigates, and a NavController may only be used on the main
 * thread. The decision reads the database off the main thread, and this
 * effect resumes on whatever its coroutine context dispatches to: the main
 * thread in the app, but the Compose test rule's effect dispatcher resumes
 * on the thread that finished the read. So the decision is acted on with an
 * explicit switch to the main thread. A navigate on another thread throws
 * half way, leaving the editor's back stack entry INITIALIZED, and
 * destroying the activity then crashes.
 *
 * It counts as decided only once acted on, in the same main-thread step, so
 * an activity destroyed while the decision is still being read (a rotation
 * at launch) decides again, and one restored after acting (a rotation, or a
 * process death, whose back stack comes back on its own) never reopens twice.
 */
@Composable
internal fun ReopenAtLaunch(
    launchDecision: (suspend () -> LaunchDecision)?,
    reopen: (LaunchDecision.ReopenEditor) -> Unit,
) {
    var decided by rememberSaveable { mutableStateOf(false) }
    val currentReopen by rememberUpdatedState(reopen)
    LaunchedEffect(Unit) {
        if (decided) return@LaunchedEffect
        val decision = launchDecision?.invoke()
        withContext(Dispatchers.Main.immediate) {
            if (decision is LaunchDecision.ReopenEditor) currentReopen(decision)
            decided = true
        }
    }
}
