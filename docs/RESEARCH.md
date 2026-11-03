# NightIcon 前期调研（RESEARCH）

> 目标：系统深浅色切换时，荣耀桌面图标在两套主题（.hwt/.hnt）的图标之间自动切换；只动桌面图标，不碰锁屏、壁纸、字体。
> 设备：Honor Magic7 Pro（~~PTP-AN00~~ **实测 PTP-AN10**，`PTP-AN10 11.0.0.110(SP7C00E110R102P1)`），MagicOS 11（**实测 SDK 37**，magic_api_level 43），KernelSU 3.3.1 + LSPosed 2.2.1（临时 root，重启失效）。
> 路线：**路线二**——预先解出两套主题的 `icons` 内层 zip 缓存，Hook framework 层或桌面的图标加载，按当前 uiMode 返回对应图标，配置变化时刷新桌面图标缓存。
> 调研日期：2026-11-03。参考仓库均为只读浅克隆（`refs/`，不在交付包内）。

置信度说明：**高** = 有 MagicOS 11 实证或代码直接可见；**中** = 只有 EMUI 10/11 反编译代码，荣耀分家后大概率沿用但未验证；**低** = 推测。

---

## 1. 关键结论（先看这里）

1. EMUI 11 framework 的主题图标链路**全部收口到一个调用**：`ZipFileCache.getAndCheckCachedZipFile(getThemeDir(), "icons")`。
   **【阶段1 已证实】** MagicOS 11 上类为 `hihonor.android.hwutil.ZipFileCache`（framework-magic.jar），调用方实际用 3 参版 `(getThemeDir(), "icons", "icons_backup")`，内部委托 2 参版。
   ~~`getThemeDir()` 恒返回 `/data/skin`~~ **【已推翻】** 桌面进程返回 `HWT_PATH_THEME + "/" + userId` = `/data/themes/0`，其它进程返回 `/data/themes/skin`；设备上不存在 `/data/skin`。
   → 只要在桌面进程里把 `zip == "icons"` 的那次调用的 `path` 换成我们的缓存目录，就能做到**只换图标、其它主题资源不动**。这是首选 Hook 点。
2. `ZipFileCache` 内部用 `ConcurrentHashMap<path + "/" + zip, ZipFileCache>` 缓存，因为 key 带 path，**浅色/深色两个目录会各自缓存、互不覆盖**，切换时不需要清 ZipFileCache 本身，只需要清上层的 Drawable 缓存和桌面 IconCache。
3. **【已证实】** framework 已有夜间处理：`HwResourcesImpl.getDarkerBitmap(Bitmap)`（private） 在 `uiMode & 0x30 == 0x20` 时对主题图标乘一层暗色（`COLOR_FOR_DARKER_ICON`）。深色套图本身已是暗色时可能被**二次压暗**，需要同时 Hook 掉或在深色槽位时跳过。
4. 荣耀桌面 `com.hihonor.android.launcher`（launcher3 同源，HonorHook 称"锚点未混淆"）**没有第三方图标包管理**，所以桌面层方案只能挂 launcher3 的 IconCache / 图标加载，不能走"官方图标包"捷径。
   **【阶段1 补充】** 桌面在 uiMode 变化时已自行 `IconCache.reCreate` + 全量 `ShortcutInfoUtils.reloadIcon`，且 `app_icons.db` 的 freshness 含 `uimode`，不需要我们 forceReload。
5. 主题包外层是明文 zip，无加密、系统侧无签名校验（HonorHook 40 号报告）；`icons` 是无扩展名的内层 zip，可以直接拷出来用。

---

## 2. 候选 Hook 点

> **阶段 1 核实结果（2026-11-03）见 `docs/ANALYSIS.md` §6**。要点：F1 ✅（包名 `hihonor.android.hwutil`）；F2 ⚠️（桌面进程为 `/data/themes/<userId>`）；F3 ✅（现为 6 参 private 重载）；F4 ✅；F5 ⚠️（扁平主题图不进 Drawable 缓存，无需清）；F6 ✅；F7 ✅（另有 `ZipResourcesAdapter` 静态状态、zygote 预载）；F8 ✅；F9 未核实；L1 不需要自做（桌面原生已刷新，类名 `com.hihonor.android.launcher.IconCache`）；L2 ✅（configChanges 含 uiMode）；L3/L4 存在；L6 兜底。

### 2.1 framework 层方案（在桌面进程内生效，作用域只需勾选桌面）

