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
    /** Remote instructor console (/instructor). Off unless the instructor enables it on the tablet. */
    @Volatile var instructorEnabled = false
    @Volatile private var instructorState = "{}"
    @Volatile private var lastInstructorPoll = 0L
    private val commands = java.util.concurrent.ConcurrentLinkedQueue<Map<String, String>>()
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
    fun publish(plc: RidePlc, drill: Drill?, desk: String? = null) {
        if (!running) return
        if (plc !== geomFor) {
            geomFor = plc
            geomVersion++
            geometry = WebMap.geometry(plc, geomVersion)
        }
        if (System.currentTimeMillis() - lastPoll < 5000) state = WebMap.state(plc, drill, geomVersion, desk)
    }

    /**
     * Call from the sim thread each frame, before [publish]: applies queued remote-instructor commands
     * and refreshes the instructor snapshot while a console is open.
     */
    fun serviceInstructor(apply: (Map<String, String>) -> Unit, snapshot: () -> String) {
        if (!running) return
        if (!instructorEnabled) { commands.clear(); return }
        while (true) apply(commands.poll() ?: break)
        if (System.currentTimeMillis() - lastInstructorPoll < 5000) instructorState = snapshot()
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
            val parts = line.split(' ')
            val target = parts.getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val authed = instructorEnabled && RemoteInstructor.pinOk(query["pin"])
            val forbidden = Triple("403 Forbidden", "text/plain", "forbidden")
            val (status, type, body) = when (path) {
                "/", "/index.html" -> Triple("200 OK", "text/html; charset=utf-8", WebHome.page(instructorEnabled))
                "/map" -> Triple("200 OK", "text/html; charset=utf-8", WebMap.PAGE)
                "/geometry" -> Triple("200 OK", "application/json", geometry)
                "/state" -> { lastPoll = System.currentTimeMillis(); Triple("200 OK", "application/json", state) }
                "/instructor" -> Triple("200 OK", "text/html; charset=utf-8", RemoteInstructor.PAGE)
                "/instructor/state" -> if (!authed) forbidden else {
                    if (System.currentTimeMillis() - lastInstructorPoll >= 5000) instructorState = "{}"
                    lastInstructorPoll = System.currentTimeMillis()
                    Triple("200 OK", "application/json", instructorState)
                }
                "/instructor/cmd" -> when {
                    parts.getOrNull(0) != "POST" -> Triple("405 Method Not Allowed", "text/plain", "POST only")
                    !authed -> forbidden
                    else -> { if (commands.size < 100) commands.add(query - "pin"); Triple("200 OK", "text/plain", "ok") }
                }
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

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { '=' in it }.associate {
        val dec = { v: String -> try { java.net.URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v } }
        dec(it.substringBefore('=')) to dec(it.substringAfter('='))
    }
}
