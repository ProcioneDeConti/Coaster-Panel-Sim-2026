package com.abemu.coaster.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.abemu.coaster.sim.FictionalScenery
import com.abemu.coaster.sim.RealScenery
import kotlin.math.cos
import kotlin.math.sin

/** Static OSM scenery under the plan view, rendered once per ride / size / day-night into a bitmap. */
class PlanSceneryCache {
    private var key: Triple<PlanTransform, Boolean, Density>? = null
    private var image: ImageBitmap? = null

    fun get(xf: PlanTransform, night: Boolean, density: Density): ImageBitmap? {
        val sc = RealScenery.byId(xf.profile.spec.id) ?: FictionalScenery.forProfile(xf.profile)
        val k = Triple(xf, night, density)
        if (k != key) {
            val img = ImageBitmap(xf.width.toInt().coerceAtLeast(1), xf.height.toInt().coerceAtLeast(1))
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img),
                Size(xf.width, xf.height)) { drawScenery(xf, sc, night) }
            image = img
            key = k
        }
        return image
    }
}

private fun DrawScope.drawScenery(xf: PlanTransform, sc: RealScenery.Scenery, night: Boolean) {
    fun path(pts: List<Pair<Double, Double>>, close: Boolean) = Path().apply {
        pts.forEachIndexed { i, q -> val o = xf.planPt(q); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        if (close) close()
    }
    val ground = if (night) Color(0xFF060A0F) else Color(0xFF0F1A24)
    val shore = Color(0xFF2E5F86)
    val waterFill = Color(if (night) 0xFF0A1A2E else 0xFF143552)
    fun area(pts: List<Pair<Double, Double>>, fill: Color) {
        val p = path(pts, true)
        drawPath(p, fill)
        drawPath(p, shore, style = Stroke(1.5f))
    }
    // Lake Erie and the land inside it, with a beach along the whole lake shore: a wide sand stroke whose inner
    // half the land fill then covers. Then the park's own lagoons and their islands.
    val sandFill = Color(if (night) 0xFF4A4232 else 0xFF8C7B57)
    sc.lake.forEach { area(it, waterFill) }
    sc.lakeLand.forEach { l ->
        val p = path(l, true)
        drawPath(p, sandFill, style = Stroke(20f))
        drawPath(p, ground)
    }
    sc.water.forEach { area(it, waterFill) }
    sc.land.forEach { area(it, ground) }
    val r = xf.planPt(1.1 to 0.0).x - xf.planPt(0.0 to 0.0).x
    val tree = Color(0xFFA5D6A7).copy(alpha = if (night) 0.35f else 0.8f)
    val leaf = Color(0xFF7CB342).copy(alpha = if (night) 0.12f else 0.3f)
    sc.trees.forEachIndexed { i, q ->
        val c = xf.planPt(q)
        // Hand-drawn canopy: a lumpy outline with uneven bumps, plus a short inner branch stroke.
        val rnd = java.util.Random(i * 7919L + 17)
        val rr = r * (0.75f + rnd.nextFloat() * 0.5f)
        val n = 6 + rnd.nextInt(4)
        val rot = rnd.nextDouble() * 2 * Math.PI
        val radii = FloatArray(n) { rr * (0.8f + rnd.nextFloat() * 0.3f) }
        fun at(a: Double, rad: Float) = c + Offset((cos(a) * rad).toFloat(), (sin(a) * rad).toFloat())
        val crown = Path()
        for (k in 0..n) {
            val a = rot + k * 2 * Math.PI / n
            val p = at(a, radii[k % n] * 0.82f)
            if (k == 0) crown.moveTo(p.x, p.y) else {
                val ctl = at(a - Math.PI / n, radii[k % n] * (1.15f + rnd.nextFloat() * 0.25f))
                crown.quadraticTo(ctl.x, ctl.y, p.x, p.y)
            }
        }
        drawPath(crown, leaf)
        drawPath(crown, tree, style = Stroke(2.2f))
        val b = rot + rnd.nextDouble() * Math.PI
        drawLine(tree, at(b, rr * 0.35f), at(b + Math.PI + rnd.nextDouble() - 0.5, rr * 0.3f), 1.6f)
    }

}
