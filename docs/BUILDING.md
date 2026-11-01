# 构建与本地验证

## 环境

- JDK 17 或 21；Android SDK Platform 34、Build Tools 34.0.0。
- Android NDK `28.2.13676358`、SDK CMake `3.22.1`。
- Gradle wrapper `8.10.2`、AGP `8.5.2`、Kotlin `1.9.24`；版本在仓库配置中固定。
- Python 3.9+ 用于本地文件登记和原创诊断程序生成。
- Android 8.0 / API 26 以上；当前 APK 构建 `arm64-v8a` 和 `x86_64`。

```powershell
git submodule update --init third_party/mgba
# 在未提交的 local.properties 中设置 sdk.dir=C:/path/to/Android/Sdk
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Unix 使用 `./gradlew`。mGBA 固定在 `26b7884bc25a5933960f3cdcd98bac1ae14d42e2`（0.10.5）；没有初始化子模块时，CMake 会给出明确错误。首轮构建需要下载 Gradle 和 Android 依赖，代理由开发者本地配置，不写入项目。

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。这是内部预览构建，尚非 v0.1 发布包。

## 文件登记

```powershell
python scripts/register_rom.py --rom 'roms/你的圣魔文件.gba' --role fe8
python scripts/register_rom.py --rom 'roms/你的天地之剑文件.gba' --role sword-of-heaven-and-earth
python -m unittest discover -s scripts/tests -v
```

登记只读取源文件、计算 SHA-256 / SHA-1 并校验大小与 header；不修改或复制源 ROM。结果默认存入 `.local/targets.json`，`role` 是作品声明，不能证明兼容。文件头不合规时拒绝登记；不自动修 ROM。

目录约定：

| 路径 | 用途 | Git |
| --- | --- | --- |
| `roms/` | 用户自行提供的游戏文件 | 忽略 |
| `.local/targets.json` | 含本地路径的精确文件登记 | 忽略 |
| `.local/`、`samples/local/` | 本地诊断程序、截图、日志与真实存档样本 | 忽略 |
| `scripts/tests/`、`app/src/test/` | 合成数据与纯逻辑测试 | 提交 |
| `app/src/androidTest/` | 真实 JNI 核心的设备测试代码，不附游戏文件 | 提交 |
| `docs/M0_TARGETS.md` | 不含 ROM 内容和个人绝对路径的元数据清单 | 提交 |

## 原创诊断程序与设备测试

诊断程序由本仓库汇编源码生成，没有游戏素材或 Nintendo logo。它显示纯色、按 A 改变颜色、输出方波音频，并将帧计数写入 EWRAM 和 SRAM，供自动测试验证输入、读档和实际电池存档通道。

```powershell
python scripts/create_smoke_rom.py --llvm-bin 'C:/path/to/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.emberlily.app/.MainActivity
adb shell mkdir -p /sdcard/Android/data/com.emberlily.app/files
adb push .local/smoke.gba /sdcard/Android/data/com.emberlily.app/files/smoke.gba
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.emberlily.app.emulation.NativeCoreSmokeTest'
```

上述 Gradle 设备测试用于专用测试模拟器：其测试部署清理会卸载目标应用，连带删除应用数据。保留应用安装及数据时，改为显式安装测试 APK 并调用 runner：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 运行应用创建外部目录，并按上方命令推送 smoke.gba 后：
adb shell am instrument -w -e class com.emberlily.app.emulation.NativeCoreSmokeTest com.emberlily.app.test/androidx.test.runner.AndroidJUnitRunner
```

连接多个设备时给 `adb` 加 `-s <serial>`，并明确测试目标。缺少 `smoke.gba` 时测试直接失败，不静默跳过。集成测试只操作自己的缓存会话；完成后关闭核心并清理测试目录。

本地商业 ROM 测试单独选择 `LocalTargetSmokeTest`，通过 runner 的 `targets` 参数传入英文文件名的逗号列表；文件放入应用外部目录的 `targets/`。若通过 adb 创建该子目录，需对该测试目录执行 `adb shell chmod 777 /sdcard/Android/data/com.emberlily.app/files/targets`，避免 shell 所有权阻止应用读取。未传参数时该测试明确跳过。它只验证启动渲染和状态回退，不证明火纹语义适配。不要将 ROM 加入测试 APK 或仓库。

## 应用操作与存储

通过系统文件选择器打开 `.gba`；应用校验后复制到私有 `files/roms/<SHA-256>.gba`。源文件保持不变，无需全部存储权限。