> 这些类在 boot classpath（EMUI 为 hwframework.jar；MagicOS 11 可能改名/迁入 framework-magic 等），每个 App 进程都会加载。
> 我们只在桌面进程里 Hook，所以不影响其它 App 与锁屏（锁屏在 SystemUI 进程）。

| # | 类名.方法名 | 进程/包 | 来源文件 | 置信度 | 作用 / MagicOS 11 需验证 |
|---|---|---|---|---|---|
| F1 ★ ✅ | ~~`huawei.android.hwutil`~~ → **`hihonor.android.hwutil`**`.ZipFileCache.getAndCheckCachedZipFile(String path, String zip)` (static) | 桌面进程（boot classpath） | `HWFramework/MATE-20_EMUI_11.0.0/src/main/java/huawei/android/hwutil/ZipFileCache.java:81` | 中 | `zip=="icons"` 时按 uiMode 替换 `path` 为 `<缓存>/light` 或 `<缓存>/dark`（目录内放名为 `icons` 的 zip）。验证：类名/包名是否改成 `hihonor.*`/`com.hihonor.*`；方法签名；桌面进程能否读到缓存目录（SELinux/权限）；是否仍用 `icons` 字面量 |
| F2 | `android.content.res.HwResourcesImpl.getThemeDir()` | 同上 | `.../android/content/res/HwResourcesImpl.java:2026` | 中 | 返回 `/data/skin`。**不建议直接改**：它也被 framework-res、各包名 zip、颜色/尺寸用到，改了会把整套主题都切掉。仅作理解链路 |
| F3 | `HwResourcesImpl.getThemeIcon(Resources, String imgFile, String packageName)` (private) | 同上 | `HwResourcesImpl.java:418` | 中 | 应用图标主入口（被 `loadDrawable` 调用，`HwResourcesImpl.java:1274`）。备选：完全接管返回 Drawable（要复刻自适应合成/缩放）。验证：是否仍 private、名字是否被混淆 |
| F4 | `HwResourcesImpl.getThemeIconByName(String name)` | 同上 | `HwResourcesImpl.java:263` | 中 | 按名字取图标（桌面用来取文件夹图标 `portal_ring_inner_holo.png`、动态图标等）。F1 生效后它自动跟随 |
| F5 | `HwResourcesImpl.loadDrawable(Resources, TypedValue, int id, Theme, boolean)` | 同上 | `HwResourcesImpl.java:1248` | 中 | 先查 `IconCache.contains("id#pkg")` 再取 `getColorDrawableCacheEx` / `getDrawableconstantState` 缓存。**切换时必须让这层缓存失效**（key 与深浅无关）。验证：缓存字段名（`mDrawableCacheEx`、`sPreloadedDrawablesEx`）与清理入口 |
| F6 | `HwResourcesImpl.getDarkerBitmap(Bitmap)` (private) | 同上 | `HwResourcesImpl.java:2270` | 中 | 夜间对主题图标乘暗色。深色槽位生效时让它原样返回，避免二次压暗。验证：MagicOS 11 是否仍有此逻辑（可能已移除或改成可配置） |
| F7 | `HwResourcesImpl.handleAddIconBackground(Resources, int, Drawable)` | 同上 | `HwResourcesImpl.java:354` | 中 | 无主题图标的 App 加底板/遮罩（`icon_mask.png`、`icon_background*`、`icon_border.png`）。底板素材也来自 `icons` zip，但被缓存在**静态字段** `sBmpMask/sBmpBorder/sBgList`，切换时需置 null 才会重读 |
| F8 | `huawei.android.hwutil.IconCache`（`add/contains/get`） | 同上 | `.../huawei/android/hwutil/IconCache.java` | 中 | 只记"资源 id#包名 → 图标文件名"，与深浅无关，**不需要改**；可用来确认某个 id 是否走主题图标 |
| F9 | `huawei.android.hwtheme.HwThemeManagerImpl.updateConfiguration(boolean)` | 调用方进程 | `.../huawei/android/hwtheme/HwThemeManagerImpl.java:247` | 中 | 官方换主题后的"全局刷新"做法：给 extra config item 1 加 1 再 `updateConfiguration`。可作为"让桌面重新加载图标"的参考，但它是全局的，**不要直接调用**（会让所有 App 重载主题） |

### 2.2 桌面层方案（`com.hihonor.android.launcher`）

