package com.emberlily.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.emberlily.app.fe.*

internal data class InventorySelection(val owner: UnitIdentity?, val slot: Int, val raw: Int) {
    fun matches(entry: FeInventoryEntry) = owner == entry.owner?.identity && slot == entry.item.slot && raw == entry.item.raw
}

/** Owned by the tab host, so switching pages preserves browsing within the same game context. */
@Stable
class InventoryUiState {
    val listState = LazyListState()
    var query by mutableStateOf(FeInventoryQuery())
    var searching by mutableStateOf(false)
    var filtersExpanded by mutableStateOf(false)
    var operationsExpanded by mutableStateOf(false)
    internal var selected by mutableStateOf<InventorySelection?>(null)
    internal var pinned by mutableStateOf<InventorySelection?>(null)
    internal var tradeSelection by mutableStateOf<PrepTradeSelection?>(null)
}

@Composable
fun rememberInventoryUiState(companion: CompanionState): InventoryUiState =
    remember(companion.battle?.stamp?.copy(frame = 0), companion.battle?.chapter) { InventoryUiState() }

@Composable
fun InventoryPanel(companion: CompanionState, ui: InventoryUiState, modifier: Modifier = Modifier, operationMessage: String? = null,
    operations: @Composable () -> Unit = {}) {
    val battle = companion.battle
    val supported = companion.profile?.supports(FeCapability.ITEM_DATA) == true &&
        companion.profile.supports(FeCapability.LIVE_UNITS)
    if (battle == null || battle.scene !in setOf(FeScene.MAP, FeScene.PREPARATION) ||
        !supported) {
        Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("军团／背包", style = MaterialTheme.typography.headlineSmall)
            Text(if (supported) "进入已适配的战场或准备画面后，显示角色物品。"
                else "当前游戏版本的实时背包尚未验证，可在会话页继续游玩。")
            Text(companion.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            operations()
        }
        return
    }
    val allEntries = FeInventory.entries(battle, FeInventoryQuery(faction = null, nonEmptyOnly = false)) +
        FeInventory.entries(battle, FeInventoryQuery(source = FeInventorySource.CONVOY, nonEmptyOnly = false))
    val selected = allEntries.firstOrNull { ui.selected?.matches(it) == true }
    val pinned = allEntries.firstOrNull { ui.pinned?.matches(it) == true }
    LaunchedEffect(ui.selected, ui.pinned, allEntries) {
        if (ui.selected != null && selected == null) ui.selected = null
        if (ui.pinned != null && pinned == null) ui.pinned = null
    }
    if (selected != null) {
        InventoryDetail(selected, pinned, onBack = { ui.selected = null },
            onPin = { ui.pinned = ui.selected }, onUnpin = { ui.pinned = null }, modifier = modifier)
        return
    }
    val entries = FeInventory.entries(battle, ui.query)
    LazyColumn(modifier, state = ui.listState, contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("军团／背包", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = ui.query.source == FeInventorySource.UNITS,
                    onClick = { ui.query = ui.query.copy(source = FeInventorySource.UNITS) }, label = { Text("角色背包") })
                FilterChip(selected = ui.query.source == FeInventorySource.CONVOY,
                    onClick = { ui.query = ui.query.copy(source = FeInventorySource.CONVOY) }, label = { Text("仓库") })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ui.filtersExpanded = !ui.filtersExpanded; ui.searching = false; ui.operationsExpanded = false }) { Text("筛选") }
                OutlinedButton(onClick = { ui.searching = !ui.searching; ui.filtersExpanded = false; ui.operationsExpanded = false }) { Text("编号查找") }
                OutlinedButton(onClick = { ui.operationsExpanded = !ui.operationsExpanded; ui.filtersExpanded = false; ui.searching = false }) { Text("准备交换") }
            }
        }
        if (ui.operationsExpanded) item { operations() }
        if (!ui.operationsExpanded && operationMessage != null) item {
            Text(operationMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (ui.filtersExpanded) item { InventoryFilters(battle, ui) }
        if (ui.searching) item { InventorySearch(ui) }
        if (ui.query.hexQuery.isNotEmpty() && !ui.searching) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${searchLabel(ui.query.searchField)} #${ui.query.hexQuery}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { ui.query = ui.query.copy(hexQuery = "") }) { Text("清除") }
            }
        }
        if (companion.profile?.nameEncoding == FeNameEncoding.UNVERIFIED_CUSTOM) item {
            Text("中文名称暂以编号显示，可按名称编号或物品 ID 查找。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pinned != null) item {
            TextButton(onClick = { ui.selected = ui.pinned }) { Text("已固定：${pinned.item.name.display} · 查看") }
        }
        item {
            Text(when {
                ui.query.source == FeInventorySource.CONVOY && battle.convoy == null -> "仓库状态未知"
                ui.query.source == FeInventorySource.CONVOY -> "${entries.size} 个结果 · 仓库容量 ${battle.convoy?.capacity} 格"
                else -> "${entries.size} 个物品槽 · ${factionLabel(ui.query.faction)}${ui.query.category?.let { " · ${categoryLabel(it)}" } ?: ""}"
            },
                style = MaterialTheme.typography.titleSmall)
        }
        if (ui.query.source == FeInventorySource.CONVOY && battle.convoy == null) item {
            Text("此构建或当前场景的仓库读取尚未验证，不能判断仓库是否为空。")
        } else if (entries.isEmpty()) item {
            Text("没有符合筛选的物品。可清除编号或切换角色、类别。")
            TextButton(onClick = { ui.query = FeInventoryQuery(source = ui.query.source) }) { Text("重置筛选") }
        }
        items(entries, key = { "${it.owner?.slot ?: "convoy"}:${it.item.slot}" }) { entry ->
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "查看物品详情") {
                ui.selected = InventorySelection(entry.owner?.identity, entry.item.slot, entry.item.raw)
            }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.item.name.display, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(itemUses(entry.item), style = MaterialTheme.typography.bodyMedium)
                }
                Text(ownerLabel(entry), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${categoryLabel(entry.item.details?.category)} · 物品 ID #${entry.item.itemId.toString(16).uppercase().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InventoryFilters(battle: FeBattleSnapshot, ui: InventoryUiState) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.query.source == FeInventorySource.UNITS) {
            InventoryMenu(when (ui.query.faction) { 0 -> "我方"; 0x40 -> "友军"; 0x80 -> "敌方"; else -> "全部阵营" },
                listOf("我方" to 0, "友军" to 0x40, "敌方" to 0x80, "全部阵营" to null)) {
                ui.query = ui.query.copy(faction = it, ownerSlot = null)
            }
            val owners = battle.units.filter { ui.query.faction == null || it.faction == ui.query.faction }
            InventoryMenu(owners.firstOrNull { it.slot == ui.query.ownerSlot }?.let { "角色槽 #${it.slot.toString(16).uppercase()}" } ?: "全部角色",
                listOf("全部角色" to null) + owners.map { "${it.name.display} · 槽 #${it.slot.toString(16).uppercase()}" to it.slot }) {
                ui.query = ui.query.copy(ownerSlot = it)
            }
        }
        val categories = ((0..10).toList() + battle.units.flatMap { it.items }.mapNotNull { it.details?.category } +
            battle.convoy?.items.orEmpty().mapNotNull { it.details?.category }).distinct().sorted()
        InventoryMenu(ui.query.category?.let(::categoryLabel) ?: "全部类别",
            listOf("全部类别" to null) + categories.map { categoryLabel(it) to it }) { ui.query = ui.query.copy(category = it) }
        FilterChip(selected = !ui.query.nonEmptyOnly, onClick = { ui.query = ui.query.copy(nonEmptyOnly = !ui.query.nonEmptyOnly) },
            label = { Text("显示空槽") })
    }
}

