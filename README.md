# 霁梦输入法

> GPL-3.0 · Android 8.0+ · 只做 arm64

用 librime 当引擎、雾凇拼音当词库的拼音输入法。键盘那一层的代码移植自 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)，因为它已经把那堆琐碎但必要的东西做完了：长按气泡、九宫格、候选栏状态机。自己从头写一遍没有意义。

## 为什么又做一个输入法

市面上的拼音输入法基本都要联网。不是「偷偷联网」，是明着写进隐私政策的那种。我不想在别人的服务器上留一份自己打过什么字的记录，也不信「我们不会上传」这句话。

所以这个项目的前提很简单：**除了你手动开的两个功能，它没有别的联网路径。** 一个是插件商店的下载，一个是你自己填的翻译服务器。代码全在这里，可以自己查。

## 现在有什么

**输入**

- 雾凇拼音，librime 驱动，简繁都支持，中英混输
- 左边一列拼音音节，点哪一格就锁哪一格改谐音，改完不丢已经上屏的句子
- 预测候选：上一轮打的词会挂在候选栏，按下输入键才切回工具栏

**键盘**

- 长按弹按键预览气泡，能探出键盘上沿
- 长按展开九宫格扩展字符，带中文标点
- 上滑出符号、按住退格连发、空格左右滑移光标
- Caps 双击 / 长按三态（无 / 一次 / 锁定）
- `?123` / `ABC` / `!?#` 布局切换只是导航，不打断预测和候选
- 候选栏是 fcitx5 `KawaiiBarStateMachine` 那套：Idle / Candidate / Title 三态
- 表情和颜文字面板

**语音**

内置 sherpa-onnx，说话直接上屏，不用跳别的 App。模型 239MB，设置页按需下。

录音这块处理得比较细，因为踩过的坑都写在这了：

- 开麦前请求 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，让正在放的音乐压低音量
- 拿不到焦点也照常录——焦点只管「别人让不让路」，不让路不代表不能走
- 麦克风真被占了才提示「被其他应用占用」
- 来电、导航播报把焦点抢走，立即中止这次录音。不然别人的声音会被当成你说的
- 收起键盘、切输入框、切到后台，立刻释放麦克风。不在后台偷录

**外观**

- 深浅主题跟随系统，内置 fcitx5 默认主题
- 皮肤包：网格布局，可导入、复制、删除、改清单
- 字体自己从存储导 `.ttf` / `.otf` / `.ttc`，不进 APK
- 剪贴板历史和快捷粘贴

## 一些你可能想知道的

**为什么 GPL-3.0**

雾凇拼音是 GPL-3.0，词库又打包进了 APK，传染性绕不开。LGPL-2.1 允许升到 GPL-3，BSD 和 Apache-2.0 都能并进去，所以整体只能 GPL-3.0。

想换宽松协议，就得把雾凇词库从 APK 里拿掉，改成运行时让用户自己装。

**为什么字体和模型要声明 `noCompress`**

`.ttf` 默认会被 zip 压进 APK。`Typeface.createFromAsset` 读到压缩流，得先整份解压到临时文件再交给底层。最胖那款字体 10MB，每次键盘重建、每次设置页刷新都要走一遍，肉眼可见地卡。声明成 `noCompress` 后原样存储，直接 mmap。

onnx 同理，但更狠：239MB 压不动（存压都是 239MB），压缩存的话 sherpa-onnx 拿不到可 seek 的 fd，每次加载都得先解一份到临时文件——冷启动直接崩到十几秒，还额外占 239MB 空间。

代价是 APK 变大点，值。

**为什么 `local.properties` 优先于 `gradle.properties`**

内置翻译服务的 key 从这个顺序取：`local.properties` → `gradle.properties` / `-P` → 环境变量。

`gradle.properties` 是仓库里的文件，写进去了提交时就跟着上去了，等于公开。`local.properties` 在 `.gitignore` 里，每个开发者本地一份、永不入库。所以更安全的那个排前面。

什么都没配就留空，内置服务显示「未配置」，功能不报错。

**关于代码注释**

注释写得比较啰嗦，是刻意的。这块代码经常隔好几周才回来改一次，不写清「当时为什么这么写」，下次就会把它改回去，然后再踩一遍同样的坑。`app/build.gradle.kts` 里关于压缩的那段是个例子。

## 编译

要 Android SDK、NDK，以及 `arm64-v8a` 的 librime 预编译库。

```bash
./gradlew :app:assembleDebug
```

仓库带了两个脚本：

```bash
./build.sh :app:assembleDebug     # 编译，只打印错误和结果
./release.sh <tag> <apk路径>       # 打 Release 并上传 APK
```

`build.sh` 里 `cd /root/jm3` 是原作者机器的路径，你得改成自己的克隆目录。`release.sh` 会从 remote URL 里取 token，也能用 `GITHUB_TOKEN` 环境变量覆盖。

## 版本流程

节奏分三层：**提交记进度，tag 记 beta，最后一个干净的才叫正式版**。

