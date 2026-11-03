# NightIcon 阶段 1：真机侦察数据分析（ANALYSIS）

> 数据来源：Mac `~/nighticon-recon/20261103-211343/`（手机侦察包），拷到 box `/workspace/nighticon/recon/`。
> 设备实测：**PTP-AN10**，`PTP-AN10 11.0.0.110(SP7C00E110R102P1)`，Android 17 / SDK 37，`ro.build.magic_api_level=43`，MagicOS_11.0.0。
> 桌面 `com.hihonor.android.launcher` 18.0.0.259（`/data/app` 有更新版，与 `/system/app/HnLauncher6` 同 versionCode 18000259，targetSdk 37）；Home Activity `.unihome.UniHomeLauncher`；进程名 = 包名（dumpsys 未见 `process=` 覆盖）。
> 反编译：jadx 1.5（`/workspace/nighticon/tools/jadx`）。framework-magic.jar 全量（2242 类）；HnLauncher6.apk 全量（13263 类）；framework.jar 只按需单类反编译（`ResourcesImpl`、`AbsResourcesImpl`、`Resources`、`ApplicationPackageManager`、`LauncherActivityInfo`、`HwThemeManager`）。
> 原则：下文所有类名/方法名/字段名都是反编译代码里**实际看到**的；没看到的明确标"未看到/未核实"。

---

## 1. 结论速览

| 项 | 结论 |
|---|---|
| 首选 Hook | `hihonor.android.hwutil.ZipFileCache.getAndCheckCachedZipFile(String path, String zip)`（public static，**在 framework-magic.jar**），只在桌面进程、`zip` 为 `"icons"` / `"icons_backup"` 且 `path` 是主题目录时替换 `path` |
| 刷新时机 | `android.content.res.HwResourcesImpl.updateConfiguration(Configuration, int)` 的 after-hook：发现入参 `config.uiMode & 0x30` 翻转 → 软重置 HwResourcesImpl / ZipResourcesAdapter 的静态底板缓存（不 recycle）。桌面自己会在 uiMode 变化时重建 IconCache 并重载所有图标（已在反编译中确认），**不需要我们去 forceReload** |
| 二次压暗 | `HwResourcesImpl.getDarkerBitmap(Bitmap)`（private）确实存在，夜间 `drawColor(0xFFF5F5F5 /*-657931*/, MULTIPLY)`。深色槽位生效时 Hook 成原样返回 |
| 当前主题 | `/data/themes/skin → /data/themes/0 → /data/themes/user/0/theme/theme_b`（A/B 槽位），主题"琉光 (动态)" 8.0.18；`theme_a/icons` 与 `theme_b/icons` **字节完全相同** |
| icons 格式 | 内层 zip（STORED+DEFLATED 混合），8033 条目：**几乎全是扁平 `<包名>.png`（218×218）**，外加 `icon_mask.png`、`icon_background_01.png`、`icon_folder.png`、`icon_folder_light.png`、`icon_pattern.png`、`dynamic_icons/{com.android.calendar,com.hihonor.android.totemweather,com.hihonor.deskclock}/`；**没有**自适应 `<pkg>/foreground.png` 目录、没有 `icon_masks/`、`icon_borders/`、`icons_backgrounds/`、`icon_border.png`、`transparent_config.xml` |
| 图标跟随主题(Cube SVG) | 关闭：`global/themed_icon_enabled=0` → `CubeResourcesAdapter.isSupportCubeSvg()` 为 false，走 zip 链路 |
| 当前深浅色 | `Night mode: auto`，`mCurUiMode=0x21`（侦察时为深色），`secure/ui_night_mode=0`，自定义 22:00–07:00（79200000/25200000 ms） |

---

## 2. framework 侧：主题图标加载链路（已反编译确认）

### 2.1 类的位置与改名

