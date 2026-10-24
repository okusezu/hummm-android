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

/**
 * Chooses a Chromecast, drawn like a terminal pop-up. Selecting a route
 * starts a Cast session; the playback service notices and moves the queue
 * over. Phone speaker / Bluetooth outputs go through the system switcher.
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

    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Cast", fontWeight = FontWeight.Bold)
            Text("────", color = dim)
            when {
                cast == null -> Text("Chromecast needs Google Play services, which this phone doesn't have.", color = dim)
                routes.isEmpty() -> {
                    Spinner(label = "looking for devices…")
                    Text("The Chromecast must be on the same Wi-Fi as this phone.", color = dim, style = MaterialTheme.typography.bodySmall)
                }
                else -> routes.forEach { route ->
                    val selected = route.isSelected
                    Text(
                        (if (selected) "▶︎ " else "  ") + route.name + if (selected) "  (casting)" else "",
                        Modifier.fillMaxWidth().clickable {
                            if (!selected) route.select()
                            onDismiss()
                        }.padding(vertical = 8.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected) FontWeight.Bold else null,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text("")
            if (casting != null && cast != null) {
                PickerAction("stop casting") { cast.sessionManager.endCurrentSession(true); onDismiss() }
            }
            PickerAction("phone & bluetooth outputs") {
                SystemOutputSwitcherDialogController.showDialog(context)
                onDismiss()
            }
            PickerAction("close", onDismiss)
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
