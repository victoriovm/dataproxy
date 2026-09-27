package com.dataproxy.proxy

import android.util.Log
import com.dataproxy.network.AirplaneModeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Tiny HTTP server with a single route: GET /renew.
 *
 * The route flips airplane mode on, waits for the modem to confirm the
 * radio is off, flips airplane mode back off immediately, waits for mobile
 * data to reconnect, then answers 200. The cellular drop/reconnect in
 * between forces the carrier to hand out a fresh IP, which is the whole
 * point: a script on the LAN can rotate the egress IP with one curl.
 *
 * Deliberately hand-rolled on ServerSocket instead of an HTTP library: the
 * app has no OkHttp/Ktor dependency (see CLAUDE.md data-path invariant) and
 * pulling one in for a single route would bloat the APK. Only GET /renew is
 * served, everything else gets 404.
 *
 * Lifecycle mirrors Socks5Server: one [start]/[stop] cycle, bound to the
 * same bind address the SOCKS listener uses (the Listen screen picker), on
 * its own port so the two never fight over a socket.
 */
class RenewWebServer(
    val bindAddress: String,
    val port: Int,
    private val airplane: AirplaneModeController,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes renew cycles so concurrent GETs queue instead of toggling over each other. */
    private val renewMutex = Mutex()

    @Volatile var running: Boolean = false
        private set

    fun start() {
        if (running) return
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(bindAddress), port))
            }
        } catch (e: Exception) {
            Log.e(TAG, "web bind failed on $bindAddress:$port", e)
            throw e
        }
        serverSocket = socket
        running = true
        Log.i(TAG, "renew server listening on ${socket.inetAddress.hostAddress}:${socket.localPort}")

        acceptJob = scope.launch {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (!running || socket.isClosed) break
                    Log.w(TAG, "web accept error, continuing: ${e.message}")
                    continue
                }
                launch { handleClient(client) }
            }
            Log.i(TAG, "renew accept loop exited")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
        Log.i(TAG, "renew server stopped")
    }

    private suspend fun handleClient(client: Socket) {
        runCatching { client.soTimeout = READ_TIMEOUT_MS }
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
            val requestLine = runCatching { reader.readLine() }.getOrNull()
            // Drain the request headers so the client can reuse the connection cleanly.
            runCatching {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
            }
            val parts = requestLine?.trim()?.split(" ")
            if (parts != null && parts.size >= 2 && parts[0] == "GET" && stripQuery(parts[1]) == "/renew") {
                // The renew cycle waits on modem callbacks plus timeouts
                // (25s radio-off, 30s reconnect, 3s cooldown), so hold the
                // socket open long enough instead of timing out early.
                runCatching { client.soTimeout = RENEW_TIMEOUT_MS }
                val outcome = renewMutex.withLock { airplane.renew() }
                when (outcome) {
                    is AirplaneModeController.Result.Ok ->
                        reply(client, 200, "renewed\n")
                    is AirplaneModeController.Result.Failed ->
                        reply(client, 500, "renew failed: " + outcome.reason + "\n")
                }
            } else {
                reply(client, 404, "not found\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "renew request failed: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun stripQuery(path: String): String {
        val q = path.indexOf('?')
        return if (q >= 0) path.substring(0, q) else path
    }

    private fun reply(client: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        runCatching {
            val out = client.getOutputStream()
            out.write(head.toByteArray(StandardCharsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        404 -> "Not Found"
        else -> "Internal Server Error"
    }

    companion object {
        private const val TAG = "RenewWebServer"
        private const val READ_TIMEOUT_MS = 10_000
        // Upper bound of one renew cycle (25s radio-off incl. re-kick + 30s
        // reconnect + 3s cooldown), plus headroom so the socket never dies
        // before the answer is ready.
        private const val RENEW_TIMEOUT_MS = 65_000

        const val DEFAULT_WEB_PORT = 8080
    }
}
