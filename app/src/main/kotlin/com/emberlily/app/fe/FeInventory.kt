package com.emberlily.app.fe

enum class FeInventorySource { UNITS, CONVOY }
enum class FeInventorySearchField { ITEM_ID, ITEM_TEXT_ID, UNIT_TEXT_ID, UNIT_SLOT }

data class FeInventoryQuery(
    val source: FeInventorySource = FeInventorySource.UNITS,
    val faction: Int? = 0,
    val ownerSlot: Int? = null,
    val category: Int? = null,
    val nonEmptyOnly: Boolean = true,
    val searchField: FeInventorySearchField = FeInventorySearchField.ITEM_TEXT_ID,
    val hexQuery: String = "",
)

/** Filtering never rewrites the physical item slot or drops the source context. */
data class FeInventoryEntry(val owner: FeUnit?, val item: FeItemSlot, val stamp: SnapshotStamp)

object FeInventory {
    fun entries(battle: FeBattleSnapshot, query: FeInventoryQuery = FeInventoryQuery()): List<FeInventoryEntry> {
        if (battle.scene != FeScene.MAP && battle.scene != FeScene.PREPARATION) return emptyList()
        val input = query.hexQuery.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        val search = if (input.isEmpty()) null else {
            if (!Regex("[0-9a-fA-F]{1,4}").matches(input)) return emptyList()
            input.toInt(16)
        }
        val entries = when (query.source) {
            FeInventorySource.UNITS -> battle.units.asSequence()
                .filter { query.faction == null || it.faction == query.faction }
                .filter { query.ownerSlot == null || it.slot == query.ownerSlot }
                .flatMap { unit -> unit.items.asSequence().map { FeInventoryEntry(unit, it, battle.stamp) } }
            FeInventorySource.CONVOY -> battle.convoy?.items.orEmpty().asSequence()
                .map { FeInventoryEntry(null, it, battle.stamp) }
        }
        return entries.filter { !query.nonEmptyOnly || it.item.itemId != 0 }
            .filter { query.category == null || it.item.details?.category == query.category }
            .filter { entry -> search == null || when (query.searchField) {
                FeInventorySearchField.ITEM_ID -> entry.item.itemId
                FeInventorySearchField.ITEM_TEXT_ID -> entry.item.name.textId
                FeInventorySearchField.UNIT_TEXT_ID -> entry.owner?.name?.textId
                FeInventorySearchField.UNIT_SLOT -> entry.owner?.slot
            } == search }.toList()
    }
}