| 阶段 | 干什么 |
|---|---|
| 平时 | 做完一小块就 commit，写清做了什么 |
| 出 beta | 编译零告警 → 升版本号 → 真机自测 → 打 tag → push → APK 挂 Release |
| 收尾 | 功能和代码都干净了，去掉 `-betaN`，打正式 tag |

几条硬规矩：

- **每打一个装机的包，版本号必须往上走。** `versionCode` 一定 +1，Android 靠它判新旧
- `versionName` 按改动大小进位：改 bug 调样式走第三位（`0.4.0` → `0.4.1`），加功能换行为走第二位（`0.4.1` → `0.5.0`）
- beta 号在同一个 `X.Y.Z` 里可以连号；`X.Y` 一进位，beta 重新从 1 数
- 绝不允许两个不同的包共用同一个 `versionName`——装上之后没法分辨新旧
- beta 用 tag 标，不用分支。`main` 一直往前走，tag 钉住「那一版长什么样」
- 推上去的 tag 不要改名或删除，要改就升新号
- 每个 beta 都要发 Release 并挂 APK。仓库不收 `*.apk`，Release 是唯一下载入口，只 push tag 不发 Release 等于没发布

### 发一版

```bash
./build.sh :app:clean :app:assembleDebug

# 改 app/build.gradle.kts 里的 versionCode / versionName

git add -A && git commit -m "..."
git tag -a v1.0.1 -m "这一版做了什么"
git push origin main && git push origin v1.0.1

./release.sh v1.0.1 app/build/outputs/apk/debug/jimeng-1.0.1-debug.apk
```

`release.sh` 会读 tag 注释当说明。tag 名里有 `beta` 就自动发成 prerelease，没有就是正式版；想强制指定给 `PRERELEASE=true/false`。

### 已发布

见 `git tag -l` 或仓库的 Releases 页。当前是 `jimeng-ime`，对应 v1.0.0。

## 代码在哪

```
app/src/main/java/com/jimeng/ime/
├── JimengIME.kt          输入法服务主体：窗口、insets、候选栏接线（3000 行，最大的一坨）
├── core/                 引擎事件模型（FcitxEvent、FormattedText）
├── input/preedit/        编码区：音节锁、谐音、分段
├── rime/                 librime JNI 封装、方案管理
├── kb/
│   ├── keyboard/         键盘视图层（移植自 fcitx5-android）
│   ├── popup/            气泡与长按面板
│   ├── picker/           表情 / 符号面板
│   ├── clipboard/        剪贴板
│   ├── prefs/            偏好设置
│   ├── theme/            主题与皮肤
│   ├── utils/            拼音切分之类
│   ├── page/             剪贴板页、文本编辑页、键盘高度页
│   ├── core/             keysym、KeyState
│   └── RimeKeyActionDispatcher.kt   按键 → 引擎动作
├── view/                 候选栏、状态机、自绘面板
├── voice/                sherpa-onnx 语音识别与录音条
├── translate/            翻译栏与翻译服务
├── plugin/               插件清单、安装、商店
├── SetupActivity.kt      设置首页
├── SettingsActivity.kt   键盘设置页
├── KeyboardActivity.kt   键盘管理
├── SkinActivity.kt       皮肤管理
├── StoreActivity.kt      插件商店
├── ModelManagerActivity.kt  模型管理
├── AboutActivity.kt      关于
├── PrivacyActivity.kt    隐私政策
└── LicensesActivity.kt   第三方开源许可
```

大约 3.1 万行 Kotlin（不含内置的 sherpa 绑定），其中四分之一是注释。

## 隐私

只有两处会联网：插件商店下载、翻译。翻译会把待译文本发到你填的服务器，这是它唯一把文字送出去的路径，所以界面上给了黄框警示。录音只在内存里过，不落盘。输入内容不进日志。没有广告、统计、崩溃上报 SDK。

权限只有四个：`VIBRATE`、`RECORD_AUDIO`、`INTERNET`、`ACCESS_NETWORK_STATE`。

## 许可证

GPL-3.0，原因见上面。

| 组件 | 许可证 | 怎么用的 |
|---|---|---|
| [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) | LGPL-2.1-or-later | 键盘视图层、气泡长按体系、候选栏状态机（各文件保留原始 SPDX 头） |
| [librime](https://github.com/rime/librime) | BSD-3-Clause | 引擎，静态链接进 `libjimeng_rime.so` |
| [rime-ice / 雾凇拼音](https://github.com/iDvel/rime-ice) | GPL-3.0 | 方案与词库，打包在 `assets/rime/` |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | 语音识别，模型按需下载 |
| AndroidX / splitties / kotlinx | Apache-2.0 | Gradle 依赖 |

`libjimeng_rime.so` 里还静态链了 OpenCC、Boost、leveldb、marisa-trie、yaml-cpp、glog，各自许可证见 App 内「第三方开源许可」页。

## 用到的开源

这个项目能存在主要靠这两件事：rime 生态已经解决了中文输入的核心问题，fcitx5-android 已经解决了 Android 键盘的 UI 问题。我只是把它们接起来，加了自己需要的东西。
