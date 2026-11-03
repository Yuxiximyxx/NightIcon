# NightIcon v0.2.0

荣耀 MagicOS（PTP-AN10 / MagicOS 11）LSPosed 模块：系统深浅色切换时，**桌面图标**在两套主题（.hwt/.hnt）的图标之间自动切换。
只作用于荣耀桌面 `com.hihonor.android.launcher`，不碰锁屏、壁纸、SystemUI，也不修改任何主题文件。

> 0.1.0 的日夜切换已在荣耀 PTP-AN10（MagicOS 11，KernelSU + LSPosed）上验证。0.2.0 只改设置页和图标，Hook 行为没有改。

## 原理

1. **选主题（模块 App）**：用 SAF 只读打开 .hwt/.hnt，解出其中的内层 zip `icons`（有 `icons_backup` 也一并解出），校验能当 zip 打开。没有 `icons` 会提示「该主题不包含图标」。
2. **下发（立即应用）**：模块 App 通过 libxposed `XposedService.openRemoteFile` 把文件写成**远程文件**，再在**远程偏好** `nighticon` 里写版本号和令牌。
   桌面进程里的 Hook 收到偏好变化后，用 `XposedInterface.openRemoteFile` 只读打开，拷到 `<桌面数据目录>/files/nighticon/{light,dark}/v<版本>/`，校验通过后才启用该槽位。
   - 为什么用远程文件和远程偏好：这是 LSPosed 官方给模块的跨进程通道，不需要放宽文件权限、不用 world-readable、不需要 root，桌面进程也读得到。
   - 为什么目录带版本号：`ZipFileCache.getAndCheckCachedZipFile` 按「路径/zip 名」永久缓存结果，**连「文件不存在」的 null 也缓存**。每次换主题都用新目录，就不会撞上旧缓存。
3. **改道**：Hook `hihonor.android.hwutil.ZipFileCache.getAndCheckCachedZipFile`，2 参和 3 参两个版本都 Hook，在方法执行前改参数。
   - 当 zip 名是 `icons`/`icons_backup`，且路径是主题目录（`/data/themes/<id>`、`/data/themes/skin`、`/data/themes/user/<id>/theme/theme_x`）时，把路径换成当前深浅色对应的槽位目录。
   - 槽位未就绪时不改道，桌面照常使用系统当前主题。
4. **翻转**：Hook `HwResourcesImpl.updateConfiguration` 在方法执行之后，读入参 `config.uiMode` 判断深浅；发生翻转时只做**软重置**：
   - `needReloadMask/Border/Bg = true`、`isBackgroundLoaded = false`、`sBgList = null`、`ZipResourcesAdapter.sIsNeedCheckMultiLevel = true`。
   - 不 recycle 任何 Bitmap，也不调用 `clearHwThemeZipsAndSomeIcons`。
   - 之后桌面自己会在 uiMode 变化时重建 IconCache 并重载全部图标；这一点在反编译里已确认。
5. **夜间不压暗**：夜间套生效时，`HwResourcesImpl.getDarkerBitmap` 原样返回参数，避免系统再给夜间图标叠一层灰。它的调用方都做了 `deoptimize`，防止内联导致 Hook 失效。

## 使用

1. 安装 APK，在 LSPosed 启用 NightIcon，作用域**只勾「荣耀桌面」**。
2. 打开 NightIcon：
   - 打开「总开关」。
   - 点「白天主题」「夜间主题」，分别选一个主题文件（默认定位到 `/storage/emulated/0/Honor/Themes`）。
   - 点「立即应用」。
3. 图标没有变化时，点「重启桌面」。
4. 切换系统深浅色，观察图标。
5. 「关于」显示版本号和仓库地址，点一下用浏览器打开 https://github.com/Yuxiximyxx/NightIcon 。

