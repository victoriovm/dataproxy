package com.dataproxy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Toggles airplane mode to force the carrier to hand out a fresh IP.
 * Actuation is Shizuku-only: `cmd connectivity airplane-mode` through the
 * system service, the same path as the quick-settings tile. Raw
 * Settings.Global writes were proven ignored on this device family
 * (setting reads 1, modem stays IN_SERVICE), so no fallback exists.
 *
 * No fixed sleeps: after turning airplane mode on we wait for the modem
 * to confirm the radio is really off, then turn it back off immediately.
 * Timeouts keep both waits from hanging forever.
 */
class AirplaneModeController(private val context: Context) {

    sealed interface Result {
        data object Ok : Result
        data class Failed(val reason: String) : Result
    }

    companion object {
        private const val TAG = "AirplaneMode"

        /** Cap on waiting for the modem to confirm radio off. */
        const val RADIO_OFF_TIMEOUT_MS = 25_000L

        /** Cap on waiting for mobile data to come back after airplane off. */
        const val MOBILE_BACK_TIMEOUT_MS = 30_000L

        /** Backup poll cadence while waiting on telephony state. */
        private const val POLL_MS = 250L

        /** Settle time after the re-kick OFF before turning back ON. */
        private const val REKICK_SETTLE_MS = 2_000L

        /**
         * Breathing room between the end of one cycle and the start of the
         * next. RenewWebServer serializes renews with a mutex, so this
         * delay simply spaces them out.
         */
        private const val CYCLE_COOLDOWN_MS = 3_000L

        /** Needs no telephony permission at all, plain connectivity read. */
        private const val MOBILE_NET_TIMEOUT_MS = 45_000L
    }

    private val appContext = context.applicationContext

    /**
     * Airplane ON, wait for the radio to confirm off, airplane OFF, wait
     * for mobile data to come back. Serialized by the caller
     * (RenewWebServer holds a mutex), so back to back GET /renew calls
     * queue instead of interleaving toggles.
     */
    suspend fun renew(): Result {
        if (ShizukuAirplane.status(appContext) != ShizukuAirplane.Status.Ready) {
            Log.w(TAG, "shizuku not ready, refusing renew")
            return Result.Failed("authorize Shizuku first")
        }
        return try {
            if (isAirplaneSettingOn()) {
                Log.i(TAG, "airplane already ON at entry, clearing first")
                setAirplane(false)
                delay(REKICK_SETTLE_MS)
            }
            setAirplane(true)
            var off = withTimeoutOrNull(RADIO_OFF_TIMEOUT_MS / 2) { waitForRadioOff() }
                ?: false
            if (!off) {
                Log.w(TAG, "no radio confirmation in ${RADIO_OFF_TIMEOUT_MS / 2}ms, re-kicking airplane")
                setAirplane(false)
                delay(REKICK_SETTLE_MS)
                setAirplane(true)
                off = withTimeoutOrNull(RADIO_OFF_TIMEOUT_MS / 2) { waitForRadioOff() }
                    ?: false
            }
            if (!off) {
                Log.w(TAG, "radio-off not confirmed in ${RADIO_OFF_TIMEOUT_MS}ms, restoring airplane OFF")
                setAirplane(false)
                delay(CYCLE_COOLDOWN_MS)
                return Result.Failed("radio did not power off")
            }
            setAirplane(false)
            val back = withTimeoutOrNull(MOBILE_BACK_TIMEOUT_MS) { waitForMobileConnected() }
                ?: false
            if (!back) {
                Log.w(TAG, "mobile data did not come back in ${MOBILE_BACK_TIMEOUT_MS}ms")
                delay(CYCLE_COOLDOWN_MS)
                return Result.Failed("mobile data did not reconnect")
            }
            Log.i(TAG, "renew cycle done")
            delay(CYCLE_COOLDOWN_MS)
            Result.Ok
        } catch (e: Exception) {
            Log.w(TAG, "renew failed: ${e.message}")
            Result.Failed(e.message ?: "unknown error")
        }
    }

    /**
     * Suspends until the modem reports the radio is off. Tries the
     * TelephonyCallback first (event-driven); a poll backs it up every
     * [POLL_MS] in case the callback never fires on this modem.
     */
    private suspend fun waitForRadioOff(): Boolean {
        if (isRadioOff()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return pollUntil(timeoutMs = RADIO_OFF_TIMEOUT_MS) { isRadioOff() }
        }
        val base = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val managers = subscriptionManagers(base)
        return suspendCancellableCoroutine { cont ->
            val executor = Executor { it.run() }
            val box = TelephonyBox()
            val settle: (Boolean) -> Unit = { value ->
                if (!box.settled) {
                    box.settled = true
                    box.cbs.forEach { (m, cb) -> runCatching { m.unregisterTelephonyCallback(cb) } }
                    box.poller?.interrupt()
                    if (cont.isActive) cont.resume(value)
                }
            }
            cont.invokeOnCancellation {
                box.settled = true
                box.cbs.forEach { (m, cb) -> runCatching { m.unregisterTelephonyCallback(cb) } }
                box.poller?.interrupt()
            }
            var registered = 0
            for (m in managers) {
                val cb = object : TelephonyCallback(),
                    TelephonyCallback.ServiceStateListener,
                    TelephonyCallback.DataConnectionStateListener {
                    override fun onServiceStateChanged(serviceState: ServiceState) {
                        Log.i(TAG, "service state: ${stateName(serviceState.state)}")
                        if (serviceState.state == ServiceState.STATE_POWER_OFF ||
                            (serviceState.state == ServiceState.STATE_OUT_OF_SERVICE && isRadioOff())
                        ) settle(true)
                    }

                    override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                        Log.i(TAG, "data connection: ${dataName(state)}")
                        if (state == TelephonyManager.DATA_DISCONNECTED && isRadioOff()) settle(true)
                    }
                }
                try {
                    m.registerTelephonyCallback(executor, cb)
                    box.cbs += m to cb
                    registered++
                } catch (e: SecurityException) {
                    Log.w(TAG, "telephony callback denied, polling instead")
                }
            }
            if (registered == 0) {
                var ok = false
                val deadline = android.os.SystemClock.elapsedRealtime() + RADIO_OFF_TIMEOUT_MS
                while (cont.isActive && android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (isRadioOff()) { ok = true; break }
                    Thread.sleep(POLL_MS)
                }
                settle(ok)
                return@suspendCancellableCoroutine
            }
            box.poller = Thread {
                val deadline = android.os.SystemClock.elapsedRealtime() + RADIO_OFF_TIMEOUT_MS
                while (!box.settled && android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (isRadioOff()) { settle(true); return@Thread }
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
                settle(false)
            }.also { it.isDaemon = true; it.start() }
        }
    }

