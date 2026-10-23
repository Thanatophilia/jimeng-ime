# 霁梦输入法 · Jimeng IME

> 仓库：<https://github.com/selenaflora927-dot/jimeng-ime> · 许可证：**GPL-3.0**

一个基于 **librime** 引擎的 Android 拼音输入法。键盘层移植自 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)，词库使用 [雾凇拼音](https://github.com/iDvel/rime-ice)。

## 特性

**输入**

- 雾凇拼音方案，librime 引擎驱动，支持简繁、中英混输
- 音节级编码区：左侧一列拼音音节，点任意一格即可锁定并改谐音
- 选中的音节可上下切换谐音变体，改完不丢已上屏的句子
- 预测候选：上一轮上屏的词继续挂在候选栏，按下输入键后切回工具栏

**键盘**

- 真 View 键树（移植 fcitx5-android）：
  - 长按弹出按键预览气泡，可探出键盘上沿
  - 长按展开九宫格扩展字符面板（带中文标点显示）
  - 上滑出符号、按住退格连发、空格左右滑移光标
  - Caps 双击 / 长按三态（无 / 一次 / 锁定）
  - 布局切换（`?123` / `ABC` / `!?#`）是纯导航动作，不打断预测与候选
- 候选栏状态机移植 fcitx5 `KawaiiBarStateMachine`：Idle / Candidate / Title 三态，候选栏显示与收起跟随引擎状态
- 表情 / 颜文字面板

**语音**

- 内置 sherpa-onnx 语音识别，说话直接上屏，不需要跳转别的 App
- 识别结果先挂候选栏：安静 0.8 秒才搬进输入框，中途可以改
- 模型体积较大（约 239MB），在设置页按需下载

**外观与扩展**

- 深色 / 浅色主题跟随系统，内置 fcitx5 默认主题
- 皮肤包：网格布局，可导入、复制、删除、改清单
- 字体：自己从手机存储导 `.ttf` / `.otf` / `.ttc`，不进 APK
- 插件商店：本地导入或在线下载，走同一条安装链路
- 剪贴板历史与快捷粘贴

## 构建

需要 Android SDK、NDK，以及 `arm64-v8a` 的 librime 预编译库。

```bash
./gradlew :app:assembleDebug
```

仓库里带了两个脚本：

```bash
./build.sh :app:assembleDebug                                   # 编译并只打印错误/结果
./release.sh v0.86.0-beta1 app/build/outputs/apk/debug/jimeng-0.86.0-beta1-debug.apk
```

## 版本流程

开发节奏分三层，**提交（commit）记进度、标签（tag）记 beta、最后一个干净版才叫正式版**：

| 阶段 | 动作 | 产物 |
|---|---|---|
| 平时开发 | 每做完一小块就 `git commit`，写清做了什么 | 一条条提交记录 |
| 出一个 beta | ① 检查代码（编译零告警、无用代码清理）② 升 `versionCode` / `versionName` ③ 打包真机自测 ④ 打 tag `vX.Y.Z-betaN` ⑤ push 提交 + tag ⑥ 把 APK 挂到 Release | `jimeng-X.Y.Z-betaN-debug.apk` + tag + Release |
| 研发完毕 | 确认功能完整、代码干净，升版本号去掉 `-betaN`，打 tag `vX.Y.Z` | 正式版 |

约定：

- **每打一个装机的包，版本号必须往上走一格**。`versionCode` 一定 +1（Android 靠它判断新旧），
  `versionName` 按改动大小进位：
  - 只清代码 / 修小 bug / 调样式 → 第三位进位：`0.4.0-beta1` → `0.4.1-beta1`
  - 加了新功能、换了行为 → 第二位进位：`0.4.1-beta1` → `0.5.0-beta1`
- **beta 号在同一个 `X.Y.Z` 里可以连号**：`0.4.1-beta1` / `-beta2` / `-beta3`；
  一旦 `X.Y` 进位，beta 号重新从 1 开始
- 绝不允许两个不同的包共用同一个 `versionName`——装机后没法分辨哪份是新的

- **beta 用 tag 标记，不用分支**。主干 `main` 一直往前走，tag 钉住"那一版长什么样"
- tag 一律 `v` 开头：`v0.4.0-beta1`（beta）、`v0.5.0`（正式版）
- 每次 beta 之前必做的检查：`./build.sh :app:clean :app:assembleDebug` 通过、Kotlin 零告警、无未使用 import、无死代码
- 已推送的 tag 不要改名或删除；要改就升一个新号
- 每个 beta 都要发 GitHub Release 并把 APK 挂上去。仓库里不收 `*.apk`，Release 是唯一的下载入口，只 push tag 不发 Release 等于这一版没发布

已发布版本见 `git tag -l -n1`（或 GitHub 仓库的 Tags / Releases 页）。当前最新为 `v0.86.0-beta1`。

### 发一个 beta 的完整命令

```bash
# ① 检查 + 打包
./build.sh :app:clean :app:assembleDebug

# ② 升版本号（app/build.gradle.kts 里的 versionCode / versionName）

# ③ 提交改动
git add -A && git commit -m "feat: ..."

# ④ 打 tag（注释里写清这版做了什么）
git tag -a v0.86.0-beta1 -m "beta 1：..."

# ⑤ 推代码 + tag
git push origin main && git push origin v0.86.0-beta1

# ⑥ 把 APK 挂到 GitHub Release，方便网页直接下载
./release.sh v0.86.0-beta1 app/build/outputs/apk/debug/jimeng-0.86.0-beta1-debug.apk
```

`release.sh` 会读 tag 的注释当作 Release 说明，并打印最终下载地址。

> APK 不进 git 仓库（`.gitignore` 里挡了 `*.apk`），避免仓库被二进制文件撑大；包统一挂 Release。

## 目录结构

```
app/src/main/java/com/jimeng/ime/
├── JimengIME.kt              输入法服务主体（窗口、insets、候选栏接线）
├── core/                     FcitxEvent、FormattedText 等引擎事件模型
├── input/preedit/            编码区（音节锁、谐音、分段）
├── rime/                     librime JNI 封装、方案管理、日语插件
├── kb/
│   ├── keyboard/             键盘视图层（移植自 fcitx5-android）
│   ├── popup/                气泡与长按面板
│   ├── picker/               表情 / 符号面板
│   ├── clipboard/            剪贴板历史
│   ├── prefs/                输入法偏好
│   ├── theme/                主题与皮肤管理
│   ├── utils/                拼音切分等工具
│   ├── page/                 剪贴板页、文本编辑页、键盘高度调整页
│   ├── core/                 keysym、KeyState 等基础定义
│   └── RimeKeyActionDispatcher.kt  librime 引擎适配层（按键 → 引擎动作）
├── view/                     候选栏、状态机、面板等自绘 View
├── voice/                    sherpa-onnx 语音识别与录音条
├── plugin/                   插件清单、安装、商店
└── SetupActivity.kt          设置 / 自检页
```

## 许可证

本项目以 **GPL-3.0** 发布，原因见下表中 rime-ice 部分。

### 依赖与出处

| 组件 | 许可证 | 使用方式 |
|---|---|---|
| [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) | LGPL-2.1-or-later | 键盘视图层、气泡与长按面板体系、候选栏状态机的代码移植（各文件保留原始 SPDX 头） |
| [librime](https://github.com/rime/librime) | BSD 3-Clause | 引擎，静态链接为 `libjimeng_rime.so` |
| [rime-ice / 雾凇拼音](https://github.com/iDvel/rime-ice) | **GPL-3.0** | 方案配置与词库，打包于 `app/src/main/assets/rime/` |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | 语音识别引擎，模型按需下载 |
| AndroidX / splitties / kotlinx 等 | Apache-2.0 | Gradle 依赖 |

### 为什么是 GPL-3.0

雾凇拼音（rime-ice）以 GPL-3.0 授权，而本项目将其方案与词库打包进 APK 分发。GPL-3.0 的传染性使整体分发须以 GPL-3.0 授权。

LGPL-2.1-or-later 允许升级为 GPL-3.0，BSD 3-Clause 与 Apache-2.0 均可并入 GPL-3.0，因此均可与 GPL-3.0 兼容。

若希望本项目以更宽松的许可证发布，需要移除打包的雾凇词库，改为让用户在运行时自行安装。
