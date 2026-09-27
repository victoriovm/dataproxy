package com.dataproxy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.network.ShizukuAirplane
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.OutlineStrong
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.viewmodel.MainViewModel

/**
 * Dedicated Renew server page. Owns the enable toggle and the web port.
 * Execution is tied to the proxy: the server starts and stops with the
 * Home power button. While the proxy is live every control is disabled,
 * same rule as the Listen screen.
 *
 * Actuation is Shizuku-only. The status below tells whether Shizuku is
 * installed, running and authorized; the toggle needs all three.
 */
@Composable
fun RenewScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val bindAddress by viewModel.bindAddress.collectAsStateWithLifecycle()
    val webEnabled by viewModel.webEnabled.collectAsStateWithLifecycle()
    val webPort by viewModel.webPort.collectAsStateWithLifecycle()
    val webState by viewModel.webState.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()

    val canEdit = when (serviceState) {
        is ProxyService.State.Stopped, is ProxyService.State.Error -> true
        else -> false
    }

    val context = LocalContext.current
    var shizukuStatus by remember { mutableStateOf(ShizukuAirplane.Status.Unavailable) }
    LaunchedEffect(Unit) {
        while (true) {
            shizukuStatus = runCatching { ShizukuAirplane.status(context) }
                .getOrDefault(ShizukuAirplane.Status.Unavailable)
            kotlinx.coroutines.delay(1500)
        }
    }
    val ready = shizukuStatus == ShizukuAirplane.Status.Ready

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        TopBar(title = "Renew server", onBack = onBack)
        Spacer(Modifier.height(8.dp))
        if (!canEdit) {
            HintBanner("Stop the proxy to change the renew server settings.")
            Spacer(Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceMid)
                    .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable renew server",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when (val ws = webState) {
                            is ProxyService.WebState.Running ->
                                "Running, GET /renew on $bindAddress:${ws.port}"
                            else -> if (webEnabled) "Starts with the proxy" else "Off"
                        },
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = webEnabled,
                    onCheckedChange = { if (canEdit) viewModel.setWebEnabled(it) },
                    enabled = canEdit,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SurfaceLow,
                        checkedTrackColor = Accent,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SurfaceLow,
                        uncheckedBorderColor = OutlineStrong,
                    ),
                )
            }

            // Same bind address as the SOCKS listener, own port. The address
            // itself is picked under Listen, only the port lives here.
            PortField(
                label = "Web port",
                port = webPort,
                enabled = canEdit,
                onChange = viewModel::selectWebPort,
            )

            HintBanner(
                message = "GET /renew turns airplane mode on, waits for the " +
                    "radio to switch off, turns it back off right away, waits " +
                    "for mobile data to reconnect, then answers 200. The " +
                    "server starts and stops with the proxy power button on " +
                    "Home. Example: curl http://<phone-ip>:$webPort/renew",
            )

            HintBanner(
                alert = !ready,
                message = when (shizukuStatus) {
                    ShizukuAirplane.Status.Ready ->
                        "Shizuku connected, airplane toggles go through the system."
                    ShizukuAirplane.Status.Unauthorized ->
                        "Shizuku found but not authorized. Open the Shizuku app, " +
                            "start it via wireless debugging, then tap Authorize " +
                            "when it prompts for DataProxy."
                    ShizukuAirplane.Status.Unavailable ->
                        "Shizuku not running. Install it (RikkaApps/Shizuku), " +
                            "start it via wireless debugging, then authorize " +
                            "DataProxy."
                },
            )
            if (shizukuStatus == ShizukuAirplane.Status.Unauthorized) {
                androidx.compose.material3.Button(
                    onClick = { runCatching { ShizukuAirplane.requestPermission() } },
                    enabled = canEdit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Authorize via Shizuku")
                }
            }
            if (!ready) {
                Text(
                    text = "Renew needs Shizuku authorized to toggle airplane mode.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