    /**
     * Suspends until mobile data is usable again after airplane mode is
     * turned off. Signal is a validated cellular network from
     * ConnectivityManager (needs only ACCESS_NETWORK_STATE, always
     * granted), watched via callback with a bounded poll as backup.
     */
    private suspend fun waitForMobileConnected(): Boolean {
        if (isMobileUsable()) return true
        return suspendCancellableCoroutine { cont ->
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val box = CallbackBox()
            val settle: (Boolean) -> Unit = { value ->
                if (!box.settled) {
                    box.settled = true
                    box.netCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                    box.poller?.interrupt()
                    if (cont.isActive) cont.resume(value)
                }
            }
            box.netCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isMobileUsable()) settle(true)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    if (isMobileUsable()) settle(true)
                }
            }
            cont.invokeOnCancellation {
                box.settled = true
                box.netCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                box.poller?.interrupt()
            }
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { cm.registerNetworkCallback(req, box.netCb!!) }
            box.poller = Thread {
                val deadline = android.os.SystemClock.elapsedRealtime() + MOBILE_NET_TIMEOUT_MS
                while (!box.settled && android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (isMobileUsable()) { settle(true); return@Thread }
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
                settle(false)
            }.also { it.isDaemon = true; it.start() }
        }
    }

    private class CallbackBox {
        var settled: Boolean = false
        var netCb: ConnectivityManager.NetworkCallback? = null
        var poller: Thread? = null
    }

    private class TelephonyBox {
        var settled: Boolean = false
        val cbs = mutableListOf<Pair<TelephonyManager, TelephonyCallback>>()
        var poller: Thread? = null
    }

    private fun subscriptionManagers(base: TelephonyManager): List<TelephonyManager> {
        val ids = runCatching {
            val sm = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as SubscriptionManager
            sm.activeSubscriptionInfoList?.map { it.subscriptionId }.orEmpty()
        }.getOrNull().orEmpty()
        if (ids.isEmpty()) return listOf(base)
        return ids.map { runCatching { base.createForSubscriptionId(it) }.getOrNull() ?: base }
    }

    private fun isRadioOff(): Boolean {
        if (!isAirplaneSettingOn()) return false
        return runCatching {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val ids = runCatching {
                val sm = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as SubscriptionManager
                sm.activeSubscriptionInfoList?.map { it.subscriptionId }.orEmpty()
            }.getOrNull().orEmpty()
            val managers = if (ids.isEmpty()) listOf(tm)
            else ids.map { runCatching { tm.createForSubscriptionId(it) }.getOrNull() ?: tm }
            managers.any { m ->
                when (m.serviceState?.state) {
                    ServiceState.STATE_POWER_OFF,
                    ServiceState.STATE_OUT_OF_SERVICE -> true
                    else -> false
                }
            }
        }.getOrNull() == true
    }

    private fun isAirplaneSettingOn(): Boolean {
        return runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON) == 1
        }.getOrNull() == true
    }

    /**
     * True when a cellular network is validated and carrying data. Read off
     * ConnectivityManager (ACCESS_NETWORK_STATE, install-time), so it needs
     * no phone permission at all.
     */
    private fun isMobileUsable(): Boolean {
        return runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nets = cm.allNetworks
            nets.any { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }.getOrNull() == true
    }

    /** Bounded poll of [test], true on first success, false on timeout. */
    private suspend fun pollUntil(timeoutMs: Long, test: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { test() }.getOrNull() == true) return true
            delay(POLL_MS)
        }
        return runCatching { test() }.getOrNull() == true
    }

    private suspend fun setAirplane(on: Boolean) {
        if (!ShizukuAirplane.setAirplane(on)) {
            Log.w(TAG, "shizuku actuation failed")
        }
    }

    private fun stateName(state: Int): String = when (state) {
        ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
        ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
        ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
        ServiceState.STATE_POWER_OFF -> "POWER_OFF"
        else -> "UNKNOWN($state)"
    }

    private fun dataName(state: Int): String = when (state) {
        TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
        TelephonyManager.DATA_CONNECTING -> "CONNECTING"
        TelephonyManager.DATA_CONNECTED -> "CONNECTED"
        TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
        else -> "UNKNOWN($state)"
    }
}
