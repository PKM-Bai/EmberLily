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

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。这是 M0 调试构建，尚非 v0.1 发布包。

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

模拟、音频采样、存档和帧间任务由唯一线程串行执行。画面复制到宿主缓冲区，不向 UI 暴露 native 指针。`readMemory` 限定读取区域和长度，没有公开任意写内存接口；M1 再建立火纹 profile 和受控命令服务。

## 双屏与手柄

主显示屏放游戏，副显示屏使用 `Presentation` 放控制面板；没有副屏时自动采用单屏布局。支持 A/B、十字键、左摇杆、L/R、Start/Select，以及键盘 Z/X、方向、Enter/Space。触摸按键也可使用；失去焦点、暂停、设备移除和切换时释放按住状态。

Thor 验收必须单独记录：从上下两屏启动、下屏触摸后手柄输入、持键时切换/失焦、休眠唤醒、旋转和屏幕重连、游戏内保存、连续运行与音频稳定。Android 模拟器测试不代替这些设备结论。
