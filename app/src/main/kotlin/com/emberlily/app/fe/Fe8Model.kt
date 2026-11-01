package com.emberlily.app.fe

data class MapPosition(val x: Int, val y: Int)
enum class FeScene { UNAVAILABLE, MAP, PREPARATION, INVALID }
data class FeName(val textId: Int, val text: String?, val note: String? = null) {
    val display: String get() = text ?: "名称 #${textId.toString(16).uppercase().padStart(4, '0')}"
}
data class UnitIdentity(val context: SnapshotStamp, val chapter: Int, val slot: Int, val generation: Long, val characterAddress: Long)
data class FeItemSlot(val slot: Int, val raw: Int, val itemId: Int, val uses: Int, val name: FeName, val maxUses: Int?,
    val details: FeItemDetails? = null)

/** ROM base values, not a unit's effective battle forecast or equip permission. */
data class FeItemDetails(
    val category: Int,
    val attributes: Long,
    val might: Int,
    val hit: Int,
    val weight: Int,
    val critical: Int,
    val encodedRange: Int,
) {
    val isWeapon: Boolean get() = attributes and 1L != 0L
    val isStaff: Boolean get() = attributes and 4L != 0L
    val unbreakable: Boolean get() = attributes and 8L != 0L
    // 0xF encodes dynamic ranges; do not present the nibble as a literal 15 tiles.
    private val fixedRange: Boolean get() = (encodedRange ushr 4) in 1..14 &&
        (encodedRange and 15) in (encodedRange ushr 4)..14
    val minRange: Int? get() = (encodedRange ushr 4).takeIf { fixedRange }
    val maxRange: Int? get() = (encodedRange and 15).takeIf { fixedRange }
}

/** All physical slots, including empty ones. Presence never grants normal supply access. */
data class FeConvoySnapshot(val capacity: Int, val items: List<FeItemSlot>)
data class FeUnit(
    val identity: UnitIdentity,
    val slot: Int,
    val address: Long,
    val characterId: Int,
    val classId: Int,
    val name: FeName,
    val className: FeName,
    val faction: Int,
    val x: Int,
    val y: Int,
    val level: Int,
    val experience: Int,
    val maxHp: Int,
    val hp: Int,
    val power: Int,
    val skill: Int,
    val speed: Int,
    val defense: Int,
    val resistance: Int,
    val luck: Int,
    val state: Long,
    val items: List<FeItemSlot>,
)
data class FeBattleSnapshot(
    val stamp: SnapshotStamp,
    val scene: FeScene,
    val reason: String? = null,
    val chapter: Int? = null,
    val turn: Int? = null,
    val phase: Int? = null,
    val cursor: MapPosition? = null,
    val mapWidth: Int? = null,
    val mapHeight: Int? = null,
    val units: List<FeUnit> = emptyList(),
    /** Only the verified player movement-selection state; menus and other selection modes remain unknown/null. */
    val selectedSlot: Int? = null,
    val mapProcLock: Int? = null,
    val activeProcScripts: Set<Long> = emptySet(),
    /** Null means not verified/readable in this snapshot, not an empty warehouse. */
    val convoy: FeConvoySnapshot? = null,
) {
    val focusedUnit: FeUnit? get() = cursor?.let { position -> units.firstOrNull {
        it.x == position.x && it.y == position.y && it.state and (0x4L or 0x8L or 0x20L) == 0L
    } }
    val selectedUnit: FeUnit? get() = units.firstOrNull { it.slot == selectedSlot }
}

/** Tracks observed occupancy, not an invented permanent character-instance identifier. */
class UnitLifetimeTracker {
    data class Observation(val slot: Int, val characterAddress: Long)
    private var scope: SnapshotStamp? = null
    private var chapter: Int? = null
    private var previous = emptyMap<Int, UnitIdentity>()
    private var generation = 0L

    fun invalidate() { previous = emptyMap() }

    fun identify(stamp: SnapshotStamp, chapter: Int, observed: List<Observation>): Map<Int, UnitIdentity> {
        require(observed.map { it.slot }.distinct().size == observed.size) { "Duplicate deployment slots" }
        val nextScope = stamp.copy(frame = 0, revision = 0)
        if (scope != nextScope || this.chapter != chapter) previous = emptyMap()
        scope = nextScope
        this.chapter = chapter
        val next = observed.associate { item ->
            require(item.slot in 1..255 && item.characterAddress in 0x08000000L..0x09FFFFFFL)
            val old = previous[item.slot]?.takeIf { it.characterAddress == item.characterAddress }
            item.slot to (old ?: UnitIdentity(nextScope, chapter, item.slot, ++generation, item.characterAddress))
        }
        previous = next
        return next
    }
}
