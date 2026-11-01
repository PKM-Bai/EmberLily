package com.emberlily.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.emberlily.app.fe.*

internal data class PrepTradeSelection(val context: PrepTradeContext, val left: Int = 0, val right: Int = 0)

@Composable
fun PrepTradePanel(player: PlayerState, companion: CompanionState, ui: InventoryUiState, exchange: (PrepTradeRequest) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("准备交换", style = MaterialTheme.typography.titleMedium)
        val context = player.prepTrade
        if (companion.profile?.identity?.sha256 != RomProfiles.FE8J_VOICE_SHA256) {
            Text("此游戏版本目前可查看、查找和比较物品；正常交换尚未开放。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (context == null) {
            Text("在上屏的准备／整理物品中选好两名角色并进入交换画面，再在这里选择物品交换。",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            val signature = context.copy(stamp = context.stamp.copy(frame = 0), cursorSlot = 0, selectedSlot = 255, helpSlot = 255, idle = true)
            val selection = ui.tradeSelection?.takeIf { it.context == signature } ?: PrepTradeSelection(signature)
            val left = selection.left
            val right = selection.right
            val units = companion.battle?.units.orEmpty()
            fun name(unit: PrepTradeUnit) = units.firstOrNull { it.address == unit.address }?.name?.display ?: "角色槽 #${unit.slot}"
            fun item(unit: PrepTradeUnit, slot: Int): String {
                val raw = unit.items[slot]
                if (raw == 0) return "空位"
                val parsed = units.firstOrNull { it.address == unit.address }?.items?.getOrNull(slot)?.takeIf { it.raw == raw }
                return parsed?.name?.display ?: "物品 #${(raw and 255).toString(16).uppercase().padStart(2, '0')}"
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TradeSlotSelector(name(context.left), context.left, left, { item(context.left, it) },
                    { ui.tradeSelection = selection.copy(left = it) }, !player.busy && context.ready, Modifier.weight(1f))
                TradeSlotSelector(name(context.right), context.right, right, { item(context.right, it) },
                    { ui.tradeSelection = selection.copy(right = it) }, !player.busy && context.ready, Modifier.weight(1f))
            }
            val plan = runCatching { PrepTradeCommands.plan(context.request(left, right), context) }
            if (!context.ready) {
                Text("请先在上屏关闭帮助框或取消当前物品选择。", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${item(context.left, left)} ↔ ${item(context.right, right)}", style = MaterialTheme.typography.bodyMedium)
                if (plan.isFailure) Text(plan.exceptionOrNull()?.message ?: "当前选择不可交换", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { exchange(context.request(left, right)) }, enabled = !player.busy && plan.isSuccess) {
                Text(if (player.busy) "正在交换…" else "确认交换")
            }
            Text("按原游戏流程交换；物品耐久保持，空槽由游戏整理。执行时请暂勿操作实体按键。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (player.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(player.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
    }
}

@Composable
private fun TradeSlotSelector(name: String, unit: PrepTradeUnit, selected: Int, itemName: (Int) -> String,
    select: (Int) -> Unit, enabled: Boolean, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled) { Text("槽 ${selected + 1} · ${itemName(selected)}") }
            DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
                val count = unit.items.count { it != 0 }
                for (slot in 0..minOf(count, 4)) {
                    DropdownMenuItem(text = { Text("槽 ${slot + 1} · ${itemName(slot)}") }, onClick = { select(slot); open = false })
                }
            }
        }
    }
}
