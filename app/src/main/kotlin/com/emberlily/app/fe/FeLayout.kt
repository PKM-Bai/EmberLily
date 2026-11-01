package com.emberlily.app.fe

/** A verified engine layout, selected by an exact ROM profile, never by the GBA header. */
data class FeLayout(
    val id: String,
    val bmState: Long,
    val playState: Long,
    val units: List<UnitRegion>,
    val activeUnit: Long,
    val mapSize: Long,
    val mapUnitRows: Long,
    val mapTerrainRows: Long,
    val procPool: Long,
    val procRoots: Long,
    val mapTaskScript: Long,
    val characterTable: Long,
    val classTable: Long,
    val itemTable: Long,
    val textTable: Long,
    val playerPhaseScript: Long,
    val bmapMainScript: Long,
    val idle: PlayerIdleContract?,
    val movement: PlayerMovementContract?,
    val characterSize: Int = 0x34,
    val characterFirstId: Int = 1,
    val classSize: Int = 0x54,
    val classFirstId: Int = 0,
    val itemSize: Int = 0x24,
    val unitSize: Int = 0x48,
    val procCount: Int = 64,
    val procSize: Int = 0x6C,
    val chapterOffset: Int = 0x0E,
    val phaseOffset: Int = 0x0F,
    val turnOffset: Int = 0x10,
    val cursorOffset: Int = 0x14,
    val huffmanBaseLiteral: Long = 0x080006E0,
    val huffmanRootLiteral: Long = 0x080006DC,
) {
    data class UnitRegion(val address: Long, val count: Int, val firstSlot: Int)
    fun unitAddress(slot: Int): Long? = units.singleOrNull { slot in it.firstSlot until it.firstSlot + it.count }
        ?.let { it.address + (slot - it.firstSlot) * unitSize }
}

data class PlayerIdleContract(val cursor: Long, val callback: Long, val blockedScripts: Set<Long>, val parentLock: Int = 1)
data class PlayerMovementContract(val cursor: Long, val callback: Long)
enum class FeNameEncoding { SHIFT_JIS, UNVERIFIED_CUSTOM }

object GbaRamCapture {
    val ranges = listOf(MemoryRange(0x02000000, 0x40000), MemoryRange(0x03000000, 0x8000))
}

object FeLayouts {
    val fe8j = FeLayout(
        "fe8j", Fe8JLayout.BM_STATE, Fe8JLayout.PLAY_STATE,
        listOf(FeLayout.UnitRegion(Fe8JLayout.PLAYER_UNITS, 62, 1),
            FeLayout.UnitRegion(Fe8JLayout.ENEMY_UNITS, 50, 0x81), FeLayout.UnitRegion(Fe8JLayout.NPC_UNITS, 20, 0x41)),
        Fe8JLayout.ACTIVE_UNIT, Fe8JLayout.MAP_SIZE, Fe8JLayout.MAP_UNIT_ROWS, Fe8JLayout.MAP_TERRAIN_ROWS,
        Fe8JLayout.PROC_POOL, Fe8JLayout.PROC_ROOTS, Fe8JLayout.MAP_TASK_SCRIPT,
        Fe8JLayout.CHARACTER_TABLE, Fe8JLayout.CLASS_TABLE, Fe8JLayout.ITEM_TABLE, Fe8JLayout.TEXT_TABLE,
        Fe8JLayout.PLAYER_PHASE_SCRIPT, Fe8JLayout.BMAP_MAIN_SCRIPT,
        PlayerIdleContract(Fe8JLayout.PLAYER_IDLE_CURSOR, Fe8JLayout.PLAYER_IDLE_CALLBACK,
            setOf(0x085B98A0L, 0x085B98D8L, 0x085C3248L)),
        PlayerMovementContract(Fe8JLayout.PLAYER_MOVEMENT_CURSOR, Fe8JLayout.PLAYER_MOVEMENT_CALLBACK),
    )

    // R02/R06 ROM literals and their own live process trees were checked independently.
    val fe7j = FeLayout(
        id = "fe7j", bmState = 0x0202BBB4, playState = 0x0202BBF4,
        units = listOf(FeLayout.UnitRegion(0x0202BD4C, 62, 1), FeLayout.UnitRegion(0x0202CEBC, 50, 0x81),
            FeLayout.UnitRegion(0x0202DCCC, 20, 0x41)),
        activeUnit = 0x030045B0, mapSize = 0x0202E3D4, mapUnitRows = 0x0202E3D8, mapTerrainRows = 0x0202E3DC,
        procPool = 0x02024E20, procRoots = 0x02026A28, mapTaskScript = 0x08C05464,
        characterTable = 0x08C4C158, classTable = 0x08C4F468, itemTable = 0x08C51538, textTable = 0x08BBB370,
        playerPhaseScript = 0x08C02630, bmapMainScript = 0x08C01DBC,
        idle = PlayerIdleContract(0x08C026A0, 0x0801C625,
            setOf(0x08BFFF78L, 0x08BFFE90L, 0x08BFFEF8L, 0x08BFFF58L, 0x08C028E8L)),
        movement = PlayerMovementContract(0x08C026E0, 0x0801CACD),
    )
}