| 类 | 所在 jar | 备注 |
|---|---|---|
| `android.content.res.ResourcesImpl` | framework.jar classes.dex | 字段 `private AbsResourcesImpl mHwResourcesImpl`，构造时 `HwThemeManager.getHwResourcesImpl()` |
| `android.content.res.AbsResourcesImpl` | framework.jar classes.dex | 抽象基类 |
| `android.content.res.HwResourcesImpl extends AbsResourcesImpl` | **framework-magic.jar** classes.dex | 名字沿用 EMUI，未改 `Hn*` |
| `hihonor.android.hwutil.ZipFileCache` | framework-magic.jar classes2.dex | 由 `huawei.android.hwutil` 改为 `hihonor.android.hwutil` |
| `hihonor.android.hwutil.IconCache` | framework-magic.jar | 仅 `HashMap<String, CacheEntry> ICON_CACHE`（"id#pkg" → 文件名/type），与深浅无关 |
| `hihonor.android.hncube.ZipResourcesAdapter` / `CubeResourcesAdapter` | framework-magic.jar | 多级遮罩/底板/描边、Cube SVG 开关 |
| `android.hwtheme.HwThemeManager` | framework.jar classes2.dex | 路径常量 |
| `android.app.ApplicationPackageManager`、`android.content.pm.LauncherActivityInfo` | framework.jar classes.dex | 图标获取入口 |

未看到 `hwframework.jar`（侦察包里也没有）。

### 2.2 路径常量（`android.hwtheme.HwThemeManager`）

- `HWT_PATH_THEME` → `/data/themes`
- `HWT_PATH_SKIN` → `/data/themes/skin`
- `HWT_PATH_SKIN_EXTERNAL` → `/data/themes/externalSkin`
- `HWT_PATH_TEMP_SKIN` → `/data/themes/skin.tmp`

`/data/skin` 在设备上并不存在（`ls-data-skin.txt` 只回显了路径）。

### 2.3 `HwResourcesImpl.getThemeDir()`（public）

行为概述：桌面进程（`isFromLauncher()` 为真）返回 `/data/themes/<userId>`（如 `/data/themes/0`），其它进程返回 `/data/themes/skin`。
`private boolean isFromLauncher()`：`ActivityThreadEx.currentProcessName()` 等于 `"com.hihonor.android.launcher"`。
→ **桌面进程里 icons 的路径是 `/data/themes/0/icons`**（`/data/themes/0` 软链到 A/B 槽位）。

### 2.4 `ZipFileCache`

- `private static final ConcurrentHashMap<String, ZipFileCache> ZIP_FILE_CACHE_MAPS`，key = `path + "/" + zip`。
- `public static ZipFileCache getAndCheckCachedZipFile(String path, String zip, String zip_backup)`：先调 2 参版；返回 null 时打日志并用 `zip_backup` 再调 2 参版。
- `public static ZipFileCache getAndCheckCachedZipFile(String path, String zip)`：`synchronized (ZipFileCache.class)`；**文件不存在也会缓存一个"不存在"条目**（`mIsFileNotExist`），之后对同一 key 一直返回 null。打开方式 `new File(mPath, mZip)` + `new ZipFile(file, 1)`。
- `public static synchronized void clear()`：关闭所有并清空 map。
- 其它：`getBitmapEntry(...)` 多个重载、`getDrawableEntry`、`getBitmapList`、`isDir`、`isUseAdaptiveIcon`、`getInputStreamEntry`、`getFilesInFolder`、`checkIconEntry`、`initResDirInfo`。未看到 ZipFileCache 内部有 Bitmap 结果缓存。
- 常量 `private static final String ICONS = "icons"`。

`HwResourcesImpl` 中所有图标 zip 访问都是 **3 参版** `getAndCheckCachedZipFile(getThemeDir(), ICONS_ZIP_FILE, ICONS_ZIP_FILE_BACKUP)`（`ICONS_ZIP_FILE="icons"`，`ICONS_ZIP_FILE_BACKUP="icons_backup"`，共 13 处），调用方包括：`getThemeIcon`、`isIconFileEmpty`、`getAllBgImage`、`getThemeIconByNameFromZips`、`addIconBackgroundByMaskInner`、`addIconBackgroundInner`、`handleRoundCornerAndBg`、`handleAddIconBorder`、`handleAddIconBackground(Resources, Drawable)`、`getDrawableForDynamic`、`getJoinBitmap`、`preloadHwThemeZipsAndSomeIcons`。
各包名内层 zip（如 `com.hihonor.android.launcher`）用 **2 参版** `getAndCheckCachedZipFile(getThemeDir(), packageName)` —— Hook 2 参版时必须只认 `zip ∈ {"icons","icons_backup"}`。

### 2.5 应用图标链路

