# 技术依据与适配边界

核验记录日期：2026-11-01（Asia/Shanghai）。本文整理规划期间已经查阅的一手代码与发布说明，不代表 EmberLily 已实现这些功能，也不代表已运行或验证任何目标 ROM。本轮未下载 ROM。外部源码链接指向所列分支；实际适配时仍须固定源码提交与目标 ROM 指纹。

## 1. ROM 资料与 RAM 战局是两类输入

**已核实事实：**FE8U 的 `CharacterData`、`ClassData` 包含名称文本 ID、头像、基础能力、成长率、属性、移动消耗及地形修正表；运行中的 `Unit` 则保存角色/职业数据指针、等级、经验、坐标、当前能力、物品、武器熟练度、异常状态、救出关系和 AI 字段。标准 FE8U 的 `Unit` 长度为 `0x48`，单位物品有 5 格。

依据：[FE8U bmunit.h](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/include/bmunit.h)。

**对实现的约束：**静态资料应从当前 ROM 解析，动态状态从一致的模拟器快照解析，再组合为界面模型。不能将原版角色数据库当作所有改版的真值，也不能把 RAM 原始能力直接当作装备、支援、状态和技能修正后的最终能力。

## 2. 战局结构可读，但数据需要上下文

**已核实事实：**FE8U 有独立的单位、地形、移动、射程、迷雾、隐藏信息二维地图指针；`PlaySt` 包含章节、回合、阵营阶段、天气、视野及准备画面等状态；`BmSt` 包含镜头与光标。

依据：[bmmap.h](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/include/bmmap.h)、[types.h](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/include/types.h)。

**对实现的约束：**当前移动/射程缓冲区不自动等于全体敌军危险区域。读取需要辨别战术地图、准备、菜单、对话、战斗和转场；读档、回退、换 ROM、换章节后必须使外部缓存与历史失效或重新同步。RAM 中存在敌人信息，只证明可读取，展示范围属于产品策略。

## 3. 当前战斗预测与自由预测是不同能力

**已核实事实：**FE8U 的 `gBattleActor`、`gBattleTarget`、`BattleStats` 包含武器、地形、攻击、防御、速度、命中、回避、暴击和反击状态等；代码区分真实战斗与模拟战斗，存在 `BattleGenerateSimulation`、`BattleGenerateReal` 及不同战斗配置。原版还存在贯通、大盾、瞬杀、恶魔、吸血、三角攻击等专门处理。

依据：[bmbattle.h](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/include/bmbattle.h)。

**对实现的约束：**镜像游戏已生成的当前预测，和为任意单位、目标、位置、武器计算预测，应分别标记支持情况。战斗缓冲区必须绑定当前预测上下文，离开上下文后不能继续显示为有效结果。函数名中的 Simulation 不证明调用无副作用；未来若调用 ROM 逻辑，须验证全局战斗状态、随机数和其他运行状态的影响。

**改版差异的直接证据：**C-SkillSys 文档列出了力量/魔力分离、攻速公式改动、技能修正暴击与特效倍率、真实伤害、包围效果、距离命中惩罚、联动攻击、扣 HP 法术及仓库扩容。传统 SkillSystem_FE8 也提供力量/魔力分离、存档扩展等可选配置。