设置页用 Miuix 0.7.2（`top.yukonga.miuix.kmp:miuix`）按效果图画：大标题、圆角卡片、「主题 / 状态 / 其他」分组。0.8 及以上要 Kotlin 2.3+，0.9.4 要 Kotlin 2.4 和 AGP 9，和当前工具链冲突，所以停在与 Kotlin 2.2.21 对齐的 0.7.2。界面跟随系统深浅色。

启动图标是自适应图标（前景、背景、单色）加各密度的旧版 png。图案按候选 1（昼夜分屏格子），并收进圆形遮罩的安全区，避免太阳和月牙被切掉。

「重启桌面」的做法：
- 优先用 root 执行 `am force-stop com.hihonor.android.launcher`。这样不依赖 Hook 是否生效，桌面卡住时也能用；系统会自动重新拉起默认桌面。
- 没有 root 或没授权时，退回到写远程偏好令牌，由桌面进程里的 Hook 自己 `killProcess`。

## 已知风险

- **类名、方法名、字段名**全部来自本机 framework-magic.jar 和桌面 APK 的反编译结果。系统升级后可能失效；失效时只打日志，不会崩溃（`ExceptionMode.PROTECTIVE`，反射全部 `runCatching`）。
- **ZipFileCache 被 AOT 内联**：boot image 里调用方如果把它内联了，改道就不会生效。日志里如果没有「路径替换」，就是这个问题。
- **uiMode 被改回白天**：当前主题如果含 `com.hihonor.android.launcher` 内层 zip 且其中没有 `night/`，系统可能把桌面 ResourcesImpl 的 uiMode 强制为白天。
  - 我们按入参判断深浅，同时在日志里打印两者是否一致。
  - 这种情况也可能影响桌面 `app_icons.db` 是否失效、能否自动刷新。
- **自适应格式主题**：`<pkg>/foreground.png` 格式的主题，图标可能残留在 `ResourcesImpl.mDrawableCache` 里，切换后显示旧图。本版本没有处理，需要真机观察。
- **「立即应用」时的刷新**：会反射调用以下方法；任何一步失败就请用「重启桌面」：
  - `IconCache.getInstace().clearCacheAndDb(true)`
  - `Launcher.getLauncher().updateIconCache()`
  - `LoadIconUtil.launchUpdateIconTask(null, null)`
- **远程偏好变化监听**：在桌面进程里能否实时收到变化，取决于 LSPosed 的实现。收不到时，「立即应用」和兜底的「重启桌面」都要靠 root 重启桌面后才生效。冷启动时 Hook 会主动同步一次。
- 首次同步会在桌面进程里拷贝几十 MB 文件（后台线程）。拷完之前不改道。
- 选主题时会把整个主题包临时拷进 App 缓存目录，用完即删。

## 调试

```
adb logcat -s NightIcon
```

主要日志：
- `已 Hook ...` / `类不存在 ...` / `方法不存在 ...`：各 Hook 是否装上
- `同步完成：总开关=… 白天=… 夜间=…`：槽位是否就绪
- `路径替换[2参/3参] /data/themes/0/icons → …/nighticon/dark/vXXX/icons`：每次改道（LSPosed 日志里只在内容变化时记录）
- `入参 uiMode=0x.., mResourcesImpl uiMode=0x..（一致/不一致！）`
- `深浅翻转：白天 → 夜间，本轮 getDarkerBitmap 调用 N 次（跳过 M 次）`
- `getDarkerBitmap 被调用（第 n 次）`
- `软重置（…）：[字段列表]`

## 构建

```
cd app
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug testDebugUnitTest   # JDK 17+
```

libxposed 从 Maven Central 拉取，不需要 `mavenLocal`。
单元测试（`app/src/test`）用自己构造的小 zip 覆盖这些情况：.hwt、STORED 格式的 .hnt、没有 icons、icons 不是合法 zip、icons_backup 损坏、扩展名过滤、路径判断。有侦察数据时，还会用真实 icons 跑一遍。
