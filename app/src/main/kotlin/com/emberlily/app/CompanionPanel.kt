package com.emberlily.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emberlily.app.fe.*
import java.util.Locale

@Composable
fun CompanionPanel(state: CompanionState, pin: (UnitIdentity?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val battle = state.battle
        Text("战场详情", style = MaterialTheme.typography.headlineSmall)
        if (state.profile == null) {
            Text(state.message, style = MaterialTheme.typography.bodyLarge)
            Text("可在会话页导出 RAM 快照供离线研究。", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(state.profile.identity.label, style = MaterialTheme.typography.labelLarge)
        if (!state.profile.supports(FeCapability.LIVE_UNITS) || !state.profile.supports(FeCapability.CHAPTER_AND_CURSOR)) {
            Text("已识别此文件；实时战局尚未完成该构建的运行验证。", style = MaterialTheme.typography.bodyLarge)
            Text("当前可普通游玩和导出 RAM 快照，能力状态按具体文件分别记录。", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (battle == null || battle.scene == FeScene.UNAVAILABLE || battle.scene == FeScene.INVALID) {
            Text(state.message, style = MaterialTheme.typography.bodyLarge)
            Text("进入已识别的战局后自动显示；标题、转场或未解析状态不会保留上一场详情。",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            val phase = when (battle.phase) { 0 -> "我方"; 0x40 -> "友军"; 0x80 -> "敌方"; else -> "阶段 ${battle.phase}" }
            Text("章节 #${battle.chapter} · 回合 ${battle.turn} · $phase", style = MaterialTheme.typography.titleMedium)
            Text("光标 ${battle.cursor?.x ?: "—"}, ${battle.cursor?.y ?: "—"} · 已解析 ${battle.units.size} 个单位",
                style = MaterialTheme.typography.bodyMedium)
            if (state.profile.nameEncoding == FeNameEncoding.UNVERIFIED_CUSTOM) {
                Text("此中文构建的名称暂以文本编号显示。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val focused = battle.focusedUnit
            val comparison = state.comparison
            if (comparison != null) {
                UnitComparison(focused, comparison, pin)
            } else {
                UnitColumn("光标单位", focused, action = {
                    if (focused != null) OutlinedButton(onClick = { pin(focused.identity) }) { Text("固定对比") }
                })
            }
        }
        HorizontalDivider()
        Text("近期 P95 · 捕获 %.1f ms / 解码 %.1f ms / 更新 %.1f ms".format(Locale.ROOT,
            state.captureMs, state.decodeMs, state.latencyMs), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("帧工作 P95 %.1f ms · 超预算 %d 帧 · 捕获 %d 次".format(Locale.ROOT,
            state.frameP95Ms, state.frameOverruns, state.captured), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnitComparison(focused: FeUnit?, pinned: FeUnit, pin: (UnitIdentity?) -> Unit) {
    // Lay out corresponding fields in shared rows: wrapping on either side must
    // never shift subsequent stats out of alignment, even on the Thor lower screen.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ComparisonRow(
            left = { Text("光标单位", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
            right = { Text("固定对比", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
        )
        ComparisonRow(
            left = { Text(focused?.name?.display ?: "光标下无单位", style = MaterialTheme.typography.titleLarge) },
            right = { Text(pinned.name.display, style = MaterialTheme.typography.titleLarge) },
        )
        ComparisonRow(
            left = { Text(focused?.className?.display ?: "—", style = MaterialTheme.typography.bodyMedium) },
            right = { Text(pinned.className.display, style = MaterialTheme.typography.bodyMedium) },
        )
        ComparisonRow(
            left = { Text(focused?.let { "Lv ${it.level} · EXP ${it.experience}" } ?: "—", style = MaterialTheme.typography.bodyMedium) },
            right = { Text("Lv ${pinned.level} · EXP ${pinned.experience}", style = MaterialTheme.typography.bodyMedium) },
        )
        HorizontalDivider()
        ComparisonStat("HP", focused?.let { "${it.hp} / ${it.maxHp}" }, "${pinned.hp} / ${pinned.maxHp}")
        ComparisonStat("力", focused?.power?.toString(), pinned.power.toString())
        ComparisonStat("技", focused?.skill?.toString(), pinned.skill.toString())
        ComparisonStat("速", focused?.speed?.toString(), pinned.speed.toString())
        ComparisonStat("防", focused?.defense?.toString(), pinned.defense.toString())
        ComparisonStat("抗", focused?.resistance?.toString(), pinned.resistance.toString())
        ComparisonStat("运", focused?.luck?.toString(), pinned.luck.toString())
        HorizontalDivider()
        ComparisonRow(
            left = {
                OutlinedButton(onClick = { focused?.let { pin(it.identity) } }, enabled = focused != null) { Text("固定对比") }
            },
            right = { TextButton(onClick = { pin(null) }) { Text("取消固定") } },
        )
        Text("RAM 基础值", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ComparisonRow(
            left = { UnitLocation(focused) },
            right = { UnitLocation(pinned) },
        )
        ComparisonRow(
            left = { Text("携带物品", style = MaterialTheme.typography.titleSmall) },
            right = { Text("携带物品", style = MaterialTheme.typography.titleSmall) },
        )
        for (slot in 0 until maxOf(focused?.items?.size ?: 0, pinned.items.size)) {
            ComparisonRow(
                left = { ComparisonItem(focused?.items?.getOrNull(slot)) },
                right = { ComparisonItem(pinned.items.getOrNull(slot)) },
            )
        }
    }
}

@Composable
private fun ComparisonRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

@Composable
private fun ComparisonStat(label: String, left: String?, right: String) {
    ComparisonRow(left = { StatValue(label, left ?: "—") }, right = { StatValue(label, right) })
}

@Composable
private fun StatValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun UnitLocation(unit: FeUnit?) {
    Text(unit?.let { "单位槽 #${it.slot} · 坐标 ${it.x}, ${it.y}" } ?: "—",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ComparisonItem(item: FeItemSlot?) {
    Column {
        Text(item?.let { "${it.slot + 1}. ${it.name.display}" } ?: "—", style = MaterialTheme.typography.bodyMedium)
        if (item != null && item.itemId != 0) {
            Text("耐久 ${item.uses} / ${item.maxUses ?: "?"}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UnitColumn(title: String, unit: FeUnit?, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (unit == null) {
            Text("光标下没有可显示的单位", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Text(unit.name.display, style = MaterialTheme.typography.titleLarge)
        Text("${unit.className.display} · Lv ${unit.level} · EXP ${unit.experience}", style = MaterialTheme.typography.bodyMedium)
        Text("HP ${unit.hp} / ${unit.maxHp}", style = MaterialTheme.typography.headlineSmall)
        Text("力 ${unit.power}    技 ${unit.skill}    速 ${unit.speed}\n防 ${unit.defense}    抗 ${unit.resistance}    运 ${unit.luck}",
            style = MaterialTheme.typography.bodyLarge)
        Text("单位槽 #${unit.slot} · 坐标 ${unit.x}, ${unit.y} · RAM 基础值", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        unit.items.forEach { item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${item.slot + 1}. ${item.name.display}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (item.itemId != 0) Text("${item.uses} / ${item.maxUses ?: "?"}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        action()
    }
}
