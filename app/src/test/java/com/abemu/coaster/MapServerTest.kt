package com.abemu.coaster

import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.web.MapServer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class MapServerTest {
    private fun get(port: Int, path: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        return c.responseCode to (if (c.responseCode == 200) c.inputStream else c.errorStream).bufferedReader().readText()
    }

    @Test fun servesPageGeometryAndState() {
        val server = MapServer(18080)
        server.start()
        try {
            assertTrue(server.error ?: "", server.running)
            val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.default(ProfileType.LIFT)), 3))
            server.publish(plc, null)
            val (code, page) = get(18080, "/map")
            val home = get(18080, "/").second
            assertTrue(home, home.contains("href=\"/map\"") && home.contains("KEY OUT"))
            assertTrue(code == 200 && page.contains("<canvas"))
            val geo = get(18080, "/geometry").second
            assertTrue(geo, geo.startsWith("{\"version\":1") && geo.contains("\"points\":[[") && geo.contains("\"blocks\":[{"))
            get(18080, "/state") // first poll wakes the publisher
            server.publish(plc, null)
            val st = get(18080, "/state").second
            assertTrue(st, st.contains("\"state\":\"POWER OFF\"") && st.contains("\"trains\":[{\"id\":1"))
            assertTrue(get(18080, "/nope").first == 404)
        } finally {
            server.stop()
        }
    }

    private fun post(port: Int, path: String): Int {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        return c.responseCode
    }

    @Test fun instructorNeedsPinAndQueuesCommands() {
        val server = MapServer(18081)
        server.start()
        try {
            assertTrue(get(18081, "/instructor/state?pin=1234").first == 403) // disabled
            server.instructorEnabled = true
            assertTrue(get(18081, "/instructor/state?pin=0000").first == 403)
            assertTrue(post(18081, "/instructor/cmd?pin=0000&cmd=fault&key=air&on=1") == 403)
            assertTrue(get(18081, "/instructor/cmd?pin=1234&cmd=fault").first == 405)
            assertTrue(post(18081, "/instructor/cmd?pin=1234&cmd=fault&key=air&on=1") == 200)
            assertTrue(get(18081, "/instructor/state?pin=1234").first == 200)
            val got = mutableListOf<Map<String, String>>()
            server.serviceInstructor({ got += it }) { "{\"ride\":\"x\"}" }
            assertTrue(got.toString(), got == listOf(mapOf("cmd" to "fault", "key" to "air", "on" to "1")))
            assertTrue(get(18081, "/instructor/state?pin=1234").second == "{\"ride\":\"x\"}")
            assertTrue(get(18081, "/instructor").second.contains("INSTRUCTOR PIN"))
        } finally {
            server.stop()
        }
    }
}
