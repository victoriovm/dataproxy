package com.dataproxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.network.CellularNetworkProvider
import com.dataproxy.network.CellularTechMonitor
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.components.PowerButton
import com.dataproxy.ui.components.PowerState
import com.dataproxy.ui.components.SpeedometerCard
import com.dataproxy.ui.components.StatusDot
import com.dataproxy.ui.components.TrafficStatsCard
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Danger
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.OutlineStrong
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.Warning
import com.dataproxy.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onToggle: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenAntiKill: () -> Unit,
    onOpenRenew: () -> Unit,
    onHeaderClick: () -> Unit,
) {
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val rateUnit by viewModel.rateUnit.collectAsStateWithLifecycle()
    val cellular by viewModel.cellular.collectAsStateWithLifecycle()
    val cellularTech by viewModel.cellularTech.collectAsStateWithLifecycle()
    val bindAddress by viewModel.bindAddress.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val authEnabled by viewModel.authEnabled.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val activeDeviceCount = devices.count { it.activeConnections > 0 }
    val webEnabled by viewModel.webEnabled.collectAsStateWithLifecycle()
    val webPort by viewModel.webPort.collectAsStateWithLifecycle()
    val webState by viewModel.webState.collectAsStateWithLifecycle()

    val powerState = when (serviceState) {
        is ProxyService.State.Running -> PowerState.On
        is ProxyService.State.Starting -> PowerState.Starting
        is ProxyService.State.Paused -> PowerState.Paused
        is ProxyService.State.Error -> PowerState.Error
        else -> PowerState.Off
    }
    val statusLabel = when (val s = serviceState) {
        is ProxyService.State.Running -> "Online · ${s.bindAddress}:${s.port}"
        is ProxyService.State.Starting -> "Starting on ${s.bindAddress}:${s.port}"
        is ProxyService.State.Paused -> "Paused · ${s.bindAddress}:${s.port}"
        is ProxyService.State.Error -> s.message.take(60)
        else -> "Proxy offline"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        Header(
            cellular = cellular,
            tech = cellularTech,
            onNetworkClick = onHeaderClick,
            onOpenAntiKill = onOpenAntiKill,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PowerButton(
                state = powerState,
                statusLabel = statusLabel,
                onClick = onToggle,
            )
        }
        Spacer(Modifier.height(10.dp))
        SpeedometerCard(
            upBps = rates.upBps,
            downBps = rates.downBps,
            rateUnit = rateUnit,
            onCycleRateUnit = { viewModel.cycleRateUnit() },
        )
        Spacer(Modifier.height(8.dp))
        TrafficStatsCard(
            bytesUp = totals.bytesUp,
            bytesDown = totals.bytesDown,
            activeConnections = totals.active,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavTile(
                icon = Icons.Rounded.Router,
                title = "Listen",
                subtitle = "$bindAddress:$port",
                onClick = onOpenListen,
                modifier = Modifier.weight(1f),
            )
            NavTile(
                icon = Icons.Rounded.Devices,
                title = "Devices",
                subtitle = when {
                    devices.isEmpty() -> "none"
                    activeDeviceCount == 0 -> "${devices.size} seen"
                    activeDeviceCount == devices.size -> "$activeDeviceCount online"
                    else -> "$activeDeviceCount / ${devices.size} online"
                },
                onClick = onOpenDevices,
                modifier = Modifier.weight(1f),
            )
            NavTile(
                icon = Icons.Rounded.Lock,
                title = "Auth",
                subtitle = if (authEnabled) "required" else "disabled",
                onClick = onOpenAuth,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        RenewCard(
            webEnabled = webEnabled,
            webPort = webPort,
            webState = webState,
            canEdit = when (serviceState) {
                is ProxyService.State.Stopped, is ProxyService.State.Error -> true
                else -> false
            },
            onToggle = viewModel::setWebEnabled,
            onOpenRenew = onOpenRenew,
        )
        Spacer(Modifier.weight(1f))
        Footer()
    }
}

@Composable
private fun Header(
    cellular: CellularNetworkProvider.State,
    tech: CellularTechMonitor.TechState,
    onNetworkClick: () -> Unit,
    onOpenAntiKill: () -> Unit,
) {
    val isOff = tech is CellularTechMonitor.TechState.DataOff
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Bolt badge shares the title block's vertical center: with the theme
        // button gone there is no second row to balance against, so align the
        // icon with the two-line title instead of floating it above.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Accent, Color(0xFF1E9C70)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "DataProxy",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
            )
            // Subtitle becomes the off-banner when mobile data is off, so the
            // long phrase has space to breathe instead of fighting the corner.
            Text(
                text = if (isOff) "Mobile data is OFF" else "Socks5 Over Cellular",
                color = if (isOff) Danger else TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // Header indicator is purely device-cellular state. Tech needs
        // READ_BASIC_PHONE_STATE (auto-granted normal perm, API 33+) or
        // READ_PHONE_STATE (runtime, pre-33). Tapping the indicator re-opens
        // the perms dialog so the user can grant it after the fact.
        val dotColor = when (tech) {
            is CellularTechMonitor.TechState.Tech -> Accent
            is CellularTechMonitor.TechState.OperatorOnly -> Accent
            CellularTechMonitor.TechState.DataOff -> Danger
            CellularTechMonitor.TechState.Unknown -> when (cellular) {
                is CellularNetworkProvider.State.Available -> Accent
                CellularNetworkProvider.State.Requesting -> Warning
                else -> TextMuted
            }
        }
        val label = when (tech) {
            is CellularTechMonitor.TechState.Tech ->
                if (tech.operator.isBlank()) tech.label
                else "${tech.operator} · ${tech.label}"
            is CellularTechMonitor.TechState.OperatorOnly -> tech.operator
            CellularTechMonitor.TechState.DataOff -> "OFF"
            CellularTechMonitor.TechState.Unknown -> when (cellular) {
                is CellularNetworkProvider.State.Available -> "MOBILE"
                CellularNetworkProvider.State.Requesting -> "WAIT"
                else -> "-"
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onNetworkClick)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                StatusDot(color = dotColor)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    color = if (isOff) Danger else TextSecondary,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (label.length <= 6) 13.sp else 11.sp,
                    ),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            AntiKillChip(onClick = onOpenAntiKill)
        }
    }
}

@Composable
private fun AntiKillChip(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Shield,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Anti-Kill",
            color = Accent,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun RenewCard(
    webEnabled: Boolean,
    webPort: Int,
    webState: ProxyService.WebState,
    canEdit: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenRenew: () -> Unit,
) {
    // Same tile look as the Listen/Devices/Auth row, full width. The switch
    // arms the server for the next proxy start (the power button runs proxy
    // plus renew together when the switch is on), and it locks while the
    // proxy is live so the toggle can never desync from a running server.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenRenew)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Renew server",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when (val ws = webState) {
                        is ProxyService.WebState.Running -> "GET /renew on :${ws.port}"
                        else -> if (webEnabled) "On, starts with proxy :$webPort"
                        else "Rotate carrier IP via GET /renew"
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            Switch(
                checked = webEnabled,
                // The switch consumes the tap itself so the row click does
                // not also fire. Disabled while live, same rule as Listen.
                onCheckedChange = { if (canEdit) onToggle(it) },
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
    }
}

@Composable
private fun NavTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = subtitle,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun Footer() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "DataProxy",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(TextMuted),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "v1.3",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "By Sir.MmD",
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