@Composable
private fun <T> InventoryMenu(label: String, options: List<Pair<String, T>>, select: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp),
            properties = PopupProperties(focusable = false)) {
            options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { select(value); open = false }) }
        }
    }
}

@Composable
private fun InventorySearch(ui: InventoryUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InventoryMenu(searchLabel(ui.query.searchField), FeInventorySearchField.entries.map { searchLabel(it) to it }) {
            ui.query = ui.query.copy(searchField = it, hexQuery = "")
        }
        Text("十六进制编号：${ui.query.hexQuery.ifEmpty { "未输入" }}", style = MaterialTheme.typography.titleMedium)
        // The secondary Presentation deliberately keeps gamepad focus on the game.
        // Touch keys allow searching without an IME or stealing that focus.
        "0123456789ABCDEF".chunked(4).forEach { keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.forEach { key ->
                    FilledTonalButton(onClick = { ui.query = ui.query.copy(hexQuery = ui.query.hexQuery + key) },
                        enabled = ui.query.hexQuery.length < 4, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(key.toString()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ui.query = ui.query.copy(hexQuery = ui.query.hexQuery.dropLast(1)) },
                enabled = ui.query.hexQuery.isNotEmpty()) { Text("退格") }
            OutlinedButton(onClick = { ui.query = ui.query.copy(hexQuery = "") }) { Text("清空") }
            Button(onClick = { ui.searching = false }) { Text("查看结果") }
        }
    }
}

