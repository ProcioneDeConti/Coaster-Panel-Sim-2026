package com.abemu.coaster.sim

/** Rides made with the OSM ride builder. Loaded from app storage at startup; listed after the built-in coasters. */
object CustomRides {
    class Ride(val spec: CoasterSpec, val layout: RealLayouts.Layout, val source: String)

    val rides = mutableListOf<Ride>()

    fun layout(id: String): RealLayouts.Layout? = RealLayouts.byId[id] ?: rides.firstOrNull { it.spec.id == id }?.layout

    fun put(r: Ride) { rides.removeAll { it.spec.id == r.spec.id }; rides += r }
    fun remove(id: String) { rides.removeAll { it.spec.id == id } }
}
