@file:OptIn(
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.ryf5584.wxdbdump.ui

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.ryf5584.wxdbdump.data.ThemeChoice
import io.github.ryf5584.wxdbdump.di.AppContainer
import io.github.ryf5584.wxdbdump.ui.screen.AccountsScreen
import io.github.ryf5584.wxdbdump.ui.screen.CheckScreen
import io.github.ryf5584.wxdbdump.ui.screen.DatabasesScreen
import io.github.ryf5584.wxdbdump.ui.screen.ExportScreen
import io.github.ryf5584.wxdbdump.ui.screen.ModeScreen
import io.github.ryf5584.wxdbdump.ui.screen.SettingsScreen
import io.github.ryf5584.wxdbdump.ui.theme.Motion
import io.github.ryf5584.wxdbdump.ui.theme.WxDbExportTheme

object Routes {
    const val CHECK = "check"
    const val MODE = "mode"
    const val ACCOUNTS = "accounts"
    const val DATABASES = "databases"
    const val EXPORT = "export"
    const val SETTINGS = "settings"
}

@Composable
fun AppRoot(container: AppContainer) {
    val vm: AppViewModel = viewModel(factory = AppViewModel.factory(container))
    val settings by vm.settings.collectAsStateWithLifecycle()

    val dark = when (settings.theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }

    WxDbExportTheme(
        darkTheme = dark,
        dynamicColor = settings.dynamicColor,
        reduceMotion = settings.reduceMotion,
    ) {
        LaunchedEffect(Unit) {
            vm.detect()
        }

        // Root grants are dynamic, so the user may leave to grant and come back
        // Root is re-probed on every return to the foreground
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) vm.refreshRootIfNeeded()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SharedTransitionLayout {
                val nav = rememberNavController()
                AppNavHost(nav = nav, vm = vm, animatedVisibilityScope = this)
            }
        }
    }
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    vm: AppViewModel,
    animatedVisibilityScope: androidx.compose.animation.SharedTransitionScope,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.CHECK,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it / 12 },
                animationSpec = tween(Motion.MEDIUM3),
            ) + fadeIn(tween(Motion.MEDIUM3))
        },
        exitTransition = { fadeOut(tween(Motion.SHORT3)) },
        popEnterTransition = { fadeIn(tween(Motion.MEDIUM2)) },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it / 12 },
                animationSpec = tween(Motion.MEDIUM3),
            ) + fadeOut(tween(Motion.MEDIUM3))
        },
    ) {
        // A single entry point that checks both channels directly
        composable(Routes.CHECK) {
            CheckScreen(
                vm = vm,
                animatedVisibilityScope = animatedVisibilityScope,
                onReady = {
                    // One usable channel is chosen automatically; the picker only appears when both work
                    if (vm.autoSelectIfSingle()) nav.navigate(Routes.ACCOUNTS)
                    else nav.navigate(Routes.MODE)
                },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.MODE) {
            ModeScreen(
                vm = vm,
                onPicked = { nav.navigate(Routes.ACCOUNTS) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                vm = vm,
                animatedVisibilityScope = animatedVisibilityScope,
                onBack = { nav.popBackStack() },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAccount = { a ->
                    vm.openAccount(a)
                    nav.navigate(Routes.DATABASES)
                },
            )
        }
        composable(Routes.DATABASES) {
            DatabasesScreen(
                vm = vm,
                animatedVisibilityScope = animatedVisibilityScope,
                onBack = { nav.popBackStack() },
                onExport = {
                    vm.startExport()
                    // Without storage permission startExport blocks and asks instead of opening the screen
                    if (!vm.exportPermissionBlocked) nav.navigate(Routes.EXPORT)
                },
            )
        }
        composable(Routes.EXPORT) {
            ExportScreen(
                vm = vm,
                onDone = {
                    vm.resetExport()
                    nav.popBackStack(Routes.ACCOUNTS, inclusive = false)
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