```
LauncherActivityInfo.getIcon(int density)                       [framework.jar]
  → mPm.getResourcesForApplication(appInfo).getDrawableForDensity(iconRes, density)
  → ResourcesImpl.loadDrawable(Resources, TypedValue, int id, int density, Theme, Resources.ExtProperties)
      ├─ caches = mDrawableCache / mOperationalDrawableCache / mOriginalDrawableCache / mCubeDrawableCache
      ├─ 命中 caches.getInstance(key,...) 直接返回
      └─ mHwResourcesImpl.loadHnDrawable(AbsResourcesImpl.RequestConfig)      [framework-magic.jar]
           ├─ iconKey = id + "#" + pkg；IconCache.contains(iconKey) 才走主题图标
           ├─ isIconFileEmpty / CubeResourcesAdapter.isSupportCubeSvg() / isSkipMarketThemeIcon
           ├─ getDrawableconstantState(key)：只读 sPreloadedDrawablesEx（且要求 !isPreloading && !isThemeChanged && density==DENSITY_DEVICE_STABLE）
           ├─ readDefaultConfig()
           ├─ getThemeIcon(Resources, String imgFile, String packageName, int id, boolean isSupportSvg, String resourcePackageName)  (private)
           └─ cacheDrawable(key, theme, cs) → 非 preload 时写 mDrawableCacheEx（loadHnDrawable 的图标分支不读它）
  ← ResourcesImpl：hwDrawable 非 null 且**不是** AdaptiveIconDrawable → 直接 return（**不进 ResourcesImpl.mDrawableCache**）
     是 AdaptiveIconDrawable → 继续走 handleAddIconBackground(...) 后 cacheDrawable 进 mDrawableCache
```

`getThemeIcon`（6 参、private）查找顺序：Cube SVG（本机关闭）→ 运营图标 → `<activityName>/` 自适应 → `<pkg>/` 自适应（`composeAdaptiveIcon`）→ `imgFile` → `getNewPackage` 映射 → `getIconFileName(pkg)`；扁平图走 `new BitmapDrawable(res, getDarkerBitmap(src))`，`ZipResourcesAdapter.isSupportMultiMask()` 时再 `handleRoundCornerAndBg`，`HnResourcesCacheUtils.isNeedAddBorderToIconInThemePackage()` 时再 `handleAddIconBorder`。
其它重载：`public Drawable getThemeIcon(Resources, String, String, int, boolean)`、`private Drawable getThemeIcon(Resources, String, String, int)`、`private Bitmap getThemeIcon(Resources, HnResExtProperties)`。

**对本机主题的含义**：当前 icons 全是扁平 png → 主题图标每次都现读 zip、不进 `ResourcesImpl.mDrawableCache`，所以只要 `ZipFileCache` 返回另一个 zip，下一次加载就是另一套图。

### 2.6 名字取图 / 文件夹 / 动态图标

- `public Bitmap getThemeIconByName(String name)` → `HnThemeIconAdapter.getHnThemeIcon(...)` → Cube 关闭时 `getThemeIconByNameFromZips(resources, name)`（public）→ 3 参 ZipFileCache。桌面经 `com.hihonor.android.content.res.ResourcesEx`（`getThemeDrawableByName`、`getThemeBitmapDrawable` 等）调用。
- `getDrawableForDynamic(...)` 读 `dynamic_icons/`，同样走 3 参 ZipFileCache。
→ F1 生效后这几条自动跟随。

### 2.7 无主题图标 App 的底板/遮罩（静态缓存）

`HwResourcesImpl` 静态字段（全部看到）：`private static volatile Bitmap sBmpMask, sBmpBorder, sBmpBg`；`private static List<Bitmap> sBgList`；`isMaskLoaded/isBorderLoaded/isBgLoaded/isBackgroundLoaded`；`needReloadMask/needReloadBorder/needReloadBg`（volatile）；`sMaskSizeWithoutEdge`；`sPreloadedDrawablesEx`/`sPreloadedColorDrawablesEx`（LongSparseArray）；`sDrawableKeyCache`（ArrayList<Long>）。
- `initMask/initBorder/initBackground(ZipFileCache)`：`(sBmpX == null && !isXLoaded) || needReloadX` 时重读。
- `getAllBgImage()`：`sBgList` 非空或 `isBackgroundLoaded` 就不重读；读的是 `icon_background*`（`ICON_BACKGROUND_PREFIX = "icon_background"`）。
- `preloadHwThemeZipsAndSomeIcons(int userId)`：**zygote（uid 0、64 位、user 0）里**直接从 `/data/themes/<userId>` 读 icons 预载 mask/border/sBgList，然后 `ZipFileCache.clear()` → 桌面进程 fork 出来时这些静态 Bitmap 已经是"当前系统主题"的。
- `hihonor.android.hncube.ZipResourcesAdapter`：静态 `sIsNeedCheckMultiLevel`、`sIsSupportMultiMask/Bg/Border`、`sFgMaskBmp`；`checkMultiLevel(...)` 只在 `sIsNeedCheckMultiLevel` 为 true 时检查 `icon_masks/` 等目录。