主进度位于 `files/sessions/<SHA-256>/main/`，实验分支位于同一 ROM 下独立 UUID 目录。每个分支有 `battery.sav`、自动续玩的 `resume.state`、手动恢复点 `checkpoint.state` 和元数据。游戏主动保存写入当前分支电池文件；暂停不自动保存。“保存恢复点”写入手动恢复点并更新续玩状态；后台、切换会话及销毁只更新续玩状态，不覆盖手动恢复点。“恢复此分支”读取手动恢复点，“继续上次进度”读取最新续玩状态。系统强制杀进程不能保证执行最后一次后台保存。

“创建实验分支”先在模拟线程完成当前续玩状态及电池刷盘，再复制到独立目录并切换。新实验的手动恢复点初始化为创建分支时的状态，源分支的旧手动恢复点保持不变。可以返回主进度；实验文件保留，但 M0 尚无完整历史分支列表、删除、导入、提升和迁移界面。“继续上次进度”打开最后使用的会话。

`resume.state` 为 EmberLily `ELSTATE2` 格式，含 mGBA 状态、电池存档及 RTC，不是可直接导入其他模拟器的 `.ss0`。头部绑定完整 ROM 的 SHA-256、长度及核心版本/固定提交；早期测试 v1 状态明确拒绝。本阶段不保证跨核心版本、跨架构状态兼容；恢复前校验身份和状态内容，失败保留原恢复点。若底层恢复和回滚同时失败，关闭该核心并从保留的恢复点重新打开；不把不确定状态保存覆盖恢复点。原子替换用于避免进程中断留下半份文件，不承诺突然断电时的全部文件系统持久性。

模拟、音频采样、存档和帧间任务由唯一线程串行执行。画面复制到宿主缓冲区，不向 UI 暴露 native 指针。`readMemory` 限定读取区域和长度；M1 的 `compareAndWriteMemory` 是有界 RAM 原子事务，UI 仅通过已验证的语义命令使用，不能直接输入地址写内存。

## M1 快照、实验与本地验证

0.0.6 新增副屏“背包”页。物品表和仓库读取依据见 [背包读取证据](INVENTORY_READING_EVIDENCE.md)，正常准备交换及界面验证见 [背包验证](INVENTORY_VALIDATION.md)。R04 原生准备交换集成测试需额外部署 `files/local-probes/r04-wide-agent-worldmap-prep-trade.state`（指纹见验证记录），使用 `adb -s <独立测试设备> shell am instrument -w -e class com.emberlily.app.LocalPrepTradeTest -e prepTradeTest true com.emberlily.app.test/androidx.test.runner.AndroidJUnitRunner`。该测试仅操作 cache 内的独立核心和副本；不要将常规测试安装流程指向用户正在游玩的设备。

R04 的战场页显示光标单位及物品，支持固定另一个单位对比。中文名称尚未建立自定义字库映射，使用文本 ID 表示；R03 实时战局未验收，界面不会套用 R04 的运行验证。研究页先创建独立实验，再在玩家回合的空闲地图编辑 HP（1..最大 HP）。选择移动、事件或动画期间会拒绝写入；成功后保持暂停，并提示前后值。恢复入口还原 `research-before.state`，不覆盖用户手动的 `checkpoint.state`。

研究写入使用候选恢复点和 `research-incomplete` 标记；未完成事务在下次打开时优先恢复候选／编辑前状态，再更新续玩文件。`research.jsonl` 的 `unit_hp` / `stage=memory_applied` 记录实际应用过的字段变化，不代表此后从未回滚；恢复单独记录。日志故障不能阻止数据恢复，日志不是当前状态的真值。

会话页的“导出 RAM 快照”保存 `ELSNAP1`（扩展名 `.elram`），包含 EWRAM/IWRAM、ROM SHA、会话／分支／epoch／frame／revision 和适配器版本，以及完整性摘要。它不含 ROM，不是可运行的即时存档。离线工具使用 `SnapshotCodec.decode` 与 `SnapshotReplay`；应用目前没有导入回放 UI。真实 RAM 和状态文件仅放 `.local/` 或 `samples/local/`，不得提交。

完整 JVM 测试的本地输入（路径由开发者自行提供）：

```powershell
$env:EMBERLILY_FE8J_ROM = 'path/to/R03.gba'
$env:EMBERLILY_FE8J_VOICE_ROM = 'path/to/R04.gba'
$env:EMBERLILY_FE8_REPLAY_DIR = 'path/to/local/fe8-probes'
$env:EMBERLILY_FE7J_ROM = 'path/to/R02.gba'
$env:EMBERLILY_FE7J_CHINESE_ROM = 'path/to/R06.gba'
$env:EMBERLILY_FE7_REPLAY_DIR = 'path/to/local/fe7-probes'
.\gradlew.bat :app:testDebugUnitTest
```