@Composable
private fun InventoryDetail(current: FeInventoryEntry, pinned: FeInventoryEntry?, onBack: () -> Unit,
    onPin: () -> Unit, onUnpin: () -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("返回背包列表") }
        Text(if (pinned == null) "物品详情" else "物品对比", style = MaterialTheme.typography.headlineSmall)
        if (pinned == null) {
            Text(current.item.name.display, style = MaterialTheme.typography.titleLarge)
            Text(ownerLabel(current), style = MaterialTheme.typography.bodyMedium)
            for ((label, value) in itemFields(current.item)) InventoryValue(label, value)
        } else {
            InventoryPair({ Text("当前物品", style = MaterialTheme.typography.labelLarge) },
                { Text("固定物品", style = MaterialTheme.typography.labelLarge) })
            InventoryPair({ Text(current.item.name.display, style = MaterialTheme.typography.titleLarge) },
                { Text(pinned.item.name.display, style = MaterialTheme.typography.titleLarge) })
            InventoryPair({ Text(ownerLabel(current), style = MaterialTheme.typography.bodyMedium) },
                { Text(ownerLabel(pinned), style = MaterialTheme.typography.bodyMedium) })
            val other = itemFields(pinned.item)
            itemFields(current.item).forEachIndexed { index, field ->
                InventoryPair({ InventoryValue(field.first, field.second) }, { InventoryValue(other[index].first, other[index].second) })
            }
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPin) { Text("固定此物品对比") }
            if (pinned != null) TextButton(onClick = onUnpin) { Text("取消固定") }
        }
        Text("物品基础数据；实际效果还取决于角色、职业和游戏规则。特殊射程未解析时不猜测数值。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InventoryPair(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

@Composable
private fun InventoryValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
    }
}

private fun itemFields(item: FeItemSlot): List<Pair<String, String>> {
    val data = item.details
    val weapon = data != null && data.attributes and 1L != 0L
    return listOf("类别" to categoryLabel(data?.category), "耐久" to itemUses(item),
        "威力" to if (weapon) "${data?.might}" else "—", "命中" to if (weapon) "${data?.hit}" else "—",
        "必杀" to if (weapon) "${data?.critical}" else "—", "重量" to if (weapon) "${data?.weight}" else "—",
        "射程" to when { data == null || (!data.isWeapon && !data.isStaff) -> "—"; data.minRange == null || data.maxRange == null -> "未解析";
            data.minRange == 0 && data.maxRange == 0 -> "—"; data.minRange == data.maxRange -> "${data.minRange}";
            else -> "${data.minRange}–${data.maxRange}" })
}

private fun itemUses(item: FeItemSlot) = when {
    item.itemId == 0 -> "—"
    item.details?.unbreakable == true -> "不消耗耐久"
    else -> "${item.uses} / ${item.maxUses ?: "?"}"
}

private fun ownerLabel(entry: FeInventoryEntry) = entry.owner?.let {
    "${it.name.display} · 角色槽 #${it.slot.toString(16).uppercase()} · 物品槽 ${entry.item.slot + 1}"
} ?: "仓库 · 槽 ${entry.item.slot + 1}"

private fun categoryLabel(category: Int?) = when (category) {
    null -> "未解析类别"
    0 -> "剑"; 1 -> "枪"; 2 -> "斧"; 3 -> "弓"; 4 -> "杖"; 5 -> "理"; 6 -> "光"; 7 -> "暗"
    8 -> "弩车"; 9 -> "道具"; 10 -> "龙石"
    else -> "类别 #${category.toString(16).uppercase()}"
}

private fun searchLabel(field: FeInventorySearchField) = when (field) {
    FeInventorySearchField.ITEM_ID -> "物品 ID"
    FeInventorySearchField.ITEM_TEXT_ID -> "物品名称编号"
    FeInventorySearchField.UNIT_TEXT_ID -> "角色名称编号"
    FeInventorySearchField.UNIT_SLOT -> "角色槽编号"
}

private fun factionLabel(faction: Int?) = when (faction) {
    0 -> "我方"; 0x40 -> "友军"; 0x80 -> "敌方"; else -> "全部阵营"
}
