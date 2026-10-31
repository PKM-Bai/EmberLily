package com.emberlily.app.emulation

/** Each source owns its held bits: releasing touch must not release a physical key. */
class GameInput {
    private val sources = mutableMapOf<String, Int>()

    @Synchronized fun set(source: String, bits: Int) {
        require(bits and 0x3FF == bits)
        if (bits == 0) sources.remove(source) else sources[source] = bits
    }

    @Synchronized fun keys(): Int = sources.values.fold(0) { all, next -> all or next }
    @Synchronized fun releaseAll() = sources.clear()
    @Synchronized fun releaseDevice(deviceId: Int) {
        sources.keys.removeAll { it.startsWith("key:$deviceId:") || it == "stick:$deviceId" }
    }
}
