# 霁梦输入法 · Jimeng IME

> 仓库：<https://github.com/selenaflora927-dot/jimeng-ime> · 许可证：**GPL-3.0**

一个基于 **librime** 引擎的 Android 拼音输入法。键盘层移植自 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)，词库使用 [雾凇拼音](https://github.com/iDvel/rime-ice)。

## 特性

- 雾凇拼音方案，librime 引擎驱动，支持简繁、中英混输
- 真 View 键树（移植 fcitx5-android）：
  - 长按弹出按键预览气泡，可探出键盘上沿
  - 长按展开九宫格扩展字符面板（带中文标点显示）
  - 上滑出符号、按住退格连发、空格左右滑移光标
  - Caps 双击 / 长按三态（无 / 一次 / 锁定）
- 表情 / 颜文字面板
- 深色 / 浅色主题跟随系统

## 构建

需要 Android SDK、NDK，以及 `arm64-v8a` 的 librime 预编译库。

```bash
./gradlew :app:assembleDebug
```

## 目录结构

```
app/src/main/java/com/jimeng/ime/
├── JimengIME.kt              输入法服务主体（窗口、insets、候选栏接线）
├── rime/                     librime JNI 封装
├── kb/
│   ├── keyboard/             键盘视图层（移植自 fcitx5-android）
│   ├── popup/                气泡与长按面板
│   ├── picker/               表情 / 符号面板
│   ├── theme/                主题
│   └── core/                 keysym、KeyState 等基础定义
├── view/                     候选栏、面板等自绘 View
└── SetupActivity.kt          设置 / 自检页
```

## 许可证

本项目以 **GPL-3.0** 发布，原因见下表中 rime-ice 部分。

### 依赖与出处

| 组件 | 许可证 | 使用方式 |
|---|---|---|
| [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) | LGPL-2.1-or-later | 键盘视图层、气泡与长按面板体系的代码移植（各文件保留原始 SPDX 头） |
| [librime](https://github.com/rime/librime) | BSD 3-Clause | 引擎，静态链接为 `libjimeng_rime.so` |
| [rime-ice / 雾凇拼音](https://github.com/iDvel/rime-ice) | **GPL-3.0** | 方案配置与词库，打包于 `app/src/main/assets/rime/` |
| AndroidX / splitties / kotlinx 等 | Apache-2.0 | Gradle 依赖 |

### 为什么是 GPL-3.0

雾凇拼音（rime-ice）以 GPL-3.0 授权，而本项目将其方案与词库打包进 APK 分发。GPL-3.0 的传染性使整体分发须以 GPL-3.0 授权。

LGPL-2.1-or-later 允许升级为 GPL-3.0，BSD 3-Clause 与 Apache-2.0 均可并入 GPL-3.0，因此三者与 GPL-3.0 兼容。

若希望本项目以更宽松的许可证发布，需要移除打包的雾凇词库，改为让用户在运行时自行安装。
