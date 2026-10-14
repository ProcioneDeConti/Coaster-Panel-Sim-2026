package com.abemu.coaster.sim

import kotlin.math.abs

/**
 * A feature tagged in the track editor, as seen by the generator. Positions are fractions of the
 * circuit so tags survive changes to the simulated track length. [kind] is a dev TagType name.
 */
data class LayoutTag(val kind: String, val fStart: Double, val fEnd: Double, val name: String = "")

/** Result of applying tags: the rebuilt profile plus one line per tag saying what happened. */
class TaggedLayout(val profile: RideProfile, val report: List<String>)

/**
 * Re-zones a generated ride from editor tags. Track geometry is kept as generated; tags move the
 * course block brakes, block boundaries and transfer track, and rename blocks. A tag is rejected
 * when the physics can't support it (brake on a slope, or a stopped train couldn't roll on).
 */
object TagLayout {
    private const val LOSS = 0.04 // same conservative head loss the generator uses
    private const val RESTART_HEAD = 0.2 // ~2 m/s off the drive tires
    private const val STEP = 2.0
    private val BRAKES = setOf("BLOCK_BRAKE", "TUNNEL")

    fun apply(base: RideProfile, tags: List<LayoutTag>): TaggedLayout {
        if (tags.isEmpty()) return TaggedLayout(base, emptyList())
        val p = base
        val report = mutableListOf<String>()
        fun s(f: Double) = p.wrap(f * p.length)
        val station = p.blocks[0]
        val drive = p.blocks[1]
        // Generated end of the free-running course: the final brake and unload stay as built.
        val finalIdx = p.blocks.indexOfLast { it.kind == BlockKind.BRAKE_RUN }
        val final = p.blocks[finalIdx]
        val courseStart = drive.end
        val courseEnd = final.brakeStart!!

        fun label(t: LayoutTag) = t.kind.lowercase().replace('_', ' ') + if (t.name.isNotEmpty()) " \"${t.name}\"" else ""
        // A second (tunnel) launch is an energy source the course depends on: tags may only go before it.
        val tunnel = p.blocks.subList(2, finalIdx).firstOrNull { it.exitLaunch > 0 }
        val limit = tunnel?.brakeStart ?: courseEnd
        fun inCourse(a: Double, b: Double) = a > courseStart + 10 && b <= limit && a < b

        // Track heights on a 2 m grid; tags reshape this.
        val n = (p.length / STEP).toInt()
        val h = DoubleArray(n + 1) { p.height(it * STEP) }
        fun idx(s: Double) = (s / STEP).toInt().coerceIn(0, n)

        // ---- Course brakes ----
        data class Cand(val tag: LayoutTag, val start: Double, val end: Double)
        val cands = tags.filter { it.kind in BRAKES }.mapNotNull { t ->
            val a = s(t.fStart); val b = s(t.fEnd)
            val len = p.ahead(a, b)
            when {
                // A tag over a kept brake (final or tunnel) just renames it.
                p.blocks.subList(finalIdx, p.blocks.size).plus(listOfNotNull(tunnel)).any { p.inRange(a + len / 2, it.start, it.end) } -> null
                !inCourse(a, b) -> { report += "${label(t)}: skipped, outside the free-running course"; null }
                len < p.trainLength + 10 -> { report += "${label(t)}: rejected, %.0f m is shorter than a train + 10 m".format(len); null }
                else -> Cand(t, a, b)
            }
        }.sortedBy { it.start }
            .fold(mutableListOf<Cand>()) { acc, c ->
                val prev = acc.lastOrNull()
                if (prev != null && c.start < prev.end + p.trainLength) {
                    report += "${label(c.tag)}: rejected, overlaps or crowds the previous brake"
                } else acc += c
                acc
            }

        // Walk the course carrying the available energy head. Each brake is levelled at a height the
        // train can reach; past its stop point, hills are trimmed so a train leaving from a stop
        // (off the drive tires) still clears them on the way to the final brake.
        val crest = (idx(courseStart - 15)..idx(courseStart)).maxOf { h[it] }
        var srcS = courseStart
        var srcHead = crest + if (p.type == ProfileType.LIFT) p.liftSpeed * p.liftSpeed / (2 * G) else 0.5
        val target = tunnel?.let { p.height(it.stopPos!!) } ?: p.height(final.stopPos!!)
        val accepted = mutableListOf<Cand>()
        for (c in cands) {
            val reach = srcHead - LOSS * (c.start - srcS)
            val brakeH = minOf((idx(c.start)..idx(c.end)).maxOf { h[it] }, reach - 3)
            val stop = c.end - 5
            val rollOn = brakeH + RESTART_HEAD - LOSS * (limit - stop) - 1
            if (brakeH < 1.0) { report += "${label(c.tag)}: rejected, a train couldn't reach it"; continue }
            if (rollOn < target) { report += "${label(c.tag)}: rejected, too far from the next brake for a stopped train to roll on"; continue }
            // Ramp in over a few metres, level through the brake.
            val ramp = maxOf(10.0, abs(h[idx(c.start)] - brakeH) * 2)
            val r0 = idx(maxOf(srcS + 2, c.start - ramp)); val r1 = idx(c.start)
            val h0 = h[r0]
            for (i in r0 until r1) h[i] = h0 + (brakeH - h0) * (i - r0) / maxOf(1, r1 - r0).toDouble()
            for (i in r1..idx(c.end)) h[i] = brakeH
            for (i in idx(c.end) + 1..idx(limit)) {
                val x = i * STEP - c.end
                // Trim hills to what a train leaving the stop can clear, and drop off the brake at no
                // more than 1:2 (the plant caps slope, so steeper drops would lose energy).
                h[i] = maxOf(minOf(h[i], brakeH + RESTART_HEAD - LOSS * (i * STEP - stop) - 1), brakeH - 0.5 * x)
            }
            srcS = stop; srcHead = brakeH + RESTART_HEAD
            accepted += c
        }

        val blocks = mutableListOf(station, drive)
        if (accepted.isEmpty()) {
            blocks += p.blocks.subList(2, finalIdx)
        } else {
            for (c in accepted) {
                blocks += Block(blocks.size, "B${blocks.size + 1}", c.tag.name.ifEmpty { "Block Brake" },
                    BlockKind.BRAKE_RUN, blocks.last().end, c.end, c.end - 5, c.start)
                report += "${label(c.tag)}: applied as ${blocks.last().tag}"
            }
            tunnel?.let { blocks += it.copy(index = blocks.size, tag = "B${blocks.size + 1}", start = blocks.last().end) }
            val dropped = p.blocks.subList(2, finalIdx).filter { it !== tunnel }
            if (dropped.isNotEmpty()) report += "generated ${dropped.joinToString { it.name }} replaced by tags"
        }
        for (b in p.blocks.subList(finalIdx, p.blocks.size)) blocks += b.copy(index = blocks.size, tag = "B${blocks.size + 1}", start = blocks.last().end)

        // ---- Block boundaries: move a block's end between its stop point and the next brake ----
        for (t in tags.filter { it.kind == "BLOCK_BOUNDARY" }) {
            val x = s(t.fStart)
            val i = (2 until blocks.size - 1).firstOrNull { val b = blocks[it]; b.stopPos != null && p.inRange(x, b.stopPos + 3, blocks[it + 1].brakeStart ?: blocks[it + 1].end) }
            if (i == null) { report += "${label(t)}: skipped, must lie between a stop point and the next brake"; continue }
            blocks[i] = blocks[i].copy(end = x)
            blocks[i + 1] = blocks[i + 1].copy(start = x)
            report += "${label(t)}: ${blocks[i].tag}/${blocks[i + 1].tag} boundary moved"
        }

        // ---- Renames and transfer ----
        fun rename(i: Int, t: LayoutTag) { if (t.name.isNotEmpty()) { blocks[i] = blocks[i].copy(name = t.name); report += "${label(t)}: renamed ${blocks[i].tag}" } }
        var transfer: Int? = null
        for (t in tags) {
            val mid = p.wrap(s(t.fStart) + p.ahead(s(t.fStart), s(t.fEnd)) / 2)
            val i = blocks.indexOfFirst { p.inRange(mid, it.start, it.end) }
            when (t.kind) {
                "LOAD_STATION" -> if (blocks[i].kind == BlockKind.STATION) rename(i, t) else report += "${label(t)}: not on the generated station"
                "UNLOAD_STATION" -> if (blocks[i].kind == BlockKind.UNLOAD) rename(i, t) else report += "${label(t)}: this ride has no unload station there"
                "LIFT", "LAUNCH" -> if (i == 1) rename(i, t) else report += "${label(t)}: not on the generated ${if (p.type == ProfileType.LIFT) "lift" else "launch"}"
                "TRANSFER" -> if (blocks[i].kind == BlockKind.BRAKE_RUN) { transfer = i; report += "${label(t)}: transfer on ${blocks[i].tag}" }
                    else report += "${label(t)}: transfer must be on a brake run"
                "BLOCK_BRAKE", "TUNNEL" -> if (blocks[i].kind == BlockKind.BRAKE_RUN &&
                    (blocks[i].exitLaunch > 0 || i == blocks.indexOfLast { it.kind == BlockKind.BRAKE_RUN })) rename(i, t)
                "TRIM_BRAKE", "SENSOR" -> report += "${label(t)}: informational only (not simulated yet)"
            }
        }
        val elevation = if (accepted.isEmpty()) null else simplify(h, p.length)
        return TaggedLayout(p.withBlocks(blocks, transfer, elevation), report)
    }

    /** Grid heights to (s, h) points, dropping points that lie on a straight line. */
    private fun simplify(h: DoubleArray, length: Double): List<Pair<Double, Double>> {
        val out = mutableListOf(0.0 to h[0])
        for (i in 1 until h.size - 1) {
            val slopeIn = h[i] - h[i - 1]; val slopeOut = h[i + 1] - h[i]
            if (abs(slopeIn - slopeOut) > 1e-6) out += i * STEP to h[i]
        }
        out += length to h[0] // closed circuit: the station end matches the start
        return out
    }
}
