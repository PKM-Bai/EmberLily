# EmberLily

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

用户已确认 Kotlin／Jetpack Compose、C/C++ JNI 和 mGBA；详见 [项目方案](docs/PROJECT_PLAN.md)。

## Users and purpose

主要用户在 AYN Thor 双屏掌机上游玩 GBA 火焰纹章。上屏保留原游戏，下屏提供信息、背包管理、培养、章节攻略与结构研究。产品范围、状态语义及验收以 [项目方案](docs/PROJECT_PLAN.md) 为唯一依据。

## Current surface

M1 预览界面提供战场、会话和研究三个已实现入口。指定 FE8 中文语音构建可跟随光标显示单位和物品、固定对比、导出 RAM 快照，并在独立实验分支修改 HP 和恢复。未验证的构建实时能力保持关闭；未解析的中文名称显示文本编号。军团背包、培养、攻略等后续功能尚不显示空入口。

## Constraints

原始 ROM 保持不变，实验存档与主进度隔离；触摸副屏不应让实体按键失去控制目标。目标游戏和 Thor 的实际验证结果单独记录。