### 2.8 缓存清理入口（已看到）

| 方法 | 作用 | 注意 |
|---|---|---|
| `public void HwResourcesImpl.clearHwThemeZipsAndSomeIcons()` | `synchronized(HwResourcesImpl.class){ clearHwThemeZipsAndIconsCache(); }` + `clearPreloadedHwThemeZipsCache()` | 内部 **recycle** sBgList/sBmpBorder/sBmpMask/sBmpBg 并 `ZipFileCache.clear()`、`ZipResourcesAdapter.clearHwThemeZipsAndIconsCache()`（也 recycle `sFgMaskBmp`）、`HnResourcesCacheUtils.clearTransparentConfigCache()`。recycle 正在被绘制的 Bitmap 有崩溃风险，**不建议直接调** |
| `protected void handleClearCache(int configChanges, boolean isThemeChange)` | 换主题时清颜色/尺寸 map、`clearThemeCache()`、`mColorDrawableCacheEx`/`mDrawableCacheEx` 的 `onConfigurationChange`、`sDrawableKeyCache.clear()`、重新 preload | 只在 `updateConfiguration` 发现 ExtraConfig item 1（`mConfigHwt`）变化时调用 |
| `public void updateConfiguration(Configuration config, int configChanges)` | 由 `ResourcesImpl.updateConfiguration` 调用；uiMode 变化本身**不会**触发 handleClearCache | 适合作为"深浅色翻转"的观测点 |
| `public static void ZipResourcesAdapter.clearHwThemeZipsAndIconsCache()` | 重置多级标志并 recycle sFgMaskBmp | 同上，有 recycle |

`ApplicationPackageManager`：`sIconCache`（`ArrayMap<ResourceName, WeakReference<ConstantState>>`），`static void configurationChanged()` 会清空；`LauncherActivityInfo.getIcon` 不经过它（直接 `getDrawableForDensity`）。

### 2.9 夜间压暗

`private Bitmap getDarkerBitmap(Bitmap)`（HwResourcesImpl）：当 `mResourcesImpl` 的配置 uiMode 为夜间（`& 0x30 == 0x20`）时，对位图以 `0xFFF5F5F5` 做 MULTIPLY 叠色压暗，否则原样返回。
调用点 8 处：`getThemeIcon`、`handleRoundCornerAndBg`、`handleAddIconBackground(Resources, Drawable)`、`resizeAdaptiveIcon`（对 fg/bg/mono）、`getDrawableForDynamic` 等。另有 `private boolean isDarkMode()` 同一判断。
**注意** `ResourcesImpl.updateConfiguration` 里有一段：当 `currentNightMode==32`、`getDataThemePackages()` 含当前包名、且 `extraConfig.getConfigItem(7) != 1` 并且主题里该包 zip 没有 `night/` 目录时，会把该 ResourcesImpl 的 `mConfiguration.uiMode` 强制改回白天（`| 16`）。当前主题目录里确实有 `com.hihonor.android.launcher` 这个内层 zip，但我**没能核实它里面有没有 `night/`**（Mac 第二次取文件时离线）。因此桌面进程里 ResourcesImpl 的 uiMode 可能与系统真实 uiMode 不一致 → 我们判断深浅色**不能**读 `mResourcesImpl.getConfiguration()`，要读传入 `updateConfiguration` 的 `config` 参数或 `ActivityThread`/Application 的全局配置。

---

## 3. 桌面侧（com.hihonor.android.launcher 18.0.0.259）

