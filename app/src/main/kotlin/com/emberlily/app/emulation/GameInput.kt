package com.emberlily.app.emulation

/** Each source owns its held bits: releasing touch must not release a physical key. */
class GameInput {
    private val sources = mutableMapOf<String, Int>()
    private var generation = 0L

    @Synchronized fun set(source: String, bits: Int) {
        require(bits and 0x3FF == bits)
        if ((sources[source] ?: 0) != bits) generation++
        if (bits == 0) sources.remove(source) else sources[source] = bits
    }

    @Synchronized fun keys(): Int = sources.values.fold(0) { all, next -> all or next }
    /** Includes a press released between two frames, so command guards cannot miss it. */
    @Synchronized fun revision(): Long = generation
    @Synchronized fun idleRevision(): Long? = generation.takeIf { sources.isEmpty() }
    @Synchronized fun releaseAll() { if (sources.isNotEmpty()) generation++; sources.clear() }
    @Synchronized fun releaseDevice(deviceId: Int) {
        if (sources.keys.removeAll { it.startsWith("key:$deviceId:") || it == "stick:$deviceId" }) generation++
    }
}
