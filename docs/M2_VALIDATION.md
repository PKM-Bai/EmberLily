# M2 FE7 基础适配验证

记录日期：2026-11-01。用户要求完成上屏显示改进后提前推进 M2；M1 的正常准备背包、完整场景和 Thor 性能验收继续保留。本文件按实际能力记录，不将共享引擎或相同 `AE7J` header 当作兼容证明。

## 目标与实现

- R02：日版《烈火之剑》，SHA-256 `7ec691a7411864ab51765028156c5b8a3c23a317d23c95fbf07ac201212e25c0`。
- R06：狼组／火花汉化《烈火之剑》，SHA-256 `828b8bb1bff54a66b89cb1e43901d88bb783a4da6e543ac31aa6edc17e162d25`。

`FeLayout` 收拢章节、单位、物品、地图、活跃进程及命令时机差异；`FeDecoder`、`FeRomCatalog` 与 `FeResearchCommands` 复用同一语义模型。UI 使用能力与名称编码声明，不判断 FE7/FE8 的内存地址。旧 FE8 API 保留兼容别名，既有回放无需迁移。

R02 与 R06 独立身份登记、加载自己的 ROM 表和文本。R06 的自定义名称编码尚未核实，继续显示文本编号，不套用日文名称。《天地之剑》仍待 M3 独立核验。本次上屏扩景仅开放 R04，FE7 读取成功不自动获得图形扩景能力。

固定源码、布局、表与函数 literal 的直接校验见 [FE7J 依据](FE7J_EVIDENCE.md)。本地 ROM、真实状态、截图与个人路径都不提交。

## 首轮真实进度核对

只读复制 Thor 现有续玩状态到开发机，未在 Thor 上执行输入、编辑、恢复或替换进度。原始结构检查与现有 Kotlin 解码结果一致：

- R02 状态 SHA-256 `0ae4e91523ecebd7545af1e2e472acc4e68982ec54dbe8fa15cc3f1f500b5c5b`：序章、玩家阶段、第 2 回合、光标 `(8,7)`、15×10。琳在槽 1，HP 16/16；物品原值为 `0x2E01,0x036B,0x036B,0,0`。敌军槽 129/130、坐标、HP 与铁斧均与实际记录对应。
- 该 R02 状态仍处于教学对话：BM lock 为 1，事件／对话进程活跃，尚无 PlayerPhase。HP planner 拒绝请求，不能把“可读单位”视为“可写空闲地图”。
- R06 状态 SHA-256 `3b5788277a6d46212814cb7deae2be2e85d58d07f502c801df48e98c6bbca639`：无 MapTask、无有效单位，解码为 `UNAVAILABLE`；章节、回合和光标语义为空，不使用 RAM 残值。

## 真实场景与命令结果

后续在专用 API 35 x86_64 模拟器运行原作按键并采样，不修改 ROM/RAM。R02 从上面的对话副本继续；R06 从自身新游戏流程生成，不能用另一 ROM 的状态替换封装指纹。

| 构建 | 空闲状态 | 琳的数据 | 实际移动选择 |
| --- | --- | --- | --- |
| R02 | 序章第2回合，15×10，光标 `(8,7)` | slot1 / character3 / class2，Lv1 EXP0，HP16/16，位置 `(8,7)` | 光标移到 `(7,7)`，单位仍在 `(8,7)`，selectedSlot=1 |
| R06 | 序章第1回合，15×10，光标 `(13,7)` | 同样的身份字段及 HP，位置 `(13,7)`，物品原值独立读取为 `0x2E01,0x036B,0x036B,0,0` | 光标移到 `(12,7)`，单位仍在 `(13,7)`，selectedSlot=1 |