没有相应输入时，本地 ROM／回放测试明确跳过；其余合成测试正常运行。固定真实样本摘要见测试代码、[FE8J 证据](FE8J_EVIDENCE.md) 与 [M2 验证记录](M2_VALIDATION.md)。已有 FE8 回放目录没有新增大地图样本时，只有相关真实扩景用例跳过。

新增设备测试使用独立缓存会话：`MemoryTransactionTest`（emulation 包）、`CompanionRuntimeTest`、`LocalFe8SceneTest`、`LocalResearchEditorTest`。后两项需要显式 `-e fe8SceneTest true` / `-e fe8ResearchTest true`，默认文件为外部 `targets/r04.gba` 与内部 `files/local-probes/r04-02/07/08/09.state`；缺配置不伪造通过。调用方式沿用上节 `am instrument` 并指定目标设备。

`emulation.LocalFe8ProbeTest` 保留早期类名，用于在独立核心通过原生输入采集 FE7/FE8 样本。必填参数 `probeRom=r04.gba`、`probeKeys=0:120,8:2,0:120`、`probeOutput=unique-prefix`；可选 `probeState=probes/previous.state`。按键掩码为十进制，最多 120 段／36000 帧，接受 R02/R03/R04/R06 精确指纹。输出位于应用私有 `files/local-probes/`，包括截图、RAM、ELSTATE2 和来源／输入／产物摘要清单，不覆盖同名文件。通过 `adb exec-out run-as com.emberlily.app cat files/local-probes/<file>` 以二进制方式取回。该工具只在调试测试 APK 中存在，不访问用户主进度。

## M2 FE7 预览

R02 日版与 R06 汉化版使用同一战场／会话／研究界面；各自按完整指纹启用已验证的读取与 HP 编辑。日版短名称可显示，汉化版保持文本编号。教学气泡已出现移动范围时，游戏可能还没有进入可操作的移动回调；必须等待游戏自己的流程，不凭残留的 active-unit 指针认定选择，更不能允许研究写入。

`com.emberlily.app.LocalFe7IntegrationTest` 是显式本地验收入口。使用 `-e fe7Integration true -e fe7Target r02` 或 `r06`，并传入独立核对的章节／光标／单位／物品期望值。ROM 放在外部 `targets/`，四份状态放在内部 `files/local-probes/`。测试在自己的缓存目录创建主进度与实验，不打开应用游玩会话；参数、样本摘要和本轮完整命令见 [M2 验证记录](M2_VALIDATION.md)。

原来的 ELSNAP1、ELSTATE2 与会话目录格式没有改变。新的 FE7 适配不改变 FE8 存档归属，也不将《天地之剑》按 AE7J header 自动识别为原版。正常背包和 FE7 图形扩景仍需各自实现与验证。

## 双屏与手柄

主显示屏放游戏，副显示屏使用 `Presentation` 放控制面板；没有副屏时自动采用单屏布局。支持 A/B、十字键、左摇杆、L/R、Start/Select，以及键盘 Z/X、方向、Enter/Space。触摸按键也可使用；失去焦点、暂停、设备移除和切换时释放按住状态。

上屏使用沉浸式显示，触摸手柄集中在下屏“会话”页。该页的“扩展地图视野”开关默认开启并保存选择；当前仅 R04 已验证的玩家空闲地图尝试扩展。扩展内容来自实时地图、VRAM 和单位精灵，保留完整原帧；小地图、菜单、动画和未适配构建自动保持原比例。详见 [地图扩景依据与验证](WIDESCREEN_EVIDENCE.md)。

独立大地图仪器测试 `com.emberlily.app.LocalWideMapTest` 使用 `-e wideTest true -e wideState <本地大地图.state>`，源状态位于应用私有 `files/local-probes/`，源 ROM 使用 `targets/r04.gba`。测试在缓存目录复制并运行自己的核心，连续验证 180 帧原画面保留和内存不变，PNG 输出在 `files/local-probes/wide-<状态名>.png`。不打开用户会话。需先用真实游戏流程取得样本，不能用修改地图尺寸的合成状态冒充实机通过。

Thor 验收必须单独记录：从上下两屏启动、下屏触摸后手柄输入、持键时切换/失焦、休眠唤醒、旋转和屏幕重连、游戏内保存、连续运行与音频稳定。Android 模拟器测试不代替这些设备结论。
