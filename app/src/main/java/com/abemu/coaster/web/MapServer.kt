package com.abemu.coaster.web

import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.Drill
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Tiny HTTP server that serves a live plan-view map to browsers on other devices.
 * The simulation runs on the main thread, so it publishes JSON snapshots ([publish]) that the
 * server threads only read.
 */
class MapServer(val port: Int = 8080) {
    @Volatile private var geometry = "{}"
    @Volatile private var state = "{}"
    @Volatile private var lastPoll = 0L
    private var geomFor: RidePlc? = null
    private var geomVersion = 0
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "map-http").apply { isDaemon = true } }

    val running get() = server?.isClosed == false
    var error: String? = null; private set

    fun start() {
        if (running) return
        error = null
        try {
            val s = ServerSocket(port)
            server = s
            Thread({
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Exception) { break }
                    pool.execute { serve(c) }
                }
            }, "map-accept").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    fun stop() {
        server?.close()
        server = null
    }

    /** Call from the sim thread each frame. Skips the work when nobody is watching. */
    fun publish(plc: RidePlc, drill: Drill?) {
        if (!running) return
        if (plc !== geomFor) {
            geomFor = plc
            geomVersion++
            geometry = WebMap.geometry(plc, geomVersion)
        }
        if (System.currentTimeMillis() - lastPoll < 5000) state = WebMap.state(plc, drill, geomVersion)
    }

    /** URLs this device can be reached at (one per IPv4 interface). */
    fun urls(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "http://${it.hostAddress}:$port" }
    } catch (_: Exception) { emptyList() }

    private fun serve(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val line = reader.readLine() ?: return
            while (!reader.readLine().isNullOrEmpty()) { /* skip headers */ }
            val path = line.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            val (status, type, body) = when (path) {
                "/", "/index.html" -> Triple("200 OK", "text/html; charset=utf-8", WebMap.PAGE)
                "/geometry" -> Triple("200 OK", "application/json", geometry)
                "/state" -> { lastPoll = System.currentTimeMillis(); Triple("200 OK", "application/json", state) }
                else -> Triple("404 Not Found", "text/plain", "not found")
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }
    }
}