两份文件的空闲 PlayerPhase 都实际满足 PC `0x08C026A0`、callback `0x0801C625`、自身 lock/sleep/child 为0、BMapMain 父 lock=1 且 child 指回当前 PlayerPhase、MapTask lock=0、BM 三锁为0且无阻断事件。实际移动选择使用 PC `0x08C026E0`、callback `0x0801CACD`、BM flags=3。两份初始教学选择均曾停在 PC `0x08C026D8`、callback=0 且有事件锁；虽然已经显示移动范围，仍保持 selectedSlot=null 并拒绝编辑，没有为通过测试放宽合同。

`LocalFe7IntegrationTest` 对 R02/R06 **分别运行3项，全部通过**：真实场景／快照往返与非空闲拒写；实验 HP 16→15→重新打开15→恢复16→重新打开16；同一个 CompanionRuntime 顺序 FE8→FE7→FE8。每次编辑仅目标 HP 字节发生变化，其余 EWRAM/IWRAM 一致；测试创建的主进度、手动恢复点和原始样本保持不变。迟到 worker、旧固定对象、旧命令与错误 ROM 的快照均不能进入新上下文。日志分别记录构建、实验分支及前后值。

集成测试在观察状态的副本上恢复和执行明确事务，不推进游戏帧；连续帧输入取样由独立 probe 记录。不能把这项结果扩大为全章连续战斗、原游戏菜单保存 HP 或全部事件行为已验收。

关键状态指纹：

| 本地标签 | SHA-256 |
| --- | --- |
| r02-idle（同 r02-01） | `b3de6c97f99fbd50246e378af2b4925a56eff32c000d3dc3e12c2986f18c61cd` |
| r02-title | `5345e0542178c553168282c8afda5ee8b489e31412f451c01c4c84e42f2ea116` |
| r02-agent-movement | `9376763c8e7d284901acc76994e960e83004aeec6cb26165166f32ea8153a334` |
| r06-agent-idle | `34ea61fcf0a4ac24594d65d6dd8929dd44cb05f24fd35e601d127452b9a0faeb` |
| r06-agent-04（战场对话） | `b69735721cbd2f66e44fb14ba0d99b5c997ed55b5ae2609eff13963767d253d0` |
| r06-agent-movement | `a61310edd23ff068bf38d91df3099d413ef10c4051dbd80d7b06518c76c691b3` |

教学阻塞与开场附加样本的 SHA 固定在 `Fe7LocalReplayTest`。R06 黑底开场对话已有 MapTask 和单位数据，因此和 FE8 开场一样可读为已加载的 MAP，事件锁继续禁止编辑；不能仅根据黑色截图推断成标题场景。

## 构建、回归与复现

`0.0.4-m2-preview` Android arm64-v8a / x86_64 应用与测试 APK 编译通过。最终 **54 项 JVM 测试通过，0 跳过**，其中 FE7 回放3个方法覆盖9份真实状态。设备验证包括既有23项宿主／FE8回归、R02三项、R06三项，以及第二章纵向／第三章横向两项连续180帧扩景，共31项通过。

测试模拟器的独立 R06 界面分支已目视核对：游戏原生画面与战场面板并排，章节0／回合1／光标13,7／单位3名／HP16/16一致，汉化名称按编号显示且附说明，上屏没有虚拟按键。9项登记脚本测试通过，FE7 只读研究脚本也分别接受两份真实 idle 状态生成报告；沙箱默认临时目录不可写时改用工作区内的隔离测试临时目录，未改测试断言。

本地交付包 `.local/releases/EmberLily-0.0.4-m2-preview.apk`，versionCode=4，SHA-256 `68ceba8ccfbcfe19b1077251cb1cb95291404a975ea688a16e2c437476bff7fd`。它是上述能力范围的内部预览；APK 不随 Git 提交。

真实游戏输入与状态只放忽略目录，缺少本地输入时相应 JVM 测试明确跳过，不伪报兼容。环境变量与部署说明见 [BUILDING](BUILDING.md)。本次 FE7 native 复现参数如下，前提为已部署与指纹一致的本地样本：

