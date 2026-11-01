# FE7J：M2 地址、结构与操作证据

本文件记录登记目标 R02、R06 的只读调查。地址均为 GBA 总线地址；标为“ROM 偏移”的数值是文件内偏移。源码候选、实际 ROM 字节核对、运行快照和完整操作验收分开记录；两份文件中部分代码相同，不代表它们的全部能力互相继承。天地之剑 R05 不在本文件的适配声明中。

## 目标与复现

| 目标 | SHA-256 | 字节数 |
| --- | --- | ---: |
| R02，FE7 日版 | `7ec691a7411864ab51765028156c5b8a3c23a317d23c95fbf07ac201212e25c0` | 16777216 |
| R06，狼组／火花天龙剑中文 Fix 文件 | `828b8bb1bff54a66b89cb1e43901d88bb783a4da6e543ac31aa6edc17e162d25` | 16777216 |

两者 header 均为 `FIREEMBLEM7` / `AE7J`。中文构建名称来自登记文件名；没有据此确认其发布版本或完整汉化行为。`scripts/research_fe7j.py` 只接受上述完整 SHA，读取 `.local/targets.json` 中的文件，不写 ROM、存档或设备。

```powershell
python scripts/research_fe7j.py --output .local/m2/fe7j-evidence.json
```

取得独立运行样本后可追加 `--state .local/m2/probes/r06-idle.state`。脚本检查 `ELSTATE2` 封装、完整大小及封装内 ROM 指纹，输出原始语义摘要和从根可达的 Proc；场景是否有效仍由适配器和场景证据判定。输出仅允许写入忽略的 `.local/` JSON 文件，并禁止覆盖输入文件。报告不进入版本库。

一手来源固定如下：