| # | 类名.方法名 | 进程/包 | 来源 | 置信度 | 作用 / 需验证 |
|---|---|---|---|---|---|
| L1 ★ | `com.android.launcher3.icons.IconCache` 的清理/重载（如 `clear()`、`remove(ComponentName, UserHandle)`、`updateIconsForPkg`）+ `LauncherModel.forceReload()` | 桌面 | launcher3 AOSP；HonorHook `docs/research/2026-10-24-system-audit/17-桌面深挖.md`（launcher 18.0.0.259，7317 类，"锚点未混淆"） | 中 | uiMode 变化后清图标缓存并重载。验证：荣耀是否改名为 `com.hihonor.android.launcher.*`、IconCache 是否有 DB（`app_icons.db`）持久缓存需一并失效 |
| L2 | `Launcher.onConfigurationChanged(Configuration)` / `Application` 注册 `ComponentCallbacks` | 桌面 | AOSP；global-icon-pack `ReloadIconOnThemeChange.kt` 的 ThemeSig 思路 | 高（AOSP API） | 检测 `uiMode & UI_MODE_NIGHT_MASK` 翻转，触发 L1。验证：桌面 Activity 是否声明 `configChanges=uiMode`（声明了才会回调而不是重建） |
| L3 | `com.android.launcher3.icons.cache.LauncherActivityCachingLogic.loadIcon` / `BitmapInfo.newIcon` | 桌面 | `global-icon-pack-android/.../xposed/ReloadIconOnThemeChange.kt` | 中 | 参考 global-icon-pack：记住每个 BitmapInfo 的加载调用，主题签名（night + assetsSeq）变了就重新 load 替换 `icon` 字段。适合"不重建 IconCache 也能刷新" |
| L4 | `LauncherIcons` / `BaseIconFactory.createBadgedIconBitmap` | 桌面 | global-icon-pack `ReplaceIcon.kt` | 中 | 若 F 系列不可用，可在桌面侧直接把 Drawable 换成我们 zip 里的图（需自行处理遮罩） |
| L5 | `com.hihonor.android.launcher.feature.Features.checkFeature(String)` | 桌面 | HonorHook `17-桌面深挖.md` | 高（MagicOS 11 实证） | 只做观测：看有没有与主题图标/深色图标相关的云门开关 |
| L6（兜底） | 桌面进程内注册广播，收到后 `Process.killProcess(myPid())` 让桌面重启 | 桌面 | `IconsCustomizer/.../MainHook.kt:405-425` | 高 | 最粗暴但最可靠的刷新方式；切换时会有一次桌面闪烁 |

### 2.3 推荐顺序

1. **F1（ZipFileCache 替换 icons 路径）** —— 改动最小、只影响图标，先用 recon 拉回的 framework jar 确认类名与签名。
2. **F5 + F7 缓存失效**（反射清 `HwResourcesImpl` 的 Drawable 缓存与 `sBmpMask/sBmpBorder/sBgList`）+ **F6 跳过二次压暗**。
3. **L2 监听 uiMode 翻转 → L1 清桌面 IconCache 并 forceReload**（参考 L3 的 ThemeSig 判定）。
4. 若 F1 在 MagicOS 11 上不存在/被重构：退到 **F3/F4 直接接管**，或桌面侧 **L4 替换 Drawable**。
5. 任何刷新方案不稳定时，先用 **L6 重启桌面**兜底，保证功能可用。

### 2.4 作用域

- 荣耀桌面 `com.hihonor.android.launcher`：必需（framework 层 Hook 也是在桌面进程内安装）。
- 系统框架（scope.list 中的 `system`）：路线二原则上**不需要**；骨架里保留只为打日志侦察，确认后可删，减少 system_server 风险。
- 不要勾选 SystemUI（锁屏）和主题商店。

---

## 3. hwt / hnt 结构与 icons 内层 zip 命名规则

来源：`refs/honor-themes/README.md`（"The .hnt format" 一节，MagicOS 11 实测）、`refs/honor-hwt-theme-studio/hwtstudio/{blank.py,validation.py}`、HonorHook 40 号报告。

### 3.1 外层

