# FE8J：M1 地址、结构与背包操作证据

本文件记录对登记目标 R03、R04 的只读调查。地址均为 GBA 总线地址；明确写成“ROM 偏移”的数值才是文件内偏移。静态地址核对、解码样本、真实运行快照和操作验收是不同证据层级，不能互相替代。

## 目标与复现

| 目标 | SHA-256 | 字节数 |
| --- | --- | ---: |
| R03，FE8 日版 | `44fd343625ab9e6b90f63a80758c15066d526e6873fae91474006314a5ead464` | 16777216 |
| R04，2.0 中文语音版 | `4853c904c1c180f837678f3d0032dbbb86049bc710fb1993fb51bad8c49b1076` | 17928212 |

`scripts/research_fe8j.py` 只接受这两个完整指纹，读取 `.local/targets.json` 中的原始文件，不修改 ROM、存档或设备。运行：

```powershell
python scripts/research_fe8j.py --registry .local/targets.json --state .local/m1/thor/fe8-cn-main.state --output .local/m1/fe8j-evidence.json
```

输出仅写入忽略的 `.local/`。报告包含指针、短名称样本、代码区间指纹和快照语义摘要；ROM 和完整 RAM 不进入版本库。

一手代码来源已固定版本：

- FEBuilderGBA：[`8e3557912ddab708d11bf85ad311b41bb6630fd1`](https://github.com/FEBuilderGBA/FEBuilderGBA/tree/8e3557912ddab708d11bf85ad311b41bb6630fd1)。其 [`ROMFE8JP.cs`](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/ROMFE8JP.cs) 和 [`asmmap_FE8.en.txt`](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/bin/Debug/config/data/asmmap_FE8.en.txt) 的 `{J}` 条目提供日版符号候选，再对本地两个目标读取验证。
- FireEmblemUniverse/fireemblem8u：[`04d1edb163d14bd10777a065697e1a9f17be65f2`](https://github.com/FireEmblemUniverse/fireemblem8u/tree/04d1edb163d14bd10777a065697e1a9f17be65f2)。美版反编译用于字段和行为的交叉解释，**不把美版地址直接用于日版**。
- REA `open_binary` 在当前 Windows provider 组合下拒绝 raw ARM GBA ROM，没有生成反编译会话。后续使用上述源代码、实际文件字节和局部安装的 Capstone 5.0.9 ARM Thumb 反汇编；不得把结果描述为 REA/Ghidra 已分析。

## 已核对的静态布局

R03/R04 下列 ROM 指针字面量相同；仍分别以完整 SHA 选择 profile。

| 数据 | 总线地址 / 值 | ROM 偏移处的指针证据 |
| --- | --- | --- |
| 人物表 ID **1** | `0x088582BC`，步长 `0x34` | `0x10650`、`0x10268` |
| 职业表 ID **0** | `0x0885B668`，步长 `0x54` | `0x17860` |
| 物品表 ID **0** | `0x0885E068`，步长 `0x24` | `0x17568`、`0x17554` |
| 文本指针表 | `0x0814D08C`，每项 4 字节 | `0xA000` |
| 仓库数组 | `0x0203A818`，100 个 u16 | `0x31470`；计数/整理代码含 100 上界 |
| 地图渲染 MAPTASK Proc 脚本 | `0x085C5DE8` | `0x30F24` |
| 准备画面交换 Proc 脚本 | `0x08A94D60` | `0x9E120` |

人物表的起始项区别很重要：读取 ID 1 是表基址本身，ID n 的地址为 `base + (n - 1) * 0x34`。人物的实际 RAM 记录保存人物/职业记录指针，优先校验指针与记录中的 number，再解析。职业、物品的 ID 1 则分别是 `base + 0x54`、`base + 0x24`。[FEBuilder 人物表实现](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/UnitForm.cs#L45)、[职业表实现](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/ClassForm.cs#L51)

| RAM 数据 | FE8J 地址 | 合同 |
| --- | --- | --- |
| `gBmSt` / `gGameState` | `0x0202BCAC` | `+0x14/+0x16`：s16 当前地图光标 x/y |
| `gPlaySt` / chapter data | `0x0202BCEC` | 大小 `0x4C`，详见下文 |
| 玩家单位数组 | `0x0202BE48` | 62 项，步长 `0x48`，deployment index `0x01..0x3E` |
| 敌军单位数组 | `0x0202CFB8` | 50 项，步长 `0x48`，index `0x81..0xB2` |
| NPC 单位数组 | `0x0202DDC8` | 20 项，步长 `0x48`，index `0x41..0x54` |
| 当前操作单位指针 | `0x03004DF0` | u32 指针；0 表示无，不等同光标单位 |
| 当前操作单位 index | `0x0202BE40` | 不替代 unit 指针和 index 一致性校验 |
| 地图尺寸 | `0x0202E4D0` | 两个 s16，宽、高；0 不代表有效地图 |
| 单位 / 地形行指针数组的指针 | `0x0202E4D4` / `0x0202E4D8` | 先读 u32，再读第 y 行指针，再读 x 的 u8 |
| 移动 / 射程图的指针 | `0x0202E4DC` / `0x0202E4E0` | 引擎工作缓冲，不是永远有效的任意单位范围 |
| 迷雾 / hidden / 移动 2 的指针 | `0x0202E4E4` / `0x0202E4E8` / `0x0202E4EC` | 需要场景和缓冲用途验证 |
| Proc 池 | `0x02024E68` | 64 项，每项 `0x6C` |
| Proc forest roots | `0x02026A70` | 8 个 u32 根指针 |
| 战斗 actor / target | `0x0203A4E8` / `0x0203A568` | 存在不代表当前战斗预测有效 |
| ActionData | `0x0203A954` | `+0x0C` subject，`+0x0D` target，`+0x11` actionType |

RAM 地址来自日版配置及 `{J}` 符号。脚本进一步查到实际 ROM 代码区中的地址字面量，例如 `gBmSt` 出现在 ROM `0x0090E8`，`gPlaySt` 出现在 `0x002410`，玩家数组在 `0x031694`，mapSize 在 `0x015840`，active unit 在 `0x0077C0`，Proc 池及 roots 在 `0x002BBC/0x002BC8`。这些是静态交叉证据；单个字面量出现本身不证明任意时刻 RAM 内容有效。[日版配置](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/ROMFE8JP.cs#L270)

### PlaySt 与单位字段

| PlaySt 偏移 | 类型 | 含义 |
| --- | --- | --- |
| `+08` | u32 | 金钱 |
| `+0C` | u8 | 存档槽 |
| `+0D` | u8 | 迷雾视野 |
| `+0E` | u8 | 章节 index，内部编号并非显示章节数 |
| `+0F` | u8 | phase：玩家 `0`，NPC `0x40`，敌军 `0x80` |
| `+10` | u16 | 回合 |
| `+12/+13` | u8/u8 | 保存/菜单用光标；实时详情跟随用 BmSt |
| `+14` | u8 | chapter state；`0x10` 准备、`0x40` hard 候选标志 |
| `+15` | u8 | 天气 |
| `+1B` | u8 | route/mode：1 序章至分支前、2 Eirika、3 Ephraim |
| `+40..43` | 4 bytes | 配置 |

不能仅凭 `+14 & 0x10` 宣称正处于可写准备界面。需结合活动 Proc、动画/对话状态与选定功能前置条件。[PlaySt 字段依据](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/EmulatorMemoryUtil.cs#L786)

| Unit 偏移 | 类型 | 含义 |
| --- | --- | --- |
| `00/04` | u32/u32 | 人物记录 / 职业记录指针 |
| `08/09/0A/0B` | s8/u8/u8/u8 | 等级、经验、AI flags、deployment index |
| `0C` | u32 | 状态位 |
| `10/11` | s8/s8 | x/y；负值允许表示不在地图 |
| `12/13` | s8/s8 | 最大 / 当前 HP |
| `14..19` | s8[6] | 力或魔力、技、速、防、魔防、幸运 |
| `1A/1B/1C/1D` | s8/u8/u8/s8 | 体格加成、救出 index、弩车 index、移动加成 |
| `1E..27` | u16[5] | 5 物品槽：低字节 ID，高字节耐久 |
| `28..2F` | u8[8] | 八类武器熟练度 |
| `30` | u8 | 低四位状态 ID，高四位剩余回合 |
| `31` | u8 | 低四位火把、高四位圣水剩余回合 |

`state` 低位：hidden `1`，unselectable/已行动 `2`，dead `4`，not deployed `8`，rescuing `0x10`，rescued `0x20`，cantoing `0x40`，under roof `0x80`。FEBuilder 的 `CountUnits` 局部代码把 dead 写为 2，与其符号表和反编译结构冲突，不能照抄；采用三者交叉核对后的 `dead=4`。[单位结构与状态枚举](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/include/bmunit.h#L132)

### ROM 记录字段

人物 `0x34` 字节：nameId `u16+00`，descId `u16+02`，number `u8+04`，defaultClass `+05`，portrait `u16+06`，基础等级 `s8+0B`，基础 HP/力/技/速/防/魔防/运/体格 `s8+0C..13`，武器熟练 `u8[8]+14`，成长 HP/力/技/速/防/魔防/运 `u8+1C..22`，attributes `u32+28`，support ptr `+2C`。

职业 `0x54` 字节：nameId `u16+00`，number `u8+04`，promotion `+05`，base HP/力/技/速/防/魔防/体格/移动 `s8+0B..12`，上限 HP/力/技/速/防/魔防/体格 `s8+13..19`，成长 `s8+1B..21`，转职加成 `u8+22..27`，attributes `u32+28`，武器熟练 `u8[8]+2C`，三天气移动消耗表指针 `u32[3]+38`，地形回避/防御/魔防指针 `+44/+48/+4C`。[人物与职业结构](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/include/bmunit.h#L17)

物品 `0x24` 字节：nameId/descId/useDescId `u16+00/+02/+04`，number `u8+06`，weaponType `+07`，attributes `u32+08`，statBonus/effectiveness ptr `+0C/+10`，maxUses/might/hit/weight/crit/range `u8+14..19`，costPerUse `u16+1A`，weaponRank/icon/useEffect/weaponEffect/weaponExp `u8+1C..20`。表末尾 padding 或补丁字段不得随意解释。[物品结构](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/include/bmitem.h#L20)

## 文本：R03 与 R04 必须分开

| 项目 | R03 | R04 |
| --- | --- | --- |
| ROM `0x6E0` 指向的 Huffman tree base | `0x0814929C` | `0x08147DD4` |
| ROM `0x6DC` 的值 | `0x0814D088` | `0x0814D088` |
| 上述指针二次间接得到 root | `0x0814D084` | `0x0814D084` |
| 文本表 | `0x0814D08C` | `0x0814D08C` |
| 人物 1、文本 `0x199` 解压字节 | `83478343838a815b834e` | `83a88ecc95fa` |
| 物品 1、文本 `0x2DB` 解压字节 | `82c482c282cc8c95` | `90c289bb` |

R03 样本可按 CP932 解为「エイリーク」「てつの剣」。R04 的解压字节具有自定义汉字字库映射，按 CP932 或 GBK 解码不能可靠得到中文姓名；M1 应显示 name ID/未知字库，或使用后续验证的映射包。**不能因为人物 ID 与原版相同，就把原版日文名称当作读取到了汉化名称。**

Huffman 算法依据 [`FETextDecode.cs`](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/FETextDecode.cs#L327)：逐字节 LSB first；`next = base + u16(node + bit*2)*4`；node 的 u32 最高位为 1 时是叶子，低 16 位按低字节再高字节输出（高字节 0 不输出），低字节 0 表示终止，叶子后回 root。实现应限制输入、输出与节点读取范围。对话控制码与汉字映射需要独立处理；名称样本成功不代表整段剧情已能呈现。反 Huffman 指针分支不在本次样本已验证范围内。

## 活动 Proc 与场景判定

Proc：`+00 script`、`+04 scriptCursor`、`+08 endCb`、`+0C idleCb`、`+10 name`、`+14 parent`、`+18 child`、`+1C next`、`+20 prev`、`+24 s16 sleep`、`+26 mark`、`+27 flags`、`+28 lockCount`。根/child 指针指向最新节点，应沿 `+20 prev` 访问兄弟；仅沿 `+1C` 会漏掉节点。[Proc 结构](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/include/proc.h#L51)、[FEBuilder 实际树遍历](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/EmulatorMemoryForm.cs#L500)

保守读模型：从 8 个 roots 出发，只接受池内、`0x6C` 对齐、root 可达的节点；去重并限制总数 64；检查脚本指针在 ROM 内；地图场景还要有 maptask、有效地图尺寸/行指针和合法单位记录。普通池扫描或旧地图 RAM 非零不能单独证明正在战场。parent/child/prev 的回链不一致、场景切换中的快照应降低能力，不使用旧结果继续写入。

`0x085C5DE8` 是章节渲染 MAPTASK；它与游戏逻辑主 Proc `E_BMAPMAIN=0x085C2670` 是不同对象。`0x08A94D60` 只表示准备交换子画面，不能泛化成所有准备页面。战斗 actor/target、移动和射程工作缓冲也有生命周期，尚未获得有效生命周期证据前保留为未知/研究字段。

### 当前真实 Thor 快照的负例

`.local/m1/thor/fe8-cn-main.state` SHA256 `dc55b8c1becc3c2f7af66a4173433ca11b3b8b2f34afad85eed518dfe38a01e5`，430224 字节：ELSTATE2 头 128，mGBA raw 397312，battery 32768，RTC 16。raw 中 IWRAM 偏移 `0x19000..0x21000`，EWRAM `0x21000..0x61000`。

该真实快照的地图 0×0、turn 0、所有单位为空、activeUnit 0。root 3 指向 GAME_MAIN `0x085B946C`；子树还包含 `0x08B3F024`（idle `0x080CB2A1`）和 `0x08B3EDD0`（idle `0x080CA1F9`），没有 maptask。它支持“当前无有效战场”的门控负例，**不能作为战场人物解析或背包写入的正例**；这两个子脚本尚未逐项命名。

## 准备交换与仓库：静态合同

### 已定位的日版交换函数

| 项目 | 地址 | 核验 |
| --- | --- | --- |
| `PrepItemTrade_ApplyItemSwap` | `0x0809D800`，0x2C 字节 | 实际 Thumb：按 `unit+0x1E+slot*2` 交换 u16，再对两单位压紧 |
| `UnitRemoveInvalidItems` / RemoveUnitBlankItems | `0x0801772C`，0x54 字节 | 实际 Thumb：保留 5 槽中非 0 项顺序，尾部补 0 |
| `StartPrepItemTradeScreenProc` | `0x0809E100` | 实际调用 Proc_StartBlocking 并设置两单位 |
| 交换 Proc | `0x08A94D60`，0x48 字节 | ROM `0x9E120` 引用 |
| 交换 idle | `0x0809DDF8`，Thumb 指针 `0x0809DDF9` | 脚本 `0x08A94D80` repeat |

R03/R04 上述每个区间字节完全相同。交换函数区间 SHA256 为 `750f2be61e8da2c4838bda3eb2b1c9697191dd855861f100ff890c3533e28faa`。Start 与脚本关联、参数写入、交换与压紧的实际指令分别匹配 [美版 `prep_itemtrade.c` 的行为](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/prep_itemtrade.c#L21)；地址是从日版实际反汇编定位，而非统一偏移推算。

交换 Proc 数据：`+2C/+30` 两个 unit 指针，`+34` cursorItemSlot，`+38` selectedItemSlot，`+3C` helpBoxItemSlot，`+40` initialRightItemSlot；这些是 32 位字段。`slot >> 3` 选择左右单位，`slot & 7` 选择物品槽；无选择/无帮助为 `0xFF`。首次 A 选物品，第二次 A 执行交换，B 回到上一状态。空位与光标移动由游戏处理；交换后空物品被压紧，不能假定原槽索引保持不变。[原游戏确认和刷新流程](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/prep_itemtrade.c#L300)

首个合法写入闭环可以优先限定为“游戏已进入这个准备交换子画面，交由原游戏输入执行”。每步重新核验活动 Proc、idle、两 unit、选择状态、分支/快照 revision；遇到 help、fade、场景变化或实体按键竞争即取消。若改为执行等价内存事务，需要同时证明被选单位属于游戏允许名单、槽内容未变、交换后压紧、UI 刷新和派生状态一致，不能仅修改两个物品值就称完成原作操作。

源代码交换前还调用 `CheckValidLinkArenaItemSwap`，联机竞技场有额外武器约束。本次不把该模式视为普通准备画面；未适配模式应拒绝正常交换。普通准备交换动作不检验物品是否可装备，因此不能给原作增加“只允许交换可装备物品”的限制。

### 仓库和战场操作的不同前置条件

日版 `GetConvoyItemArray=0x0803144C`，clear `0x08031454`，compact `0x08031478`，count `0x080314BC`，add `0x080314E0`，find slot `0x08031530`，`HasConvoyAccess=0x08031568`。R03/R04 `0x3144C..0x315AC` 字节相同。实际 `HasConvoyAccess` 指令与原作源码相符：世界地图时查询 next uncleared chapter；非世界地图按当前章节；序章/第 1 章、内部 `0x38`、以及非世界地图 `0x05` 不可用。不能只因仓库 RAM 有数据就显示正常取放可用。[仓库访问代码](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmcontainer.c#L98)

添加物品要检查 100 个槽上限；取出后保持紧密排列；失败添加会设置 BmSt 临时字段。准备仓库菜单的完整 Proc/刷新合同尚未定位完，单独调用 add/remove 并不等同完整菜单动作。

战场交换不是准备交换：原作检查 canto/已交换、supply 属性和合法目标列表；目标有阵营、相邻关系、幻影职业、狂乱、背包非空、救出关系等条件。战场补给另检查仓库访问、幻影职业和主角自身/相邻主角，不允许用准备画面权限执行跨地图转移。[战场交换](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmmenu.c#L664)、[交换目标列表](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmtarget.c#L204)、[战场补给](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmmenu.c#L1609)

## 研究 HP 写入的保守稳定时机

以下是供宿主实现事务前置校验的证据合同，本调查没有执行任何写入。限制为当前 HP `1..maxHP`，不包括置零死亡、复活、修改最大 HP、战斗中改血或剧情状态修复。宿主仍需在暂停的同一快照边界重读并比较单位原始数据、实验分支与 revision，再执行和回读验证；不能拿 UI 几帧前的“可编辑”状态直接写。

已从两个原始 ROM 核对：

| 对象 | 地址 / 条件 | 证据 |
| --- | --- | --- |
| BmSt game lock | `0x0202BCAD` 为 0 | 日版 `LockGame=0x08015384`、Unlock `0x08015394`、Get `0x080153A4` 直接增减/读取 BmSt+1；R03/R04 代码相同 |
| BmSt graphics semaphore | `0x0202BCAE` 为 0 | 原作结构 `+02`；初次门控从严 |
| BmSt gameStateBits | `0x0202BCB0` 为 0 | 当前空闲实测为 0、移动选择实测为 3；第一版从严，不猜测未知位组合 |
| 玩家回合逻辑 Proc | script `0x085C2F58` | ROM `0x1549C`、`0x154E8` 的实际字面量 |
| 玩家输入空闲 callback | `0x0801C5A9` | `PlayerPhase_MainIdle`；repeat 命令在 `0x085C2FD0` |
| 玩家空闲 script cursor | `0x085C2FD8` | repeat 设置 callback 后将 cursor 增加 8；实际快照一致 |
| 玩家 Proc | `lock=0`、`sleep=0`，parent 为有效 BMapMain | 必须是 root 可达并有正确父子关系的实例 |
| BMapMain | script `0x085C2670`、`lock=1` | 玩家阶段是其 blocking child；已在真实空闲样本证实 |
| MAPTASK 渲染 Proc | script `0x085C5DE8`、`lock=0` | 辅助条件，不代替玩家阶段逻辑 |
| 事件引擎 | `0x085B98A0`、`0x085B98D8` 均不存在 | 来自日版 MapEventEngineExists / BattleEventEngineExists，ROM `0xD470/0xD488` |
| 地图混合过渡 | `0x085C3248` 不存在 | 日版 DoesBMXFADEExist `0x0801DA74` 实际引用 |

还必须满足有效地图/玩家 phase、合法单位指针/number/index、当前 HP 与 maxHP 合理、单位不为 dead/not-deployed/rescued 等既有结构校验。`BmSt+00 main_loop_ended=1` 和玩家 Proc `flags=2` 在正常空闲样本中都是正常值，不能误设为必须 0。玩家正常空闲时**逻辑父 BMapMain 的 lock 为 1**，不能把它和渲染 MAPTASK 的 lock=0 混淆。

菜单的基本约束也与上述 guard 相容：有 parent 的菜单使用 blocking child，让父 Proc 暂停；无 parent 的菜单调用 LockGame。玩家 `MainIdle` 打开地图菜单后还会跳回 label 9，故 callback/cursor 不应继续被当作当前可执行闲置动作。[菜单创建](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/uimenu.c#L188)、[玩家主循环](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/playerphase.c#L253)、[repeat 指令语义](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/proc.c#L498)

**R04 差异**：`MainIdle` 大部分代码相同，但选中可操作单位路径的 ROM `0x1C710..0x1C717` 被改为跳转到 Thumb `0x08BC3B1D`。不能宣称整个 MainIdle 两 ROM 相同；当前 guard 使用的 script、idle 入口和 cursor 已分别核对，R04 的实际空闲状态另有下列正例。该 hook 的完整语音行为不在此次证明范围内。

### 从原作输入获得的真实对照样本

以下由宿主在隔离 probe 中从标题进入新游戏、通过原作输入获得；没有下载他人存档，也没有通过内存制造章节或单位。报告在 `.local/m1/probes/r04-XX-evidence.json`，图像与原始恢复点同目录。

| 样本 | SHA256 | 观测与 guard 结论 |
| --- | --- | --- |
| `r04-02`，菜单 | `6484540b630bed469e3ac6ca8484f866ecff8de9fdcae973b8287f31d0fb418b` | 没有 MAPTASK、BMapMain 或玩家阶段；拒绝 |
| `r04-07`，开场对话 | `032b3b635c280c8d0fcd3a73b3f551e34576935405eeea78dc9bc76f92f69c79` | 地图 15×10、章 0、phase 0x40、gameLock=1，MapEvent 存在，无玩家阶段；拒绝 |
| `r04-08`，移动范围选择 | `afe82e026e6c09bcdf85e2a082dd89386bca2b2de28bf83ef7d7997ed1e5b716` | 玩家 idle `0x0801C985`、cursor `0x085C3020`，Bm flags=3；拒绝 |
| `r04-09`，取消选择后的玩家空闲 | `5bc57cf4a564b9f2b7056c2dd8d7179958fd86736223165587f9e8c791f045d8` | 玩家 idle `0x0801C5A9`、cursor `0x085C2FD8`、lock/sleep=0；parent `0x02025084` 是 BMapMain、lock=1；Bm lock/gfx/flags=0；符合稳定时机候选 |

`r04-09.png` 画面显示主角 HP 16/16。RAM 中玩家 index1 的人物 ID2（Seth）HP13/30，index2 的人物 ID1（Eirika）HP16/16；敌军 index0x81..0x83 三名，说明人物编号与 deployment index 不同，也实际验证了玩家/敌军数组步长和 number 指针关系。NPC 数组在该章节为空，不能据此宣称 NPC 场内对照完成。

同一快照中光标 `(4,5)`，mapUnit 行指针数组为 `0x0202E4F8`，第 0 行 `0x0202E54A`、第 1 行 `0x0202E55B`，实际行跨度 17 而可见地图宽度 15；必须逐行解引用，不能按 width 平铺。`mapUnit[5][4]=2` 与 Eirika `(4,5)` 一致；Seth `(4,4)` 的 index1、敌军 `(10,8)/(8,6)/(9,6)` 的 index0x81/82/83 也吻合。地形行指针数组为 `0x0202ECB0`，第 0 行 `0x0202ED02`。

这四个样本证明 guard 对当前原作输入路径的区分效果，不代替战斗动画、道具使用、读档瞬间、事件中断、准备菜单和实体输入竞争的专项验收。R03 目前为静态证据，不能标成已实机通过。

## 正常新游戏到首次准备画面的路径

在锁定的原作事件源码中，最早的准备调用位于第 4 章开场 `CALL(EventScr_08591FD8)`；被调脚本顺序为清理、`PREP`、清理、结束。因此从正常新游戏获得准备画面，需要经过序章、第 1、2、3 章，再让第 4 章开场事件运行到此处。不是在序章开启某个菜单即可获得完整准备系统。[第 4 章开场](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/events/ch4-eventscript.h#L96)、[共用 PREP 事件](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/eventscr.c#L3988)

用于获取合法测试进度的路线只需要满足胜利条件：序章击败 BOSS（事件是 `DefeatBoss`，不要求全灭），第 1 章制压 `(2,2)`，第 2 章全灭敌军，第 3 章制压 `(14,1)`。村庄、宝箱、可选对话不是这些章节胜利事件的必要条件；允许在这个专用测试进度跳过，不用于用户的主游玩分支。关闭战斗动画、使用游戏允许的剧情跳过可节省观察时间，但具体行动不能按假定命中或假定随机数批量承诺成功，应逐步核对真实结果。这是事件路径调查，尚未运行到第 4 章，不是经过验证的最短回合攻略。[序章目标](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/events/prologue-eventinfo.h#L30)、[第 1 章目标](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/events/ch1-eventinfo.h#L28)、[第 2 章目标](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/events/ch2-eventinfo.h#L36)、[第 3 章目标](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/events/ch3-eventinfo.h#L25)

## 本次证据的边界与后续验收

- 已完成：两个精确 ROM 指纹、主要布局静态指针、字段交叉来源、原版名称短样本、汉化文本差异、准备交换函数/Proc/压紧逻辑、仓库访问代码的原始字节核对；真实非战场快照负例和 R04 原作输入得到的对话/移动选择/玩家空闲对照。
- 待运行验证：NPC 场内对照、完整背包 UI 对照、光标跟随、地图 row pointers、章切换/读档时门控；HP 事务执行及恢复；准备交换成功/取消/空位/满背包/同单位整理，输入竞争，分支往返与游戏保存。
- 待专门适配：R04 中文 glyph mapping；完整准备菜单与仓库 UI 的 Proc/刷新规则；装备、使用、战场交换/补给的完整命令合同；移动/射程/战斗预测缓冲生命周期。
- 地址与局部代码相同只允许复用相应字段/算法合同，不推出两个 ROM 整体行为完全一致。R04 必须有自己的 profile 与通过/未通过能力集合。

以上未执行任何游戏状态修改。真正的写入能力只能在宿主确认边界、实验分支、前置条件和运行验收完成后启用。
