# 第三方组件与复用来源

EmberLily 按 GPLv3 发布，完整条款见根目录 `LICENSE`；第三方组件保留各自许可证。根目录 `NOTICE` 与应用内“关于与开源许可”展示致谢及许可原文。

| 组件 | 锁定来源 | 使用范围 | 许可证 |
| --- | --- | --- | --- |
| mGBA | Git 子模块 `third_party/mgba`，版本 0.10.5，实际提交由父仓库 gitlink 固定 | GBA 模拟核心 | MPL 2.0 |
| blip_buf | 上述 mGBA 的 `src/third-party/blip_buf` | 音频重采样 | LGPL 2.1 或更新版本 |
| inih | 上述 mGBA 的 `src/third-party/inih` | 核心配置解析 | BSD 3-Clause |
| AndroidX / Compose | Gradle 显式版本及 Compose BOM | Android 宿主与界面 | Apache 2.0 |
| Kotlin / coroutines | Gradle 显式版本 | 应用语言与并发 | Apache 2.0 |
| Gradle wrapper | Gradle 8.10.2 官方 wrapper JAR；Unix 启动脚本取自下述 PokeDaisy 提交 | 构建引导，不属于模拟核心 | Apache 2.0 |

初始化核心源码：

```sh
git submodule update --init third_party/mgba
```

PokeDaisy 参考提交为 `6fcb8b50f71c214b13149ea1adb7b7f82c1a4e6f`：

- 复制其 Unix wrapper 启动脚本，新增 Windows 启动脚本；JAR 使用 Gradle 8.10.2 分发包中提供的版本。
- 沿用已验证的 Android 构建版本与必要依赖配置。
- 选择性参考模拟线程、帧间采样、双屏 Presentation、按键归属及帧缓冲释放经验；具体实现范围见根目录 `NOTICE` 和各实现文件的来源注释。
- 火纹领域模型、界面和命令服务由 EmberLily 建立，不包含宝可梦数据、DS 模拟器、成就或网络服务依赖。

真实地图扩景另参考 PokeDaisy `f0a630c47f0468e5ab77f1e8ef333d4ea27f8738` 的地图边缘重建、图层对齐校验及原帧保留方法。FE8 图块与单位渲染为独立实现，未复制宝可梦地址表或地图数据。固定来源、FE8J 地址核验与当前覆盖范围见 [地图扩景依据](../docs/WIDESCREEN_EVIDENCE.md)。

必须保留的致谢：

> Based on PokeDaisy by Lidor Itzhari - https://github.com/lidor30/pokedaisy

`app/src/main/assets/licenses/` 保存本次引入组件的许可原文。Gradle 的 `generateLicenseNotices` 将根目录声明、GPLv3 及这些原文合并为 APK 内的 `licenses/notices.txt`。更新组件版本或增加依赖时，同时核对许可文本和组件表。

仓库与安装包均不分发 ROM、游戏素材或 BIOS。本地验证文件放入已忽略的 `.local/`。