- `.hwt` / `.hnt` 都是**普通 zip**（.hnt 一般 STORED 不压缩），无加密容器，系统侧无签名校验（只有权限门 + zip 完整性 + ≤300MB）。
- 必需条目（主题商店 `isValidThemeInfo()` 会检查）：`description.xml`、`unlock/theme.xml`、`wallpaper/home_wallpaper_0.jpg`、`wallpaper/unlock_wallpaper_0.jpg`、`preview/`（目录条目本身必须存在）、`icons`。
- `description.xml`：根元素 `<HwTheme>`（hwt）或 `<HnTheme>`（hnt），含 `title`/`title-cn`/`author`/`designer`/`version`/`font` 等。
- 其它按包名命名的内层 zip：`com.android.systemui`、`com.android.systemui.status`、`com.hihonor.android.launcher`（桌面颜色/日历颜色 `theme.xml`）、`framework-res-hwext` 等。**本模块只取 `icons`**。
- 应用后落地到 `/data/themes/<userId>/`（`/data/skin` 软链指向它），含 `icons`、`fonts`、`wallpaper`、`unlock`、`aod`、`com.android.systemui`、`com.hihonor.android.launcher` 等（HonorHook `02-系统UI定制.md`）。

### 3.2 `icons` 内层 zip

- 条目名 `icons`，**无扩展名**，内部 DEFLATED。framework 常量 `ICONS_ZIP_FILE = "icons"`。
- 图标查找顺序（`HwResourcesImpl.getThemeIcon`）：
  1. `<Activity 全类名>/` 目录存在 → 自适应合成（`foreground.png` + `background.png`）
  2. `<包名>/` 目录存在 → 自适应合成
  3. `<Activity 全类名>.png`（`IconCache` 记录 type==1 时用 activity 名，否则用包名）
  4. 回退 `<包名>.png`
- 两种格式：
  - **自适应**：`<包名或Activity类名>/foreground.png` + `/background.png`，各 294px，桌面经 `icon_mask.png` 露出中间 196px。
  - **扁平（旧）**：`<包名>.png`，218px，底板已烘焙；配套 `icon_background*.png`（底板，可多张随机）、`icon_border.png`（描边）、`icon_mask.png`（遮罩）、`transparent_config.xml`。
- 同包多入口的 App 用 Activity 类名区分，如 `com.android.contacts.activities.DialtactsActivity`（拨号）。
- 动态图标：`dynamic_icons/<包名>/...`，时钟（`ic_deskclock_dial.png` + `ic_deskclock_hour/minute/second`）、日历（`calendar_background.png`，文字颜色来自 `com.hihonor.android.launcher` zip 的 `theme.xml`）、天气（每种天气一张）。没有这个目录则显示静态图标。
- 文件夹图标：`portal_ring_inner_holo.png`（`.animate` 名会被映射为同名 png）。

### 3.3 缓存目录约定（NightIcon）

```
<桌面进程可读目录>/nighticon/
  light/icons     ← 浅色主题的 icons 内层 zip 原样拷贝
  dark/icons      ← 深色主题的 icons 内层 zip 原样拷贝
```
F1 替换后 `ZipFileCache` 的缓存 key 为 `<...>/light/icons` / `<...>/dark/icons`，两套各自缓存。
模块 App → 桌面进程的传递：优先 libxposed **远程文件**（`openRemoteFile`，文件名 `light_icons.zip` / `dark_icons.zip`），在桌面进程里复制到自己的 `filesDir/nighticon/<slot>/icons`。

注意：日历颜色等在 `com.hihonor.android.launcher` 这个内层 zip 里，**不在 icons 里**。只切 icons 时，日历动态图标的文字颜色仍跟随当前主题（可接受；后续如需要可同样替换该 zip，但会影响桌面其它主题色）。

---