依据：[C-SkillSys Abstract](https://github.com/FireEmblemUniverse/fe8u-cskillsys/blob/main/docs/Abstract.md)、[SkillSystem_FE8 README](https://github.com/FireEmblemUniverse/SkillSystem_FE8/blob/master/README.md)。

因此，“基础单位面板可读”“技能可读”“威胁范围可信”“战斗预测可信”不能合并成一个兼容性开关。

## 4. 事件解析能生成情报，不能保证生成完整攻略

**已核实事实：**FE8U 的章节事件组分别指向回合、角色、地点、杂项、选中、移动后、开场和结尾事件。标准事件检查可读取回合区间与阵营阶段、对话双方、宝箱坐标及物品/金币，并结合事件 flag 判断是否已经触发。

但事件脚本支持分支、随机数、slot 运算、脚本调用和 `ASMC` 原生函数调用。`LOADUNIT` 的单位定义既可以由常量指针提供，也可以来自运行时 slot。`CheckWin()` 检查的是胜利 flag；章节目标文字并不等于完整的胜利判定逻辑。

依据：[chapterdata.h](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/include/chapterdata.h)、[eventinfo.c](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/eventinfo.c)、[eventscr.c](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/eventscr.c)。

| 情报 | 可以提取的结构 | 不能直接推定的结论 |
| --- | --- | --- |
| 增援 | 回合事件、触发条件、加载单位的脚本和定义 | 每个回合事件都是增援；该批单位在未来一定出现 |
| 招募 | 对话双方、前置 flag、当前阵营 | 存在对话就一定能招募；对话表已经涵盖所有条件 |
| 宝箱、村庄 | 标准宝箱奖励、位置及开启 flag；地点脚本入口 | 任意地点脚本都使用固定奖励 |
| 胜利与分支 | 目标文本、部分目标字段、事件与 flag | 文本就是实际判定；未解析的原生回调没有额外条件 |

**对实现的约束：**自动情报应保留来源与条件，并区分已确认、条件成立时、未解析。路线建议、培养建议和自定义事件语义可以由版本化资料补全；未知分支不能被摘要补写成确定事实。

## 5. FE8-UI 提供同类架构先例

**已核实事实：**FE8-UI 使用 libmGBA 保留 ROM 执行，扩展 FE8 地图渲染、鼠标操作和军队物品管理。核验时 README 列出 Windows x64、Linux x86-64、Mac ARM 构建，以及 Sacred Stones、Sacred Echoes、Pokemblem、Archanae、Cerulean Crescent 的测试记录；同时明确说明改版通常需要 profile 才能完整支持。

其物品编辑器允许超出原游戏时机限制的交换，包括战斗期间。其 Pokémblem 适配文档还记录：基本地图结构保留时，运行时调色板布局仍可能变化，需要独立探测、缓存失效与降级。

依据：[FE8-UI README](https://github.com/stephenjayakar/FE8-UI/blob/main/README.md)、[改版兼容性说明](https://github.com/stephenjayakar/FE8-UI/blob/main/docs/romhack-compatibility.md)。

**证据边界：**这是架构可行性的先例，不是 EmberLily 已验证结果，也不证明其桌面实现可直接移植 Android，或能适配 FE7《天地之剑》。

## 6. 《天地之剑》属于 FE7 系，中文构建尚待确认

**已核实事实：**原作者 38% 在 2013-04-14 的制作博客中说明，原本打算以《封印之剑》制作，后来转到《烈火之剑》；同一归档页讨论琳篇、艾利乌德/海克托尔篇与军师选项。原作者另有 v1.0、v1.2 发布记录。公开日文补丁条目标注“FE7 天地の剣 v1.22 資料付き”。

依据：[原作者早期制作记录](https://38.doorblog.jp/?p=17)、[v1.0 发布](https://38.doorblog.jp/archives/46126604.html)、[v1.2 发布](https://38.doorblog.jp/archives/48025219.html)、[v1.22 补丁与资料条目](https://uu.getuploader.com/FE4/download/191)。本次仅核验发布页面，未下载补丁包，未核验包内资料。

英文译者 Nasaelo 的发布帖明确标为 FE7，署原作者 38%，并要求以 FE7 US ROM 作为 UPS 补丁输入。**补丁输入版本不自动证明补丁结果保留 FE7U 的全部运行时布局。**

依据：[英文译者发布帖](https://feuniverse.us/t/fe7-fire-emblem-sword-of-heaven-and-earth-translation-complete-v1-2/12812)。

**尚未核实：**用户目标中文 ROM 的精确构建、指纹、文本编码、地址布局、事件修订和附加补丁。Logia（蒼碧の瞳）2022 汉化、堇天 2024 重置是已找到的版本线索，但本轮未成功核验对应译者原始发布内容。不得把转载站的 ROM 名称、“v1.22”、字库或随机成长标签当作唯一构建身份。

本轮未找到可核验的完整可构建源码仓库，也未取得其使用现代技能系统的可靠证据。原作者倾向保留原《烈火》规则的表述，只能说明设计取向，不能证明不存在自定义战斗逻辑。

**对实现的约束：**FE7 基线和目标《天地之剑》构建需要独立适配。FE8 资料、FE8-UI、FE8 SkillSystem 均可提供参考，但不能直接作为目标的地址表或规则真值。

## 7. FE8U 背包操作的真实语义

以下事实仅针对所核验的 FE8U 代码；FE7《天地之剑》必须分别验证。

| 操作 | 已核实的原作行为 | 对外部管理界面的约束 |
| --- | --- | --- |
| 准备画面交换 | 交换完整物品槽并压紧空槽；交换函数不设置战场交换行动标记 | 与战场命令使用不同上下文 |
| 战场交换 | 从相邻同阵营单位等合法对象生成目标列表，救出关系有额外处理；实际交换设置 `UNIT_ACTION_TRADED` | 全军跨距离转移不是原作交换的等价替代 |
| 交换后的行动 | 记录 `BM_TAKEN_ACTION_TRADE` 后返回当前单位动作菜单；此后取消以切换单位会转为强制待机 | 既不能说交换立即结束行动，也不能说完全没有行动成本 |
| 战场补给 | 先检查章节仓库是否开放；原版 FE8U 通常要求当前路线主角本人或与其上下左右相邻，排除幻影 | 仓库可读不等于任意单位随时可以取放 |
| 装备 | 把所选武器移到第 0 格；装备武器查询按顺序找第一件可用武器 | 自动排序可能改变反击武器，须明确保留装备策略 |
| 使用道具 | 战场道具、杖进入正式行动结算；准备画面有独立使用白名单，明确排除杖 | 整理、装备、使用分别建模，不用“背包写入”一项覆盖 |

物品槽为完整 `u16` 实例：低字节物品 ID、高字节耐久，不可损坏物品另有属性语义。移动、交换、复制、生成、修满耐久是不同命令。实际取放还需维护单位/仓库容量、空槽压紧，以及操作前 owner/slot 是否仍对应同一物品。

依据：[准备画面交换](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/prep_itemtrade.c)、[战场交换](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/bmtrade.c)、[交换目标](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/bmtarget.c)、[玩家阶段行动处理](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/playerphase.c)、[菜单与补给判定](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/bmmenu.c)、[仓库开放条件](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/bmcontainer.c)、[物品实例与装备](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/bmitem.c)、[道具使用判定](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/bmitemuse.c)。

研究编辑也有结构关联：FE8U 的换阵营会寻找目标阵营空部署槽、复制并清空旧槽、更新活动单位及救出引用；移动结束需同步被救出单位和弩车位置，地图另有占用与迷雾缓存。因此原始字段编辑、受约束的语义命令、执行游戏事件必须区分。

依据：[bmunit.c](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/bmunit.c)、[bmmap.c](https://github.com/FireEmblemUniverse/fireemblem8u/blob/master/src/bmmap.c)。

## 8. 按构建、按能力适配

**已核实事实：**FEBuilder 的文本实现区分 Latin-1、Shift-JIS、UTF-8 和中文自定义码表，并区分 Huffman、anti-Huffman 等文本读取路径。汉化不仅是应用界面切换语言。

依据：[SystemTextEncoder.cs](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/master/FEBuilderGBA/SystemTextEncoder.cs)、[FETextDecode.cs](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/master/FEBuilderGBA/FETextDecode.cs)。

**已确认的规划方向：**适配以精确 ROM 构建及其验证结果为单位，并分别记录读取、文本、地图、预测、事件、背包命令和研究编辑能力。底层游戏、改版版本、汉化/二改版本与运行时布局不得混为一个名称；能正确读取基础单位，不代表其他能力已经验证。

本文中的代码结构、作者发布信息属于技术证据；产品优先级、首版功能范围、界面与实施顺序属于项目规划。本文不以外部项目的实现状态替代 EmberLily 的开发与验收记录。

## 9. PokeDaisy 可复用的宿主经验与边界

本节依据本地 pokedaisy 提交 `6fcb8b50f71c214b13149ea1adb7b7f82c1a4e6f`；所引用文件核对时无工作区修改。链接固定到该提交，不代表其后续版本行为。

**已核实事实：**GBA 主采样路径在模拟线程帧间捕获 EWRAM 和 IWRAM 副本，再由单独线程解码。它证明了“模拟器内读取 → 一致 RAM 快照 → 独立界面”的可行路径。快照之外的地址存在回退读取，因此不能把它称为全地址空间的原子快照。

依据：[MemoryReader 与 RamSnapshot](https://github.com/lidor30/pokedaisy/blob/6fcb8b50f71c214b13149ea1adb7b7f82c1a4e6f/app/src/main/kotlin/com/pokedaisy/app/companion/data/MemoryReader.kt)、[采样调度](https://github.com/lidor30/pokedaisy/blob/6fcb8b50f71c214b13149ea1adb7b7f82c1a4e6f/app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt)。

Android 双屏使用 Presentation，并处理 Compose 生命周期与按键转发；副屏窗口不抢系统焦点。模拟器与主 Activity 同时包含宝可梦、DS 等专用能力，因此这些代码是选择性复用的来源，并非可直接搬入的纯 GBA 宿主模块。

依据：[DualScreenPresentation](https://github.com/lidor30/pokedaisy/blob/6fcb8b50f71c214b13149ea1adb7b7f82c1a4e6f/app/src/main/kotlin/com/pokedaisy/app/DualScreenPresentation.kt)、[EmulatorEngine](https://github.com/lidor30/pokedaisy/blob/6fcb8b50f71c214b13149ea1adb7b7f82c1a4e6f/app/src/main/kotlin/com/pokedaisy/app/EmulatorEngine.kt)。

Android 即时存档保存／加载使用 `SAVESTATE_SAVEDATA`，加载可能将携带的游戏存档写回文件。EmberLily 的实验隔离必须覆盖该保存行为，不能只凭“编辑未改原 ROM”推断主存档不受影响。

依据：[Android JNI 即时存档实现](https://github.com/lidor30/pokedaisy/blob/6fcb8b50f71c214b13149ea1adb7b7f82c1a4e6f/app/src/main/cpp/pokedaisy_jni.c)。

仓库声明 GPLv3，并要求衍生作品在 NOTICE、README 及 About／credits 等相应位置保留指定 PokeDaisy 致谢。其第三方清单另列 mGBA 的 MPL 2.0 等许可证。该声明支持本项目已确认的开源及来源登记路线，具体复用文件仍需在 M0 记录。

依据：[PokeDaisy NOTICE](https://github.com/lidor30/pokedaisy/blob/6fcb8b50f71c214b13149ea1adb7b7f82c1a4e6f/NOTICE)。