- `UniHomeLauncher` 声明 `configChanges` 含 `uiMode` → 深浅色切换**不重建 Activity**，走 `onConfigurationChanged`。
- `LauncherApplication.onConfigurationChanged`：桌面处于 paused 且只有 uiMode 变化（diff & 512）时，延迟 500 ms（`mOnlyUiModeChangedRunnable` → `launcher.configurationChanged`）。
- `Launcher.onConfigurationChanged` → `configurationChanged(config,false)` → `ConfigChangeManager` → `config.handler.UiModeConfigHandler`（`AbsConfigHandler` 子类）：
  - `CommonLogicUtil.beforeDoingConfigChange(...)`：`isUiModeChanged` 时调 `launcher.uiModeChanged()`；
  - `Launcher.uiModeChanged()`：`updateIconCache()` → `LauncherApplication.reCreateIconCache()` → `IconCache.reCreate(Context)`（`initMemCache()` 清内存缓存并 `updateSystemState()`）；worker 上 `LoadIconUtil.launchUpdateShortcutTask(null)` + `WallpaperIconRefreshManager.updateIconCacheAndDb(Boolean.TRUE)`（参数 TRUE 时只处理分身应用）；`DynamicIconManager.updateDynamicsIconColor()`；
  - `UiModeConfigHandler.reapplyUi(Configuration)` → `LoadIconUtil.launchUpdateIconTask(null, null)`：取 `getModel().getAllIdleAppsList().data`，对非动态图标逐个 `ShortcutInfoUtils.reloadIcon(shortcutInfo, true)`，再 `PackageUpdatedTask.create(112, ...)`。
- 持久缓存 `app_icons.db`（`com.hihonor.android.launcher.IconCache extends com.android.launcher3.icons.cache.BaseIconCache`）：freshness 用 `IconProvider.mSystemState`，由 `CacheSystemState.toString()` = `"%s,dpi:%d,uimode:%d,hwt:%s,SDK:%d,iconBorder:%b"` 组成 → **uiMode 变了 DB 条目自动视为过期**，会回到 `LauncherActivityCachingLogic.loadIcon` → `LauncherActivityInfoCompat.getIcon(dpi)` 重新从 framework 取图。
- 结论：**桌面已经在每次深浅色切换时自己清缓存并重新向 framework 取所有图标**。L1/L2 不需要我们实现，只要保证 framework 在那之前返回新一套图。
- 桌面侧未看到额外的夜间压暗（在 `com/android/launcher3/icons`、`IconCache.java` 中没搜到 `PorterDuff.Mode.MULTIPLY`/darker）。

---

## 4. 主题数据（data-themes.tar）

```
/data/themes/skin -> /data/themes/0
/data/themes/0    -> /data/themes/user/0/theme/theme_b        （A/B 槽位，当前 b）
/data/themes/user/0/theme/{theme_a,theme_b}/
    icons (28,780,820 B, 内层 zip)   icons_backup   com.hihonor.android.launcher (内层 zip, 127 KB)
    description.xml (<HnTheme>, 琉光 (动态) 8.0.18)  theme_meta.json  wallpaper_meta.json  updated
    unlock/ aod/ lockscreen/ preview/ wallpaper/ fonts/ fingerprint_* livepaper*.xml
/data/themes/128/  （另一个用户/空间，"光曜 (动态)" 11.0.9，icons 1.16 MB，含自适应 <pkg>/foreground.png+background.png）
/data/themes/user/common/_append/icon/3.0.0.{3,4}/iconManifest.json
/data/themes/temp/  /data/themes/skin.tmp/  /data/themes/animscene/
权限 system:media_rw 0775；du /data/themes = 344M
```

当前 `icons`（theme_a 与 theme_b 字节相同）结构：8033 条目，根目录约 7970 个 `<包名>.png`（218×218），`icon_mask.png`（218×218 RGBA）、`icon_background_01.png`、`icon_folder.png`、`icon_folder_light.png`、`icon_pattern.png`、`dynamic_icons/`（日历 calendar1..31 等、天气、时钟）。无自适应目录、无多级遮罩目录。条目时间戳到 2026-08，说明这是被改过/扩充过的 icons。
主题商店侧可见 `/sdcard/Download/iOS17苹方Ultra加粗（6字重）.hwt`（45.8 MB）及 `/sdcard/Honor/Themes/` 大量文件，作为浅/深两套来源的候选，**本阶段未解包**。

---

## 5. logs / settings 线索

