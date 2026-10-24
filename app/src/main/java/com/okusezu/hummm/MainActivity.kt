package com.okusezu.hummm

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.okusezu.hummm.playback.CastStatus
import com.okusezu.hummm.playback.CastVolume
import com.okusezu.hummm.playback.PlayerErrors
import com.okusezu.hummm.ui.AppViewModel
import com.okusezu.hummm.ui.DetailScreen
import com.okusezu.hummm.ui.EqualizerScreen
import com.okusezu.hummm.ui.HomeScreen
import com.okusezu.hummm.ui.HummmTheme
import com.okusezu.hummm.ui.LibraryListScreen
import com.okusezu.hummm.ui.LibraryScreen
import com.okusezu.hummm.ui.LoginScreen
import com.okusezu.hummm.ui.MiniPlayer
import com.okusezu.hummm.ui.PlayerSheet
import com.okusezu.hummm.ui.Route
import com.okusezu.hummm.ui.SearchScreen
import com.okusezu.hummm.ui.SettingsScreen
import com.okusezu.hummm.ui.Tab
import com.okusezu.hummm.ui.TermTab
import com.okusezu.hummm.ui.VibeScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The playback notification (with its controls) needs this on Android 13+.
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { HummmTheme { App(vm) } }
    }

    // While casting, the volume keys set the Chromecast's volume. The media
    // session already routes them there; this covers Android versions that don't.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (CastStatus.device.value != null) when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { CastVolume.step(1); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { CastVolume.step(-1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@androidx.compose.runtime.Composable
private fun App(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val playerError by PlayerErrors.last.collectAsState()
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    LaunchedEffect(playerError) { playerError?.let { PlayerErrors.consume(); snackbar.showSnackbar(it) } }

    BackHandler(enabled = vm.playerOpen || vm.stack.isNotEmpty() || vm.tab != Tab.HOME) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    MiniPlayer(vm)
                    // The desktop app's mode bar: a rule, then the tabs as text.
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        listOf(Tab.HOME to "home", Tab.SEARCH to "search", Tab.VIBE to "vibe", Tab.LIBRARY to "library").forEach { (tab, label) ->
                            TermTab(label, selected = vm.tab == tab && vm.stack.isEmpty(), onClick = { vm.selectTab(tab) })
                        }
                    }
                }
            },
        ) { inner ->
            // Top bars handle the status bar themselves; only keep the bottom inset.
            val padding = PaddingValues(bottom = 8.dp)
            Box(Modifier.padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding())) {
                when (val r = vm.stack.lastOrNull()) {
                    is Route.Detail -> DetailScreen(vm, r.item, padding)
                    is Route.LibraryList -> LibraryListScreen(vm, r.kind, padding)
                    Route.Settings -> SettingsScreen(vm, padding)
                    Route.Login -> LoginScreen(vm)
                    Route.Equalizer -> EqualizerScreen(vm, padding)
                    null -> when (vm.tab) {
                        Tab.HOME -> HomeScreen(vm, padding)
                        Tab.SEARCH -> SearchScreen(vm, padding)
                        Tab.VIBE -> VibeScreen(vm, padding)
                        Tab.LIBRARY -> LibraryScreen(vm, padding)
                    }
                }
            }
        }
        AnimatedVisibility(vm.playerOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            PlayerSheet(vm)
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