```powershell
adb -s emulator-5554 shell am instrument -w -e class com.emberlily.app.LocalFe7IntegrationTest -e fe7Integration true -e fe7Target r02 -e fe7ExpectedMap '0,2,8,7,15,10' -e fe7ExpectedUnit '1,3,2,16,16,8,7,1,0' -e fe7ExpectedItems '0x2E01,0x036B,0x036B,0,0' -e fe7Movement r02-agent-movement.state com.emberlily.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class com.emberlily.app.LocalFe7IntegrationTest -e fe7Integration true -e fe7Target r06 -e fe7ExpectedMap '0,1,13,7,15,10' -e fe7ExpectedUnit '1,3,2,16,16,13,7,1,0' -e fe7ExpectedItems '0x2E01,0x036B,0x036B,0,0' -e fe7Map r06-agent-idle.state -e fe7Dialog r06-agent-04.state -e fe7Movement r06-agent-movement.state com.emberlily.app.test/androidx.test.runner.AndroidJUnitRunner
```

## 角色对比布局修复（0.0.5）

2026-11-01 按用户反馈，将对比改为光标单位在左、固定角色在右，HP 和力／技／速／防／抗／运逐行排列。移除 600dp 以下上下堆叠的分支；名称、职业、属性及同槽物品共享对应行，全部内容共同滚动。固定与取消按钮放在能力列表之后。单单位详情及领域解析、引用失效逻辑保持既有行为。

验证使用 API35 x86_64 独立模拟器与 R06 的隔离 UI 分支：

- 2400×1080、420dpi 的横屏中，操作面板约 381dp 宽，浅色主题下两列仍并排，HP 与六项能力可一同查看。
- 将模拟器调整为 2592×1240、369dpi，右侧操作面板为 1080×1240，与读取到的 Thor 下屏分辨率和密度相同；深色主题、字体比例 1.3 下，界面树核对 HP 与六项能力的左右边界分离、上下边界逐行相同，截图无重叠或截断。此项为模拟器尺寸验证，不替代 Thor 实机反馈。
- 实际游戏光标从角色槽1移至空地、再移至坐标(2,6)的敌方单位，右侧保持槽1；两侧 HP 分别为20/20、16/16，其他能力也有不同值，未用同一对象假充对比。
- 空地时左侧显示占位，右侧保持角色；再次固定将右侧替换为当前单位；取消固定恢复单单位详情，均通过实际界面操作与界面树核对。
- `:app:assembleDebug` 通过，布局静态扫描无报告，`git diff --check` 通过。本次只调整 Compose 呈现，没有重跑上一节的领域与原生核心全套测试。

本地截图与界面树位于忽略目录 `.local/comparison/`。交付包 `.local/releases/EmberLily-0.0.5-m2-preview.apk`，versionCode=5，SHA-256 `6f1c73fda5e5e13833ce3f893bd6800e2612b4b3cf559fe7e6ffd9a608a16347`。

## 用户验收反馈：角色对比

2026-11-01，用户在 `0.0.5-m2-preview` 角色对比修复交付后反馈：“可以，验收没问题”。按会话中既定 Thor 验收流程，登记左右并排、能力竖排的角色对比功能验收通过。反馈未附新的游戏指纹、章节或具体操作清单，因此不扩大为所有构建、转场场景或 M1/M2 全阶段通过。此前列出的开发模拟器验证仍保留其独立证据范围。

## 未完成项

FE7 正常准备背包尚未实现，M2-03 不关闭。0.0.6 已完成 R04 副屏准备交换首个闭环，并为 FE8／FE7 增加独立背包只读、筛选和比较，见 [背包验证](INVENTORY_VALIDATION.md)。FE7 仍必须取得自己的准备交换场景；新增原生物品菜单及伤药使用对照见 [FE7 背包样本](FE7_INVENTORY_SAMPLES.md)，不据此开放副屏使用或交换。Thor 角色对比已有上述用户通过反馈；新版背包、FE7 更多场景及持续运行仍需实机验证。M2 目前不视为阶段验收通过。