- `logcat-theme.txt`：只有 LSPosed 已运行（`zygisk_lsposed`、另一模块 `io.github.yuxiximyxx.magicfree4` 在 SystemUI 等进程工作）和 HnThemeManager 扫描记录；没有主题切换/图标加载日志。`logcat-full.txt`（11880 行）中未出现 `HwResourcesImpl`、`ZipFileCache`、`IconProvider`、`Launcher.IconCache` 等 TAG——侦察窗口内没发生深浅色切换。桌面 pid 24352。
- 设置：`global/themed_icon_enabled=0`；`secure/ui_night_mode=0`（auto）；`dark_theme_custom_start/end_time=79200000/25200000`；`hn_dark_mode`、`deep_theme`、`launcher_transparency_mode` 均为 null；`themeprovider-themes.txt` = "No result found."。
- `am get-config`：`...-night-560dpi-...-2800x1280-v37`；uimode dumpsys `mCurUiMode=0x21`。

---

## 6. 对 RESEARCH.md 候选的逐条判定

| # | 判定 | 说明 |
|---|---|---|
| F1 | ✅ 成立（包名改为 `hihonor.android.hwutil`） | 2 参版仍存在；实际调用方用 3 参版（多一个 `icons_backup`），3 参内部调 2 参。key 带 path，浅/深可共存 |
| F2 | ⚠️ 部分推翻 | 不是恒为 `/data/skin`：桌面进程返回 `/data/themes/<userId>`，其它进程 `/data/themes/skin`。仍不建议 Hook |
| F3 | ✅ 存在，签名变化 | 现为 6 参 private `getThemeIcon(Resources,String,String,int,boolean,String)`，另有 5 参 public、4 参 private 等重载。作备选 |
| F4 | ✅ 存在 | `public Bitmap getThemeIconByName(String)`；内部经 `getThemeIconByNameFromZips`，F1 生效后跟随 |
| F5 | ⚠️ 修正 | `HwResourcesImpl.loadDrawable(Resources,TypedValue,int,Theme,boolean)` 存在但只是包一层 `loadHnDrawable(RequestConfig)`；图标分支只读 `sPreloadedDrawablesEx`（zygote 预载才有），`mDrawableCacheEx` 只写不读；扁平主题图不进 `ResourcesImpl.mDrawableCache`。**扁平主题下不需要清 Drawable 缓存**；自适应主题图会进 `ResourcesImpl.mDrawableCache`（风险见 §8） |
| F6 | ✅ 成立 | `private Bitmap getDarkerBitmap(Bitmap)`，常量 `COLOR_FOR_DARKER_ICON = -657931` |
| F7 | ✅ 成立并扩充 | 静态 `sBmpMask/sBmpBorder/sBmpBg/sBgList` + 加载标志；zygote 预载。另有 `ZipResourcesAdapter` 的多级静态状态 |
| F8 | ✅ 成立（`hihonor.android.hwutil.IconCache`） | 与深浅无关，不需要改 |
| F9 | ❓ 未核实 | 本阶段没反编译 `HwThemeManagerImpl`；也不需要它（不应全局调用） |
| L1 | ⚠️ 不需要自己做 | 桌面 uiMode 变化时已 `IconCache.reCreate` + `launchUpdateIconTask` 全量 reloadIcon；DB freshness 含 uimode。类为 `com.hihonor.android.launcher.IconCache`（不是 `com.android.launcher3.icons.IconCache`）；未看到 `forceReload` 被这条链路使用 |
| L2 | ✅ 成立 | `configChanges` 含 `uiMode`，走 `onConfigurationChanged`；暂停时延迟 500 ms |
| L3 | ✅ 存在 | `com.hihonor.android.launcher.icons.cache.LauncherActivityCachingLogic.loadIcon(Context, BaseIconCache, LauncherActivityInfoCompat)`；不需要 |
| L4 | ✅ 存在 | `com.android.launcher3.icons.BaseIconFactory`；仅作最后备选 |
| L5 | 未查 | 非必需 |
| L6 | ✅ 可用兜底 | `Process.killProcess(myPid())` |

---

## 7. 最终方案（阶段 2 依据）

### 7.1 首选 Hook 点

