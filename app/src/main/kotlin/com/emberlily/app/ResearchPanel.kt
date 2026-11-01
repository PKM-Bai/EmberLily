package com.emberlily.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.emberlily.app.fe.HpEditRequest
import com.emberlily.app.fe.FeCapability

@Composable
fun ResearchPanel(player: PlayerState, companion: CompanionState, fork: () -> Unit,
    edit: (HpEditRequest) -> Unit, restore: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("研究编辑", style = MaterialTheme.typography.headlineSmall)
        Text(if (player.experiment) player.branchLabel else "主进度 · 当前只读", style = MaterialTheme.typography.titleMedium)
        if (player.experiment) OutlinedButton(onClick = restore, enabled = !player.busy && player.hasResearchRecovery) {
            Text("恢复编辑前状态")
        }
        if (companion.profile?.supports(FeCapability.RESEARCH_EDIT) != true) {
            Text("此构建的研究写入尚未通过验证。可继续在战场页查看已解析信息。")
            return@Column
        }
        if (!player.experiment) {
            Text("创建当前进度的独立副本后，可修改单位 HP。编辑会暂停游戏，并保存编辑前恢复点。")
            Button(onClick = fork, enabled = !player.busy) { Text("创建实验分支") }
            return@Column
        }
        val battle = companion.battle
        val unit = companion.comparison ?: battle?.focusedUnit
        if (unit != null && battle != null) {
            // Preserve the user's editing context. A changed HP/identity explicitly starts a new form.
            val request = remember(unit.identity, unit.hp) {
                HpEditRequest(battle.stamp, unit.identity, unit.classId, unit.hp, maxOf(1, unit.hp - 1))
            }
            var hp by remember(request) { mutableIntStateOf(request.targetHp) }
            Text(unit.name.display, style = MaterialTheme.typography.titleLarge)
            Text("当前 HP ${request.expectedHp} / ${unit.maxHp}", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = { hp-- }, enabled = !player.busy && hp > 1) { Text("−") }
                Text("目标 $hp", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { hp++ }, enabled = !player.busy && hp < unit.maxHp) { Text("＋") }
            }
            Text("仅在玩家回合、未选中单位且没有事件或动画时允许修改；范围为 1 到最大 HP。",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { edit(request.copy(targetHp = hp)) }, enabled = !player.busy && hp != request.expectedHp) {
                Text("确认修改：${request.expectedHp} → $hp")
            }
        } else Text("将游戏光标移到单位上，或在战场页固定一个对比对象。")
        HorizontalDivider()
        Text(player.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (player.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
