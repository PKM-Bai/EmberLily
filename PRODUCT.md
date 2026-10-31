# EmberLily

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

用户已确认 Kotlin／Jetpack Compose、C/C++ JNI 和 mGBA；详见 [项目方案](docs/PROJECT_PLAN.md)。

## Users and purpose

主要用户在 AYN Thor 双屏掌机上游玩 GBA 火焰纹章。上屏保留原游戏，下屏提供信息、背包管理、培养、章节攻略与结构研究。产品范围、状态语义及验收以 [项目方案](docs/PROJECT_PLAN.md) 为唯一依据。

## Current surface

M0 是可验证的宿主：导入本地 ROM、运行与暂停、保存恢复、建立独立实验会话。FE 语义面板在后续里程碑接入，当前界面不能展示虚构的单位或宣称已适配游戏。

## Constraints

原始 ROM 保持不变，实验存档与主进度隔离；触摸副屏不应让实体按键失去控制目标。目标游戏和 Thor 的实际验证结果单独记录。