1. **`hihonor.android.hwutil.ZipFileCache.getAndCheckCachedZipFile(String path, String zip)`**（public static，before-hook，改参数 0）
   - 仅在桌面进程（`com.hihonor.android.launcher`）安装。
   - 条件：`zip` 等于 `"icons"` 或 `"icons_backup"`，且 `path` 规范化后是主题目录（`/data/themes/0`、`/data/themes/skin`、或 `/data/themes/user/0/theme/theme_[ab]`）；且模块已启用、目标槽位文件存在。
   - 动作：把 `path` 改成 `<launcher filesDir>/nighticon/light` 或 `.../dark`（目录里放名为 `icons` 的文件；`icons_backup` 可不提供，缺失时会被 ZipFileCache 记成"不存在"返回 null，没问题；或直接两个都映射到同一份）。
   - 调用时机：每次 framework 取图标都会经过（getThemeIcon / isIconFileEmpty / 底板 / 名字取图 / 动态图标）。
   - 可选：同时 Hook 3 参版 `getAndCheckCachedZipFile(String,String,String)`，防止 2 参版被内联进 3 参版（两者都在同一类，3 参版很短）。
2. **`android.content.res.HwResourcesImpl.updateConfiguration(Configuration config, int configChanges)`**（public，after-hook）
   - 读入参 `config.uiMode & 0x30`（不要读 `mResourcesImpl` 的配置，见 §2.9），与模块记录的上次状态比较；翻转时（进程内只做一次，加锁去重）：
     - 更新"当前槽位"；
     - 软重置静态缓存：`needReloadMask = needReloadBorder = needReloadBg = true`；`isBackgroundLoaded = false`；`sBgList = null`（**赋 null，不 clear、不 recycle**）；`ZipResourcesAdapter.sIsNeedCheckMultiLevel = true`；
     - 不调用 `clearHwThemeZipsAndSomeIcons()`（它会 recycle）。
   - 它在 `ResourcesManager` 更新资源时调用，早于 `LauncherApplication/Launcher.onConfigurationChanged`，因此桌面随后 `uiModeChanged()` + `reapplyUi` 重载图标时 framework 已切好。
3. **`HwResourcesImpl.getDarkerBitmap(Bitmap)`**（private，before-hook）
   - 深色槽位生效时直接 `return bmp`，避免对深色套图二次压暗；浅色槽位或模块未生效时放行。
4. 启动初始化：模块加载时（Application.onCreate after-hook 或第一次 ZipFileCache 调用时）从全局配置取当前深浅色；同时因为 zygote 预载的 mask/border 来自系统主题，**首次启用时也做一次软重置**。

### 7.2 备选

- B1：Hook 6 参 `getThemeIcon(...)` / `getThemeIconByNameFromZips(...)` 直接按槽位返回（需自己处理 getDarkerBitmap、底板）。
- B2：桌面侧 `LauncherActivityCachingLogic.loadIcon` / `BaseIconFactory` 替换 Drawable。
- B3：翻转时补调 `com.hihonor.android.launcher.IconCache.clearCacheAndDb(boolean)` + `reCreate(Context)`，或 `LoadIconUtil.launchUpdateIconTask(null, null)`。
- B4（兜底）：`Process.killProcess(myPid())` 重启桌面。

### 7.3 为什么这样选

1. 所有主题图标（应用图标、底板/遮罩、名字取图、动态图标）都收口在 `ZipFileCache` 的 icons 调用，替换 path 只改"图标"，壁纸/锁屏/字体/桌面主题色（`com.hihonor.android.launcher` zip）都不动。
2. `ZIP_FILE_CACHE_MAPS` key 含 path，浅/深两个 zip 可同时缓存，切换无需关闭/重开文件。
3. 当前主题是扁平 png：主题图标不进 `ResourcesImpl.mDrawableCache`，`loadHnDrawable` 也不读 `mDrawableCacheEx`，所以不用动这些实例缓存。
4. 桌面自己在 uiMode 变化时重建 IconCache、重载所有图标，且 `app_icons.db` 的 freshness 含 uimode —— 我们不需要实现 forceReload。
5. 作用域只有桌面进程；`getThemeDir()` 在其它进程返回 `/data/themes/skin`，本来就不会被我们改到；锁屏在 SystemUI，不受影响。

---

## 8. 剩余风险

