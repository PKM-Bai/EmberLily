# GBA 地图扩景：来源、实现与验证边界

记录日期：2026-11-01。用户明确要求上屏显示原生 240×160 之外的真实地图内容；普通拉伸不能作为这项功能的交付。

## 固定的研究来源

本次阅读本地已有源码，没有取得或分发新的游戏文件。源码版本如下；游戏数据只来自已登记的本地 R04 文件及其运行快照。

| 来源 | 固定提交 | 用途 |
| --- | --- | --- |
| PokeDaisy | `f0a630c47f0468e5ab77f1e8ef333d4ea27f8738` | Android 显示模式与 GBA WIDE 重建技术 |
| fireemblem8u | `04d1edb163d14bd10777a065697e1a9f17be65f2` | 地图、调色板、单位精灵的程序语义 |
| FEBuilderGBA | `8e3557912ddab708d11bf85ad311b41bb6630fd1` | FE8J 地址表，随后用 R04 实际快照校验 |

美版反编译源码说明算法，不直接提供日版或改版的地址保证。R04 的完整 SHA-256 为 `4853c904c1c180f837678f3d0032dbbb86049bc710fb1993fb51bad8c49b1076`；目前只对这个构建尝试扩景。

## PokeDaisy 实际做了什么

PokeDaisy 将 ORIGINAL、STRETCH、WIDE 分成不同显示模式。ORIGINAL 保持原比例，STRETCH 把已有帧拉满，WIDE 则继续绘制屏幕之外的地图并保持像素为正方形。系统状态栏、导航栏隐藏属于独立的沉浸式窗口设置。依据：[GameStageLayout.kt:8](https://github.com/PKM-Bai/pokedaisy/blob/f0a630c47f0468e5ab77f1e8ef333d4ea27f8738/app/src/main/kotlin/com/pokedaisy/app/GameStageLayout.kt#L8)、[PokeDaisyActivity.kt:2579](https://github.com/PKM-Bai/pokedaisy/blob/f0a630c47f0468e5ab77f1e8ef333d4ea27f8738/app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt#L2579)、[沉浸式窗口:3715](https://github.com/PKM-Bai/pokedaisy/blob/f0a630c47f0468e5ab77f1e8ef333d4ea27f8738/app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt#L3715)。

GBA 宝可梦只在 VRAM 保持 256×256 的地图环形缓存。直接增大模拟器画幅会读到缓存重复部分。PokeDaisy 的 `pk_wide.c` 从地图网格、ROM 中的 metatile、当前 VRAM 图块、实时调色板和游戏精灵链重建两侧；已连接地图另读其 ROM 数据。该算法保留当前与前一份 RAM，逐帧与实际 BG tilemap/OAM 比较，防止游戏主循环和 VBlank 之间的错位。中央原生帧仍直接复制。依据：[pk_wide.c:1](https://github.com/PKM-Bai/pokedaisy/blob/f0a630c47f0468e5ab77f1e8ef333d4ea27f8738/app/src/main/cpp/pk_wide.c#L1)、[图块对齐评分:974](https://github.com/PKM-Bai/pokedaisy/blob/f0a630c47f0468e5ab77f1e8ef333d4ea27f8738/app/src/main/cpp/pk_wide.c#L974)、[相机候选与原帧合成:2712](https://github.com/PKM-Bai/pokedaisy/blob/f0a630c47f0468e5ab77f1e8ef333d4ea27f8738/app/src/main/cpp/pk_wide.c#L2712)。

平滑与着色器另在 `EmulatorView.kt` 实现；最近邻或线性过滤的选择不会增加游戏信息。上述 GBA WIDE 没有替换为高清纹理，也不是 NDS 3D 视锥扩展。宝可梦的 ObjectEvent、gSprites、连接地图和 ROM 配置与火纹不同，不能直接把它的地址表交给 FE8。这里采用同样的“从当前游戏数据重建边缘、验证对齐、保留原帧”方法，火纹渲染器为独立实现。依据：[WideScreen.kt:4](https://github.com/PKM-Bai/pokedaisy/blob/f0a630c47f0468e5ab77f1e8ef333d4ea27f8738/app/src/main/kotlin/com/pokedaisy/app/WideScreen.kt#L4)、[EmulatorView.kt:898](https://github.com/PKM-Bai/pokedaisy/blob/f0a630c47f0468e5ab77f1e8ef333d4ea27f8738/app/src/main/kotlin/com/pokedaisy/app/EmulatorView.kt#L898)。

## FE8 的真实地形与离屏单位

FE8 `DisplayBmTile` 将运行时 `gBmMapBaseTiles` 每个 16×16 格子的索引交给 `sTilesetConfig`，取四个 8×8 图块描述符，按 fog 数据叠加 palette 6 或 11。图像来自实时 BG VRAM；已开门、开箱和其他地图变更会更新这份运行时地图，不能每帧重读 ROM 原始地图覆盖它。相机与 `mapRenderOrigin` 决定 BG3 环形缓存的滚动。依据：[bmmap.c:230](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmmap.c#L230)、[四图块合成:327](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmmap.c#L327)、[相机与环形缓存:384](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmmap.c#L384)。

`RefreshUnitSprites` 为全部有效且可显示的单位建立 SMS 链和当前动画图块，不按视口裁剪。只有 `PutUnitSpritesOam` 在提交 OAM 时才剔除原画面以外的单位。因此稳定地图场景中可以从现有 SMS 与实时 OBJ VRAM 绘制额外区域的单位，无需凭职业编号生成另一套精灵。隐藏、移动动画与震动状态另有处理，不能误认为所有隐藏精灵都可以强制显示。依据：[bmudisp.c:815](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmudisp.c#L815)、[OAM 裁剪:930](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmudisp.c#L930)、[SMSHandle 布局](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/include/types.h#L380)。

R04 所用地址经 FEBuilder 日版表和实际 RAM/ROM 校验：

| 数据 | FE8J / R04 地址或偏移 |
| --- | --- |
| Bm 状态；相机；地图绘制原点 | `0x0202BCAC`；`+0x0C/+0x0E`；`+0x24/+0x26` |
| 地图尺寸、单位占位行表指针、fog 行表指针 | `0x0202E4D0`、`0x0202E4D4`、`0x0202E4E4` |
| tileset config | `0x02030B88` |
| 实际地图格子行表与数据池 | `0x02032F88` 至 `0x02033EF8` 之前 |
| ROM 中的地图池指针 literal | `0x085C2E54`，R04 内容为 `0x02032F88` |
| SMS 链哨兵及池上界 | `0x0203A014`、`0x0203A4C4` |
| SMS 记录 | 12 字节；next `+0`、x/y `+4/+6`、oam2Base `+8`、config **`+0x0B`** |
| Unit 的 SMS handle | `+0x3C` |

地址来源：[FEBuilder FE8 表:7209](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/bin/Debug/config/data/asmmap_FE8.en.txt#L7209)、[ROM 指针:11347](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/bin/Debug/config/data/asmmap_FE8.en.txt#L11347)。其中 `config` 不是相邻的 `+0x0A` 字节；真实 R04 的 Seth 记录也证实这一点。

## 实现与降级约定

`graphics/Fe8WideRenderer.kt` 接收同一模拟线程采集的 RAM、VRAM、palette、OAM、IO 寄存器及已完成的原帧。新增区域按实时地图与动画绘制；原来的 38,400 个像素逐个原样复制。原画面不被裁剪。相机在地图左边时把额外区域全部放到右侧，靠右边时反向偏移；上下边界同理。地图本身只有 15×10 时返回原图，不添加不存在的地形。输出最多 640×480；正常 16:9 屏幕目标为 285×160，并由宿主等比例显示。

首个能力边界为 R04 的玩家空闲战场：检查活跃进程树、地图锁与当前回调，排除移动选择、战斗、菜单、事件、转场、未知窗口及 BG3/OBJ 颜色特效；每次对原视野取样的 651 个 BG3 描述符与可见 SMS/OAM 都要匹配。保留同一会话、分支、epoch、revision 中相邻一帧的 RAM 作为第二候选；读档或切换上下文不能复用旧候选。单位占位存在而缺少 SMS handle 时拒绝扩景，避免把有单位的位置显示为空地。当前鼠标/光标悬停单位使用另一种 MU 动画，其可能占用范围必须完全位于原帧中，否则回退。

天气严格限制为 `PlaySt.chapterWeatherId`（`+0x15`）等于 `WEATHER_FINE = 0`。雪、暴雪、夜晚、雨、火焰、沙暴、云和未知值均返回原帧，不把缺失天气的两侧画面当作正确扩景。此外，启用的 BG2 必须保持已验证的透明 tile-0 状态；出现移动/攻击覆盖或其他非空 BG2 内容就回退，即使 RAM 场景标记尚未同步也不能忽略该图层。依据：[PlaySt 天气字段:188](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/include/types.h#L188)、[天气种类:354](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/include/types.h#L354)、[天气渲染分派:822](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/bmio.c#L822)。本地 `r04-09`、`r04-wide-agent-03/10` 的天气均为 0，BG2 均为透明状态；合成测试覆盖所有已知非晴天值、未知值与非空 BG2。

目前扩展区包括地形、静止单位身体与共用 SMS 的地物。状态角标、HP 条、光标、移动/攻击覆盖、天气和特殊事件动画没有普遍实现；正常原帧仍保留它们。新的 FE7/FE6/改版、特殊图层和非空闲场景必须独立适配与验证后才开启，不能仅因为读单位成功就继承扩景支持。

## 验证记录

- 独立 Kotlin 1.9.24 编译通过；启用本地真实样本的 `Fe8WideRendererTest` 共 **12 组测试通过**。合成图像和地图覆盖额外单位、左右/上下地图边界、全原帧不变、所有输入数组不变、图块/OAM/单位句柄失败、未知 ROM/场景/颜色效果、读档上下文失效及视频快照防御复制；真实样本范围见下文。
- 真实本地 `r04-09.state`：651 个图块取样及可见单位 OAM 校验通过，最终因地图为 15×10、没有额外区域而返回原帧。这项 JVM 回放测试使用任意原帧数组，验证的是实际 RAM/BG/OAM 对齐，不需要 Android 编译环境不提供的 `javax.imageio`。真实图像输出的像素比较留给 Android 仪器测试读取 Bitmap 验证。该小地图用例不会冒充“真实额外视野已经验证”。
- 颜色交叉校验：从该真实状态的 BG3/VRAM/palette 重建背景，与原 PNG 比较，5 位颜色做位复制时有 29,556 / 38,400 个完全相同像素；剩余区域包含单位和 UI 遮挡。只左移 3 位匹配 0 个。与 mGBA `mColorFrom555` 在转换后执行 `color |= (color >> 5) & 0x070707` 一致，扩展区沿用位复制以保持接缝颜色一致。
- 合成 22×12 地图：输出 285×160，额外区域显示原视野外 `(256,64)` 的单位，中央 240×160 像素逐个相同。合成数据只验证算法边界，不替代真实大地图、Thor 性能或玩家体验验证。
- 真实 R04 第二章 `r04-wide-agent-ch2-07.state`（SHA-256 `a3e90c83fce327cca8670c16f9e394ffbaf8ee01f7b203c951a1be28d4e70d7a`）：地图 15×15、相机 `(0,0)`，方形视口输出 240×240；651 个取样图块/OAM 对齐通过，原生下方追加 80 像素地图，显示位于 `(10,12)`、`(9,14)`、`(6,11)`、`(7,14)` 的四个实际敌方单位。独立离线运行将真实 PNG 作为原帧输入，输出中央 38,400 像素逐个相同，扩展图像已目视检查。JVM 回放测试绑定该状态哈希，并断言四个精灵的红军图块像素及原帧不变；没有把游戏数据写入仓库。
- 第二章滚动实证：`idle-north` 与 `cancel-idle` 均输出 240×240、原帧 `(0,0)`、额外 4 个 SMS；`idle-south` 相机 `(0,80)`，输出仍为 240×240、原帧移至 `(0,80)`、额外 9 个 SMS，包含原上方的友军与 NPC。`selection` 已进入移动选择及 BG2 覆盖，正确返回原帧；取消后恢复扩景。对应 JVM 回放固定状态哈希，并核对四种状态、原帧位置和原帧逐像素不变。
- 跨相机像素比较：北端扩景的 world Y=160..239，与南端原生帧 Y=80..159 比较；排除实际 BG0/BG1 图块与 OAM 标识的 UI、光标、动画区域，以及北端四个 SMS 足迹（两份快照动画相位不同）。剩余 **15,031 个地形像素全部相同，差异为 0**。未掩码的全部 19,200 像素中匹配 16,597 个，差异不能误报为地形错误或宣称所有动态像素相同。
- 该第二章只有 15 列，因此上述验证是**真实竖向扩景**；横向验证采用下述第三章样本，不能把第二章结果直接外推为宽屏表现。设备性能由宿主的 Android 仪器测试另行记录，不外推为全部章节通过。

第二章补充状态 SHA-256：

| 本地样本标签（共同前缀 `r04-wide-agent-ch2-`） | 状态 SHA-256 |
| --- | --- |
| `idle-south` | `452ea03c54da9ac978f49f2ef3318ef86f304f3a49c5d20919cab9cbe5eee24a` |
| `idle-north` | `69ba9d33fc6f87d7f352213fab805369c490bae4384efa31148cbd017b475d38` |
| `selection` | `dac4c61cc1bf9907996239fc0ad53606183c6a5413d0d755319089ee9ec755f7` |
| `cancel-idle` | `541cfef96399ba79aa54fcb994b188c24146aaada31a15db102fe1a0c83226d7` |

### 为横向验收选择实际宽地图

只读 R04 ROM 的 `0x08034534` literal 得到章节表 `0x08904E1C`；每条记录 `0x94` 字节，地图 asset ID 位于 `+8`。`0x08034588` literal 得到 asset 指针表 `0x08907BC8`，对应地图 LZ77 解压结果的前两个字节为宽、高。算法依据：[chapterdata.c:8](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/src/chapterdata.c#L8)、[ChapterMap/ROMChapterData](https://github.com/FireEmblemUniverse/fireemblem8u/blob/04d1edb163d14bd10777a065697e1a9f17be65f2/include/chapterdata.h#L6)、[FEBuilder 日版表指针](https://github.com/FEBuilderGBA/FEBuilderGBA/blob/8e3557912ddab708d11bf85ad311b41bb6630fd1/FEBuilderGBA/ROMFE8JP.cs#L50)。

| 章节 ID | 地图尺寸 | 地图 asset / ROM 地址 |
| --- | --- | --- |
| 0 | 15×10 | `0x04 / 0x081A70AC` |
| 1 | 15×10 | `0x08 / 0x081A6F88` |
| 2 | 15×15 | `0x0B / 0x081A6DF4` |
| 3 | **17×16** | `0x11 / 0x081A6C8C` |
| 4 | 15×15 | `0x15 / 0x081A6B14` |
| 5 | 22×19 | `0x1B / 0x081A6930` |

第三章是该构建最早超过 15 列的地图，因此选作横向验收样本。其全宽只有 272 原生像素，16:9 输出应受实际地图边界约束；不得为达到 285 像素而补造地形。

### 第三章真实横向验收

第三章实际 17×16 地图使用 1920×1080 目标视口，离线渲染输出 **272×160**。相机 Y=64 时，`world02`、`idle-left`、`cancel-idle` 相机 X=0，原帧在 `(0,0)`，右侧新增 32 像素；`idle-right` 相机 X=32，原帧在 `(32,0)`，新增左侧 32 像素，绘制原视野外 **4 个蓝军单位**。左镜头此时新增区域没有单位，因此该方向的实际计数为 0，不能误报额外敌军。所有有效样本的 651 个 BG3 取样与可见 OAM 校验通过；`selection` 返回原帧、取消后恢复扩景，无需放宽场景检查。

三份左右/取消样本使用各自真实 PNG 作为输入，输出原帧 38,400 个像素逐个完全相同；左右宽图已目视确认。左右交叉比较中，以实际 BG0/BG1/OAM 和源快照 SMS 足迹排除 UI、光标和不同动画相位：左镜头新增右条带与右镜头原生区域的 **4,222 个地形像素全部相同**；右镜头新增左条带与左镜头原生区域的 **2,426 个地形像素全部相同**。两方向差异均为 0。未掩码比较分别为 4,348/5,120 与 3,292/5,120，不宣称不同帧的 UI 和精灵动画完全一致。

JVM 回放测试固定以下状态身份，断言原帧位置/像素不变、地图边界、选择回退及四个额外友军的真实蓝色图块像素。图像与游戏数据仅保存在本地忽略目录。

| 本地样本标签（共同前缀 `r04-wide-agent-ch3-`） | 状态 SHA-256 |
| --- | --- |
| `world02` | `6421e14e39f2347209bdce2bfe58a8322955a803957dd622750d58eb37ca6ca6` |
| `idle-left` | `e9e6dc58ba9fab4dd75c93b9471df18d8c1a81f5ba116d8ad89ee71ff28e98d3` |
| `idle-right` | `36184e2fc77517a7850e913d764d9766e16153cf3953e079eff18ac8692b9bda` |
| `selection` | `139c6e8d57975516eb5bb1fc776928136a1040c283d1b3699f998147ffc2f689` |
| `cancel-idle` | `e045e0b1c4ff54b77fe0a28a1b20f004268581780b4232b40525a09b1f326d04` |

测试样本本身留在忽略的 `.local` 目录。仓库只发布算法、合成测试与证据元数据。

### Android 与宿主验证

独立 API 35 x86_64 模拟器运行 `LocalWideMapTest`，使用第二章北侧真实状态的副本和方形视口：180/180 帧成功输出 240×240，原帧位置 `(0,0)`，每帧检查原生像素完整保留、源帧数组不变和 EWRAM 不变。预热 30 帧后渲染 P95 为 1.98 ms，采样／重建／Bitmap 发布 P95 为 4.81 ms。此观测不包含 UI 合成、音频和帧推进，不能代替 Thor 持续游玩的整帧性能验收。

同一开发模拟器对第三章右侧真实状态运行横向测试：**180/180 帧通过**，输出 272×160，原帧 X=32，额外绘制 4 个蓝军单位。渲染 P95 为 **1.9298 ms**，采样／重建／Bitmap 发布 P95 为 **3.6476 ms**，性能口径与上述第二章测试相同。真实横向输出截图 `.local/releases/EmberLily-wide-map-preview.png` 已目视确认；此结果仍属于开发模拟器，不能记为 Thor 实机验收。

宿主回归覆盖 5 项原生核心（新增 LCD 寄存器副本、读取不推进和关闭句柄检查）、5 项 RAM 原子事务、6 项 companion 运行时（新增逐帧图形采样不饿死语义发布）、2 项真实 FE8 场景及 5 项实验编辑／恢复，共 23 项通过；另有上述 **2 项真实扩景仪器测试**，竖向第二章和横向第三章分别通过。所有测试使用隔离目录和核心，未写入 Thor 存档。

测试模拟器创建 1280×720 副显示屏检查横竖屏重绑：主屏没有虚拟键和系统状态／导航栏；下屏会话页显示扩景状态、方向、A/B、Select/Start/L/R。截图留在 `.local/`。用户对旧版 M1 读取的 Thor 反馈不自动算作新版扩景通过；Thor 新版画面、持续音视频与实体按键回归仍需实机确认。