## 4. 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 类名/签名不一致 | 参考代码是 EMUI 10/11（华为），MagicOS 11 可能改包名（`hihonor.*`）、合并到 `framework-magic.jar`、或被 R8 混淆 | recon 拉回 jar 后用 jadx 确认；Hook 侧按候选列表探测 + 失败只打日志 |
| ART 内联 | `getThemeDir()`、`getDarkerBitmap()` 等小方法可能被内联，Hook 不生效 | 优先 Hook 较大的方法（F1）；必要时对调用方 `deoptimize()` |
| 多层缓存 | `HwResourcesImpl` Drawable 缓存、静态底板 Bitmap、桌面 `IconCache` 内存 + `app_icons.db` 持久缓存 | 切换时逐层失效；不行就 L6 重启桌面 |
| 二次压暗 | `getDarkerBitmap` 夜间对主题图标乘暗色 | 深色槽位生效时跳过（F6） |
| 文件可读性 | 桌面进程（普通 App UID）读不到模块私有目录；SELinux 也可能拦 /data/local/tmp | 用 libxposed 远程文件下发，再落到桌面自己的 filesDir |
| 自适应/扁平混用 | 两套主题格式不同时，底板/遮罩来自各自 zip，静态缓存不清会串 | 切换时清 `sBmpMask/sBmpBorder/sBgList` |
| 临时 root | 重启后 KernelSU/LSPosed 失效，模块不生效，桌面回落为当前已应用主题 | 预期行为；建议把"浅色主题"设为系统实际应用的主题，失效时至少外观一致 |
| 桌面崩溃/安全模式 | 桌面反复崩溃可能触发 LSPosed 安全模式 | 全部 Hook 用 `ExceptionMode.PROTECTIVE`，失败即放行原逻辑 |
| 主题商店改写 | 用户在主题商店重新应用主题、或系统 OTA 后 `/data/themes` 变化 | F1 只替换 `icons`，不依赖 `/data/themes` 内容；无影响 |
| 锁屏/负一屏/卡片 | 锁屏在 SystemUI、负一屏/卡片可能在独立进程，不受影响（符合需求） | 无需处理；如需负一屏图标一致，另议 |

---

## 5. 需要在真机上确认的问题（阶段 0，用 `scripts/recon.sh`）

1. 桌面与主题商店的真实包名、版本（HonorHook 记录：launcher 18.0.0.234/259，thememanager 20.1.63.305/20.1.65.302）。
2. `framework.jar` / `hwframework.jar` / `framework-magic*.jar` 中是否存在 `ZipFileCache`、`HwResourcesImpl`（或 Hn* 改名），方法签名与 `"icons"` 常量。
3. `/data/themes/0/` 的实际结构：`icons` 是否仍是内层 zip、是否还有 `icons` 以外的图标来源（如 `dynamic_icons` 单独目录、`/themes/diff/icons` 差异化主题）。
4. 当前 `uiMode`、`themed_icon_enabled`（图标跟随主题）等设置键取值；"图标跟随主题"开启时是否走另一条链路（Monet 单色图标）。
5. 系统深浅色切换时，桌面是**重建 Activity** 还是**收到 onConfigurationChanged**；`HwResourcesImpl` 的 Drawable 缓存是否会被系统自动清掉（ThemeProvider/主题商店是否发广播）。
6. 夜间模式下系统是否仍对主题图标做 `getDarkerBitmap` 压暗。
7. LSPosed 2.2.1 实际支持的 libxposed API 版本（骨架按 101 编译、`minApiVersion=101`；若确认支持 102 可升到 102 与 HonorHook 对齐）。

---

## 6. 阶段 1 真机核实摘要（2026-11-03）

- §5 问题 1：桌面 18.0.0.259（/data/app 更新版）；主题商店 20.1.67.302。
- §5 问题 2：无 hwframework.jar；`HwResourcesImpl`（名字未改）与 `hihonor.android.hwutil.ZipFileCache` 在 framework-magic.jar；`ResourcesImpl`/`AbsResourcesImpl`/`HwThemeManager` 在 framework.jar；常量仍是 `"icons"`，新增 `"icons_backup"`。
- §5 问题 3：`/data/themes/skin → /data/themes/0 → /data/themes/user/0/theme/theme_b`（A/B 槽位）。当前 icons 为扁平 png（约 7970 个 `<包名>.png`，218px）+ `icon_mask.png`、`icon_background_01.png`、`dynamic_icons/`；无自适应目录。`theme_a/icons` 与 `theme_b/icons` 相同。
- §5 问题 4：`global/themed_icon_enabled=0`（Cube SVG 关闭，走 zip 链路）；`secure/ui_night_mode=0`（auto），自定义 22:00–07:00。
- §5 问题 5：`configChanges` 含 uiMode → `onConfigurationChanged`，不重建；HwResourcesImpl 不会因 uiMode 清缓存（只在主题 extraConfig item 1 变化时）。
- §5 问题 6：代码上仍有 `getDarkerBitmap`；但 `ResourcesImpl.updateConfiguration` 可能把桌面进程 ResourcesImpl 的 uiMode 强制改回白天（取决于主题 launcher zip 是否有 `night/`，未核实），需真机日志确认。
- §5 问题 7：未核实（侦察日志只证明 LSPosed 在运行）。
- §3.3 缓存目录约定维持不变。
