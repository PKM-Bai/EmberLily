package com.emberlily.app.fe

import org.junit.Assert.*
import org.junit.Test

class FeInventoryTest {
    private val stamp = SnapshotStamp(RomProfiles.FE8J_VOICE_SHA256,
        "01234567-89ab-cdef-0123-456789abcdef", "main", 3, 120, 7, "fe8j-voice-r04")
    private fun item(slot: Int, id: Int, uses: Int, textId: Int = 0x2DB, category: Int = 0) =
        FeItemSlot(slot, uses shl 8 or id, id, uses, FeName(textId, null), 46,
            if (id == 0) null else FeItemDetails(category, 1, 5, 90, 5, 0, 0x11))

    private fun unit(slot: Int, items: List<FeItemSlot>) = FeUnit(
        identity = UnitIdentity(stamp.copy(frame = 0, revision = 0), 2, slot, 1, 0x088582BC),
        slot = slot, address = 0x0202BE48L + slot * 0x48, characterId = 1, classId = 1,
        name = FeName(0x199, "角色"), className = FeName(0x246, "职业"), faction = slot and 0xC0,
        x = 2, y = 3, level = 1, experience = 0, maxHp = 20, hp = 20,
        power = 5, skill = 5, speed = 5, defense = 5, resistance = 0, luck = 5, state = 0, items = items,
    )

    @Test fun filteringNeverUsesDisplayIndexAsPhysicalSlotOrMergesIdenticalItems() {
        val first = unit(1, listOf(item(0, 2, 40), item(1, 0, 0), item(2, 1, 12), item(3, 1, 12)))
        val second = unit(7, listOf(item(0, 1, 46)))
        val enemy = unit(0x81, listOf(item(0, 1, 46)))
        val battle = FeBattleSnapshot(stamp, FeScene.MAP, units = listOf(first, second, enemy))
        val query = FeInventoryQuery(searchField = FeInventorySearchField.ITEM_ID, hexQuery = "01")
        val result = FeInventory.entries(battle, query)
        assertEquals(listOf(1 to 2, 1 to 3, 7 to 0), result.map { it.owner!!.slot to it.item.slot })
        assertEquals(listOf(0x0C01, 0x0C01, 0x2E01), result.map { it.item.raw })
        assertTrue(result.all { it.stamp == stamp })
        assertEquals(4, first.items.size) // Source not compacted.
        assertEquals(0, first.items[1].itemId)
        assertEquals(1, FeInventory.entries(battle, query.copy(faction = 0x80)).single().item.itemId)
        assertEquals(4, FeInventory.entries(battle, query.copy(faction = null)).size)
        assertEquals(2, FeInventory.entries(battle, query.copy(ownerSlot = 1)).size)
    }

    @Test fun hexFieldsAreExplicitAndCategoryAndEmptyFiltersCompose() {
        val battle = FeBattleSnapshot(stamp, FeScene.PREPARATION, units = listOf(unit(10,
            listOf(item(0, 1, 46), item(1, 0x6C, 3, 0x36C, 9), item(2, 0, 0)))))
        assertEquals(0x6C, FeInventory.entries(battle,
            FeInventoryQuery(hexQuery = "#036C", category = 9)).single().item.itemId)
        assertEquals(2, FeInventory.entries(battle, FeInventoryQuery(
            searchField = FeInventorySearchField.UNIT_SLOT, hexQuery = "0xa")).size)
        assertEquals(2, FeInventory.entries(battle, FeInventoryQuery(
            searchField = FeInventorySearchField.UNIT_TEXT_ID, hexQuery = "0199")).size)
        assertTrue(FeInventory.entries(battle, FeInventoryQuery(hexQuery = "6C")).isEmpty())
        assertTrue(FeInventory.entries(battle, FeInventoryQuery(hexQuery = "XYZ")).isEmpty())
        assertTrue(FeInventory.entries(battle, FeInventoryQuery(hexQuery = "10000")).isEmpty())
        assertEquals(3, FeInventory.entries(battle, FeInventoryQuery(nonEmptyOnly = false)).size)
    }

    @Test fun convoyUsesItsOwnPhysicalSlotsAndInvalidScenesExposeNoStaleEntries() {
        val convoy = FeConvoySnapshot(100, List(100) { slot -> when (slot) {
            37 -> item(slot, 1, 9)
            99 -> item(slot, 1, 8)
            else -> item(slot, 0, 0)
        } })
        val battle = FeBattleSnapshot(stamp, FeScene.MAP, units = listOf(unit(1, listOf(item(0, 2, 1)))), convoy = convoy)
        val query = FeInventoryQuery(source = FeInventorySource.CONVOY)
        val entries = FeInventory.entries(battle, query)
        assertEquals(listOf(37, 99), entries.map { it.item.slot })
        assertTrue(entries.all { it.owner == null && it.stamp == stamp })
        assertTrue(FeInventory.entries(battle, query.copy(searchField = FeInventorySearchField.UNIT_SLOT, hexQuery = "1")).isEmpty())
        assertTrue(FeInventory.entries(battle.copy(scene = FeScene.UNAVAILABLE), query).isEmpty())
        assertTrue(FeInventory.entries(battle.copy(scene = FeScene.INVALID)).isEmpty())
        assertNull(battle.copy(convoy = null).convoy) // Unknown is distinct from a decoded empty warehouse.
        assertTrue(FeInventory.entries(battle.copy(convoy = null), query).isEmpty())
    }

    @Test fun dynamicAndUnknownRangeDoNotBecomeLiteralFifteenTileRanges() {
        val details = FeItemDetails(4, 4, 0, 100, 0, 0, 0x12)
        assertEquals(1, details.minRange)
        assertEquals(2, details.maxRange)
        for (encoded in listOf(0, 0x1F, 0xFF, 0x21)) {
            assertNull(details.copy(encodedRange = encoded).minRange)
            assertNull(details.copy(encodedRange = encoded).maxRange)
        }
        assertTrue(details.isStaff)
        assertFalse(details.isWeapon)
        assertTrue(details.copy(attributes = 9).unbreakable)
    }
}