- [MokhaLeee/FireEmblem7J，`d0697e208b6fa03a81e4968a54f4b8ddc5f3925b`](https://github.com/MokhaLeee/FireEmblem7J/tree/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b)。其 README 声明的目标 SHA-1 `037702b1febd5c9535262165bf030551d153de81` 与 R02 实际 SHA-1 一致；本次没有重新编译该反编译工程。地址候选来自其链接脚本和带地址汇编，并逐项读取本地目标验证。
- [FEBuilderGBA，`8e3557912ddab708d11bf85ad311b41bb6630fd1` 的 `ROMFE7JP.cs`](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/ROMFE7JP.cs) 提供独立布局交叉依据。不使用 FE7 美版或 FE8 地址代替本目标。
- 关键交换函数另用本地 Capstone 5.0.9 ARM Thumb 解码实际字节核对。没有声称通过 REA/Ghidra 完成分析。

## ROM 静态表与文本

下列指针已在 R02、R06 各自实际文件读取确认。

| 数据 | 总线地址／值 | ROM 偏移处的证据 |
| --- | --- | --- |
| 人物表 ID **1** | `0x08C4C158`，步长 `0x34` | `0x9AC50` |
| 职业表 ID **0** | `0x08C4F468`，步长 `0x54` | `0x17CE0` |
| 物品表 ID **0** | `0x08C51538`，步长 `0x24` | `0x164DC`、`0x176E0` |
| 文本指针表 | `0x08BBB370`，每项 4 字节 | `0x13370` |
| Huffman 根指针地址 | `0x08BBB36C`，其中值为根 `0x08BBB368` | `0x6DC` |
| Huffman 基址，R02 | `0x08BB72E0` | `0x6E0` |
| Huffman 基址，R06 | `0x08BB5A90` | `0x6E0` |
| 仓库数组 | `0x0203A71C`，100 个 u16 | `0x2EBF0`、`0x2EC58` |

人物 ID n 位于 `base + (n - 1) * 0x34`，职业和物品 ID n 位于 `base + n * stride`。人物和职业记录的 number 位于 `+4`，物品 number 位于 `+6`；三个表的名称 ID 均为 `+0` 的 u16。单位 RAM 指向的记录还需验证地址对齐、范围和 number，不能仅根据名称猜表。

物品基础耐久字节位于 `+0x14`。`GetItemMaxUses`（`0x080176C4`）还检查 `+8` 属性字的不可损坏标志 `0x8`，命中时返回 `0xFF`；基础表值不能无条件解释为最终可消耗次数。仓库计数函数 `0x0802EC3C` 从 `0x63` 递减并包含 0，确认 100 项上界。[物品函数汇编](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/asm/item.s)、[仓库函数汇编](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/asm/code1.s)

| 静态短名称样本 | 名称 ID | R02 解码 | R06 原始解码字节 |
| --- | --- | --- | --- |
| 人物 1 | `0x04E4` | エリウッド | `83aa8bc691e185f9` |
| 职业 1 | `0x0494` | ロード | `8bf495cf` |
| 物品 1 | `0x03F8` | てつの剣 | `90fe89e1` |
| 物品 2 | `0x03F9` | ほそみの剣 | `92888fc089e1` |

R02 样本的 Huffman 结果可按 CP932 解码。R06 使用修改字形的编码位置；即便某些字节可被标准编码库接受，也不能证明字形对应。R06 名称标记为 `custom_glyph_mapping_unverified`，保留编号，等待该构建自身字库映射。该判断也说明两构建不能共用固定 Huffman 基址。

## RAM 最小语义合同

地址以 FE7J 链接脚本、FEBuilder 配置和本地 ROM 的指针字面量交叉核对；运行值仍需对应快照确认。[链接脚本](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/FireEmblem7J.lds)、[单位结构](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/include/gbafe/unit.h)、[战局结构](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/include/gbafe/bm.h)

| 数据 | FE7J 地址 | 最小合同 |
| --- | --- | --- |
| `gBmSt` | `0x0202BBB4` | 大小 `0x40`；`+0` main loop ended、`+1` game lock、`+2` display lock、`+4` flags；`+0x14/+0x16` s16 当前光标 |
| `gPlaySt` | `0x0202BBF4` | `+0x0E` chapter、`+0x0F` phase、`+0x10` u16 turn、`+0x14` chapter bits |
| 玩家单位 | `0x0202BD4C` | 62 项，deployment index `0x01..0x3E` |
| 敌军单位 | `0x0202CEBC` | 50 项，index `0x81..0xB2` |
| NPC 单位 | `0x0202DCCC` | 20 项，index `0x41..0x54` |
| 当前操作单位指针 | `0x030045B0` | u32；不等同光标单位 |
| 当前操作单位 index | `0x0202BD44` | 与指针指向的单位 index 交叉核对 |
| 地图宽／高 | `0x0202E3D4` | 两个 s16 |
| 单位／地形行表指针 | `0x0202E3D8` / `0x0202E3DC` | 先读取指针，再读取第 y 行指针，再读取第 x 列 |
| 移动／射程／迷雾／隐藏行表指针 | `0x0202E3E0` / `0x0202E3E4` / `0x0202E3E8` / `0x0202E3EC` | 分别解释，不能把内存图等同完整战术结论 |
| Proc 池 | `0x02024E20` | 64 项，每项 `0x6C` |
| Proc 根数组 | `0x02026A28` | 8 个 u32 根指针 |
| 战斗 actor／target | `0x0203A3EC` / `0x0203A46C` | 地址候选已核对；本次不声明完整战斗模型可用 |
| action 数据 | `0x0203A858` | 字段级动作操作仍需独立适配 |

单位步长为 `0x48`，最小共有字段：人物／职业指针 `+0/+4`，等级／经验 `+8/+9`，deployment index `+0x0B`，u32 state `+0x0C`，s8 坐标 `+0x10/+0x11`，最大／当前 HP `+0x12/+0x13`，力／技／速／防／魔防／幸运 `+0x14..+0x19`，5 个 u16 物品 `+0x1E..+0x27`，武器等级 `+0x28..+0x2F`。FE7/FE8 在这些字段相同不意味着尾部所有字段、状态位或技能系统相同。

`gPlaySt +0x1B` 在 FE7 为 Lyn=1、Eliwood=2、Hector=3。上游结构的一条行内注释保留了 Eirika/Ephraim 字样，但同文件实际枚举为 FE7 三条路线；适配使用枚举和游戏本身语义。FE7 后续区域包含军师、评价等字段，不整体套用 FE8 `PlaySt`。本阶段只读取已核对的最小字段，不以 FEBuilder 的块长度推断完整结构大小。

Proc 头部：script `+0`、cursor `+4`、idle callback `+0x0C`、parent `+0x14`、child `+0x18`、next `+0x1C`、prev `+0x20`、s16 sleep `+0x24`、flags `+0x27`、lock `+0x28`。从 8 个根按 child/prev 遍历，并验证范围、对齐、循环和 parent；扫描池中的残留脚本地址不能证明该 Proc 活跃。[Proc 结构](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/include/gbafe/proc.h)

## 场景与 HP 研究编辑候选

| 用途 | 脚本／地址 | 实际 ROM 证据 |
| --- | --- | --- |
| 地图渲染 MAPTASK | `0x08C05464` | 指针偏移 `0x2E644` |
| BmMain | `0x08C01DBC` | `0x2E874` |
| PlayerPhase | `0x08C02630` | `0x21A08` |
| 玩家空闲 repeat 命令 | `0x08C02698` | opcode 3；callback `0x0801C625` |
| 玩家空闲预期 live cursor | `0x08C026A0` | repeat 命令后的指令位置，待快照确认 |
| 移动选择 repeat 命令 | `0x08C026D8` | callback `0x0801CACD`，预期 cursor `0x08C026E0` |
| fade | `0x08C028E8` | `0x1DA34` |
| 统一事件引擎 | `0x08BFFF78` | `0xAED8` |
| 对话背景 | `0x08BFFE90` | `0xAD1C` |
| 对话 fade／skip fade | `0x08BFFEF8` / `0x08BFFF58` | 事件源数据和实际脚本字节 |

FE7 此路径使用统一事件 Proc，不把 FE8 的 MapEvent/BattleEvent 两个脚本地址直接替换使用。以上事件、对话和 fade 脚本可先作为保守阻止条件；不能宣称这一集合已覆盖所有 FE7 动画／菜单／教程场景。[事件数据](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/data/data_event.s)、[事件引擎](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/asm/event-engine.s)

`BmMain_StartPhase` 使用 `Proc_StartBlocking(ProcScr_PlayerPhase, proc)` 后结束自身 repeat；因此玩家空闲时预期 BmMain 的 lock 为 1，PlayerPhase 是其阻塞子节点且自身 lock=0、sleep=0、child=0，MAPTASK 独立运行。这个候选合同已由 R02/R06 自身序章快照分别确认，并完成实验 HP 编辑／恢复，实际节点、状态 SHA 与测试范围见 [M2 验证记录](M2_VALIDATION.md)。还需同时核对玩家回合、战局锁／显示锁、flags、单位有效性、当前 HP 和请求版本；仅匹配 callback 不足以允许写入。[BmMain 源码](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/src/bm.c)、[PlayerPhase 源码](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/src/player-phase.c)

## 准备画面合法交换候选

已从 R02/R06 实际字节及源汇编确认：

- `ApplySwap` 位于 `0x080948A0`，长 `0x2C`。它交换两个 `unit + 0x1E + slot * 2` 的完整 u16 物品值，再分别调用 `UnitRemoveInvalidItems`（`0x08017A78`）压紧空槽。不能只交换物品 ID 而丢弃耐久。
- `StartPrepItemTradeScreenProc` 位于 `0x080951D8`，将两个参与单位指针写入 Proc `+0x2C/+0x30`，以 blocking 子节点创建；`+0x40` 初始化为 -1。
- 准备交换脚本为 `0x08D8CF74`（指针偏移 `0x951F8`），idle repeat 命令 `0x08D8CF94`，callback `0x08094EC1`，预期 live cursor `0x08D8CF9C`。进入／退出和 fade 阶段不能按 idle 处理。

上述信息足以定义准备交换适配候选，但没有 FE7 真实准备交换样本时，不代表命令已通过合法性、界面刷新、保存和恢复验收。[交换汇编](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/asm/prep_itemtrade.s)、[准备脚本](https://github.com/MokhaLeee/FireEmblem7J/blob/d0697e208b6fa03a81e4968a54f4b8ddc5f3925b/data/prepscreen.s)

## 字节一致性与证据边界

2026-11-01 的只读脚本比较发现以下选定区间在 R02/R06 中 SHA-256 相同；完整指纹保留在本地 JSON：

| ROM 区间起点 | 长度 | 内容 |
| --- | ---: | --- |
| `0x948A0` | `0x2C` | 准备交换函数 |
| `0x951D8` | `0x24` | 准备交换启动函数及字面量 |
| `0xD8CF74` | `0x48` | 准备交换脚本 |
| `0x17A78` | `0x54` | 单位空物品槽整理 |
| `0x1C624` | `0x200` | PlayerPhase_MainIdle 的前缀，非完整函数证明 |
| `0xC02630` | `0x70` | PlayerPhase 脚本至 main idle |
| `0xC01DBC` | `0x100` | BmMain 及其后脚本区间 |
| `0xBFFF78` | `0xA0` | 事件脚本区间 |
| `0x2EC3C` | `0x20` | 仓库计数函数及字面量 |

研究脚本真实运行已通过静态读取和样本名称解码。补充的运行验证已覆盖 R02/R06 自身的标题、对话、教学阻塞、实际移动选择、玩家空闲及 HP 编辑恢复；各3项 native 集成通过，未从代码区间相同直接继承能力。准备交换和仓库存取、中文名称映射、其他章节／路线／NPC／回合切换及 Thor 新版持续表现仍未通过。详细边界见 [M2 验证记录](M2_VALIDATION.md)，本阶段尚未关闭。