| 风险 | 说明 | 缓解 / 阶段 2 验证 |
|---|---|---|
| uiMode 被 ResourcesImpl 改回白天 | §2.9：主题含 `com.hihonor.android.launcher` zip，若其中无 `night/` 且 extraConfig item 7 != 1，桌面进程 ResourcesImpl 的 uiMode 会被强制为白天 → 系统 getDarkerBitmap 可能本来就不压暗；也影响桌面 DB freshness | 判断深浅只用入参/全局配置；真机打日志对比 `config.uiMode` 与 `mResourcesImpl.getConfiguration().uiMode` |
| 自适应主题 | 若浅/深任一套用 `<pkg>/foreground.png` 自适应格式，`AdaptiveIconDrawable` 会进 `ResourcesImpl.mDrawableCache`，其 `onConfigurationChange` 只按资源自身 changingConfigurations 失效，通常不含 uiMode → 切换后可能残留旧图 | 阶段 2 先确认两套来源格式；若有自适应，翻转时反射清 launcher 进程内各 ResourcesImpl 的 `private final DrawableCache mDrawableCache`（字段已确认；清理 API 未细看） |
| 静态 Bitmap 被并发使用 | 软重置只改标志、赋 null，不 recycle；但旧 Bitmap 由 GC 回收，短时间内存翻倍 | 可接受 |
| "不存在"负缓存 | 若深/浅 zip 在首次访问时还没落地，ZipFileCache 会把该 key 永久记成不存在 | 文件就位后再启用槽位；或换一个子目录名（key 不同）；必要时反射移除 `ZIP_FILE_CACHE_MAPS` 对应项 |
| ART 内联 | `getDarkerBitmap`、`isFromLauncher`、3 参→2 参调用都很短，可能被内联 | libxposed `deoptimize` 调用方（`getThemeIcon` 等）；同时 Hook 2 参和 3 参 |
| 文件可读性 | 桌面进程（普通 app uid、SELinux untrusted_app/platform_app）读自己 filesDir 没问题；读模块目录或 /data/local/tmp 会被拦 | 远程文件 → 复制到桌面 `filesDir/nighticon/<slot>/icons` |
| zygote 预载 | 桌面 fork 自 zygote，mask/border 是系统主题的；首次启用要软重置一次 | 见 7.1-4 |
| 桌面暂停时 500 ms 延迟 | 只有 uiMode 变化且桌面不在前台时，桌面延迟处理；framework 侧已先切好，不影响正确性 | 无 |
| 日历/天气/时钟动态图标 | 由 `dynamic_icons/` 提供（跟随 F1），但文字颜色来自 `com.hihonor.android.launcher` zip | 可接受 |
| 系统换主题 | 用户在主题商店换主题会触发 `handleClearCache` 并 `ZipFileCache.clear()`（经 preload 路径） | 我们的 key 不依赖 /data/themes，重开即可 |
| 未核实项 | `HwThemeManagerImpl`（F9）、`ResourcesImpl.mDrawableCache` 清理细节、主题 launcher zip 是否有 `night/`、LSPosed 实际 API 版本 | 阶段 2 真机日志确认 |

---

## 9. 阶段 2 代码要做的事（清单）

1. 作用域只勾 `com.hihonor.android.launcher`；`onPackageLoaded`/`onPackageReady` 里判断进程名。
2. 用 `ClassLoader`（boot）找 `hihonor.android.hwutil.ZipFileCache`、`android.content.res.HwResourcesImpl`、`hihonor.android.hncube.ZipResourcesAdapter`；找不到就只打日志、不装 Hook。
3. Hook：`ZipFileCache.getAndCheckCachedZipFile(String,String)`（+ 3 参版）、`HwResourcesImpl.updateConfiguration(Configuration,int)`、`HwResourcesImpl.getDarkerBitmap(Bitmap)`；全部 `ExceptionMode.PROTECTIVE`。
4. 文件下发：模块 App 预先从两套 .hwt/.hnt 里取出 `icons`，经远程文件给桌面，桌面落到 `filesDir/nighticon/{light,dark}/icons`；校验是 zip 再启用。
5. 深浅判断：入参 `config.uiMode & UI_MODE_NIGHT_MASK`；初值取 `ActivityThread`/Application 全局配置。
6. 翻转时软重置静态字段（见 7.1-2），**不 recycle、不调 clearHwThemeZipsAndSomeIcons**。
7. 依赖桌面原生刷新；加一个开关在翻转后补调 `LoadIconUtil.launchUpdateIconTask(null, null)`（B3），以及调试用的 L6 重启。
8. 日志：每次替换 path、每次翻转、`mResourcesImpl` uiMode 与入参 uiMode 是否一致、是否走到 `getDarkerBitmap`。
