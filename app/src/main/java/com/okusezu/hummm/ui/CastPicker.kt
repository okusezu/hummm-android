package com.okusezu.hummm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.okusezu.hummm.HummmApp
import com.okusezu.hummm.playback.CastStatus
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import com.okusezu.hummm.playback.Sonos
import kotlinx.coroutines.launch

/**
 * Chooses a Chromecast or Sonos group, drawn like a terminal pop-up. Selecting
 * a Chromecast starts a Cast session and selecting a Sonos group sets
 * [Sonos.target]; either way the playback service moves the queue over.
 * Phone speaker / Bluetooth outputs go through the system switcher.
 */
@Composable
fun CastPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cast = HummmApp.instance.castContext
    val casting by CastStatus.device.collectAsState()
    val router = remember { MediaRouter.getInstance(context) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }
    var routes by remember { mutableStateOf(emptyList<MediaRouter.RouteInfo>()) }
    DisposableEffect(cast) {
        if (cast == null) return@DisposableEffect onDispose {}
        fun refresh() { routes = router.routes.filter { it.isEnabled && !it.isDefaultOrBluetooth && it.matchesSelector(selector) } }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        // Active scanning only while the picker is open; it costs battery.
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        onDispose { router.removeCallback(callback) }
    }

    val sonosGroups by Sonos.groups.collectAsState()
    val sonosScanning by Sonos.scanning.collectAsState()
    val sonosTarget by Sonos.target.collectAsState()
    LaunchedEffect(Unit) { Sonos.scan(context) }
    var addingSonos by remember { mutableStateOf(false) }

    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Cast", fontWeight = FontWeight.Bold)
            Text("────", color = dim)
            Text("chromecast", color = dim)
            when {
                cast == null -> Text("Chromecast needs Google Play services, which this phone doesn't have.", color = dim)
                routes.isEmpty() -> {
                    Spinner(label = "looking for devices…")
                    Text("The Chromecast must be on the same Wi-Fi as this phone.", color = dim, style = MaterialTheme.typography.bodySmall)
                }
                else -> routes.forEach { route ->
                    Choice(route.name, route.isSelected) {
                        if (!route.isSelected) route.select()
                        onDismiss()
                    }
                }
            }
            Text("")
            Text("sonos", color = dim)
            sonosGroups.forEach { g ->
                Choice(g.name, sonosTarget?.id == g.id) {
                    if (sonosTarget?.id != g.id) Sonos.connect(g)
                    onDismiss()
                }
            }
            when {
                sonosScanning && sonosGroups.isEmpty() -> Spinner(label = "looking for speakers…")
                sonosGroups.isEmpty() -> Text("no speakers found on this Wi-Fi", color = dim, style = MaterialTheme.typography.bodySmall)
            }
            if (addingSonos) AddSonos(onDone = { addingSonos = false })
            else PickerAction("add a speaker by IP address") { addingSonos = true }
            Text("")
            if (casting != null) {
                PickerAction("stop casting") {
                    if (sonosTarget != null) Sonos.disconnect() else cast?.sessionManager?.endCurrentSession(true)
                    onDismiss()
                }
            }
            PickerAction("phone & bluetooth outputs") {
                SystemOutputSwitcherDialogController.showDialog(context)
                onDismiss()
            }
            PickerAction("close", onDismiss)
        }
    }
}

/** A device to play on; ▶︎ marks the one in use. */
@Composable
private fun Choice(name: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        (if (selected) "▶︎ " else "  ") + name + if (selected) "  (casting)" else "",
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (selected) FontWeight.Bold else null,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** For networks that hide speakers from searches: the IP is in the Sonos app under Settings → About My System. */
@Composable
private fun AddSonos(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(HummmApp.instance.settings.sonosHost.value.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    OutlinedTextField(
        address, { address = it; failed = false }, Modifier.fillMaxWidth(),
        label = { Text("speaker IP, e.g. 192.168.1.40") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    Text(
        "The Sonos app shows it, usually under Settings → System → About My System.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
    )
    if (failed) Text("No Sonos speaker answered at that address.", color = MaterialTheme.colorScheme.error)
    if (busy) Spinner(label = "trying…")
    else PickerAction("add") {
        busy = true
        scope.launch {
            val ok = Sonos.addByAddress(address)
            busy = false
            if (ok) onDone() else failed = true
        }
    }
}

@Composable
private fun PickerAction(label: String, onClick: () -> Unit) {
    Text(
        "[ $label ]",
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary,
    )
}
