package com.dataproxy.network

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shell-privileged airplane toggle via the Shizuku binder.
 *
 * Why this exists: on Samsung OneUI a raw
 * `Settings.Global.putInt(AIRPLANE_MODE_ON, ...)` write sticks in the
 * settings database but never drives the radio. The toggle in the quick
 * settings panel works because it goes through ConnectivityService. That
 * same path is reachable from an adb shell via
 * `cmd connectivity airplane-mode [enable|disable]`, and Shizuku hands our
 * app a shell-privileged process to run exactly that, with no root.
 *
 * All calls into rikka.shizuku go through reflection, so the app runs fine
 * when Shizuku is not installed or not authorized: every entry point
 * degrades to false / [Status.Unavailable] instead of throwing
 * NoClassDefFoundError. The compile dependency (`dev.rikka.shizuku:api`)
 * only matters when the manager package is present at runtime.
 *
 * Authorization status is polled (the Renew screen re-checks every 1.5 s),
 * so no Shizuku listeners are registered anywhere.
 *
 * Pairing (one time, no PC needed):
 * 1. Install the Shizuku app (github.com/RikkaApps/Shizuku).
 * 2. Start it via wireless debugging (on-device, no cable).
 * 3. Open DataProxy's Renew screen, tap "Authorize via Shizuku", approve.
 */
object ShizukuAirplane {
    private const val TAG = "ShizukuAirplane"
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST_CODE = 1001

    enum class Status { Ready, Unauthorized, Unavailable }

    fun status(context: Context): Status {
        if (!isManagerInstalled(context)) {
            Log.w(TAG, "manager package missing")
            return Status.Unavailable
        }
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val ping = cls.getDeclaredMethod("pingBinder").invoke(null) as? Boolean
            Log.i(TAG, "pingBinder=$ping")
            if (ping != true) {
                Log.w(TAG, "binder not running, start Shizuku via wireless debugging")
                return Status.Unavailable
            }
            val perm = (cls.getDeclaredMethod("checkSelfPermission").invoke(null) as? Number)?.toInt()
            Log.i(TAG, "checkSelfPermission=$perm")
            if (perm == PackageManager.PERMISSION_GRANTED) Status.Ready
            else Status.Unauthorized
        } catch (e: Exception) {
            Log.w(TAG, "status check failed: ${e::class.java.simpleName}: ${e.message}")
            Status.Unavailable
        }
    }

    /** Fires Shizuku's system permission prompt. No-op when unavailable. */
    fun requestPermission() {
        try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            cls.getDeclaredMethod("requestPermission", Int::class.java)
                .invoke(null, PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.w(TAG, "requestPermission failed: ${e.message}")
        }
    }

    /**
     * Runs `cmd connectivity airplane-mode [enable|disable]` with shell
     * privileges through Shizuku's remote process. Returns true when the
     * service accepted the command (exit 0). Suspends on Dispatchers.IO,
     * never on the caller thread.
     */
    suspend fun setAirplane(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            // newProcess is private in v13 (only the provider calls it),
            // so reach it reflectively. Signature:
            // private static ShizukuRemoteProcess newProcess(String[], String[], String).
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val method = cls.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val raw = method.invoke(
                null,
                arrayOf("cmd", "connectivity", "airplane-mode", if (on) "enable" else "disable"),
                null,
                null,
            ) as? Process ?: return@withContext false
            val exit = raw.waitFor()
            // Drain so a chatty service cannot block on a full pipe.
            runCatching { raw.inputStream?.close() }
            runCatching { raw.errorStream?.close() }
            runCatching { raw.destroy() }
            if (exit != 0) Log.w(TAG, "cmd connectivity exited $exit")
            exit == 0
        } catch (e: Exception) {
            Log.w(TAG, "shizuku airplane failed: ${e::class.java.simpleName}: ${e.message}")
            false
        }
    }

    private fun isManagerInstalled(context: Context): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }
}
