#!/usr/bin/env bash
# =============================================================================
# NightIcon 阶段 0 侦察脚本（在 Mac 上运行，只读）
#
# 用途：确认桌面/主题商店包名与版本，拉取桌面与主题商店 APK、framework 相关 jar，
#       用 su（KernelSU）把 /data/themes 当前主题结构"读出来"打包到本机，
#       列出本地主题文件、读取主题相关设置键、导出当前 uiMode。
#
# 安全承诺：本脚本不在手机上写入、修改、删除任何东西。
#   - 只使用 getprop / pm list / pm path / dumpsys / settings get / settings list /
#     cmd uimode night（无参数=查询）/ content query / ls / cat / tar -c（输出到 stdout）
#   - 所有产物只写到本机 ~/nighticon-recon/<时间戳>/
#   - 不使用 adb push / install / settings put / cp / mv / rm / chmod 等任何写操作
#
# 用法：
#   bash recon.sh                 # 默认：全部步骤
#   bash recon.sh --no-root       # 跳过需要 su 的步骤
#   bash recon.sh --no-apk        # 跳过 APK / jar 拉取（省时间与磁盘）
#   ADB=/path/to/adb bash recon.sh
#   ANDROID_SERIAL=<序列号> bash recon.sh   # 多设备时指定
#
# 前提：手机已开 USB 调试并授权本机；KernelSU 管理器里给 "Shell (com.android.shell)"
#       授予 root（否则 su 步骤会被跳过）。临时 root 重启即失效，请在 root 有效期内运行。
# =============================================================================
set -u

ADB="${ADB:-$HOME/android-sdk/platform-tools/adb}"
DO_ROOT=1
DO_APK=1
for arg in "$@"; do
  case "$arg" in
    --no-root) DO_ROOT=0 ;;
    --no-apk)  DO_APK=0 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

TS="$(date +%Y%m%d-%H%M%S)"
OUT="$HOME/nighticon-recon/$TS"
mkdir -p "$OUT"/{info,apk,framework,themes,settings,logs}
LOG="$OUT/recon.log"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG"; }
warn() { log "警告：$*"; }

# adb shell 只读命令包装；去掉可能的 \r
sh_ro() { "$ADB" shell "$@" 2>>"$LOG" | tr -d '\r'; }
# su 只读命令包装（KernelSU 的 su 支持 -c）
su_ro() { "$ADB" shell "su -c '$*'" 2>>"$LOG" | tr -d '\r'; }

# ---------------------------------------------------------------- 0. 环境检查
if [ ! -x "$ADB" ]; then
  echo "找不到 adb：$ADB（可用 ADB=... 指定）" >&2; exit 1
fi
STATE="$("$ADB" get-state 2>/dev/null | tr -d '\r')"
if [ "$STATE" != "device" ]; then
  echo "没有可用设备（adb get-state=$STATE）。请连接手机并授权 USB 调试。" >&2; exit 1
fi
log "输出目录：$OUT"
log "adb：$ADB  设备：$("$ADB" get-serialno | tr -d '\r')"

# ---------------------------------------------------------------- 1. 设备信息
log "1/9 设备信息"
sh_ro getprop > "$OUT/info/getprop.txt"
{
  for p in ro.product.model ro.product.device ro.build.display.id ro.build.version.release \
           ro.build.version.sdk ro.build.version.magic ro.magic.api.version ro.build.magic_api_level \
           ro.config.diff_themes ro.config.custom_icon ro.config.hw_icon_supprot_cut \
           ro.config.app_big_icon_size ro.config.app_inner_icon_size ro.config.ota_update_theme; do
    printf '%s=%s\n' "$p" "$(sh_ro getprop "$p")"
  done
  echo "--- 主题/图标相关属性（grep）"
  grep -iE 'theme|icon|msc\.launcher|deep' "$OUT/info/getprop.txt"
} > "$OUT/info/props-key.txt"
sh_ro uname -a > "$OUT/info/uname.txt"

# ---------------------------------------------------------------- 2. 包名与版本
log "2/9 包名与版本"
sh_ro pm list packages -f > "$OUT/info/packages-all.txt"
grep -iE 'launcher|theme|hihonor\.android\.(launcher|thememanager)|hnthemesetting' \
  "$OUT/info/packages-all.txt" > "$OUT/info/packages-candidates.txt"
HOME_ACT="$(sh_ro cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)"
LAUNCHER_PKG="${HOME_ACT%%/*}"
[ -z "$LAUNCHER_PKG" ] && LAUNCHER_PKG="com.hihonor.android.launcher"
THEME_PKG="com.hihonor.android.thememanager"
grep -q "=$THEME_PKG\$" "$OUT/info/packages-all.txt" || \
  THEME_PKG="$(grep -iE 'thememanager' "$OUT/info/packages-all.txt" | head -n1 | sed 's/.*=//')"
printf 'home_activity=%s\nlauncher_pkg=%s\ntheme_pkg=%s\n' "$HOME_ACT" "$LAUNCHER_PKG" "$THEME_PKG" \
  | tee "$OUT/info/key-packages.txt" | tee -a "$LOG"
for pkg in "$LAUNCHER_PKG" "$THEME_PKG"; do
  [ -z "$pkg" ] && continue
  sh_ro dumpsys package "$pkg" > "$OUT/info/dumpsys-package-$pkg.txt"
  grep -E 'versionName|versionCode|codePath|lastUpdateTime|flags=' "$OUT/info/dumpsys-package-$pkg.txt" \
    | sed "s/^/[$pkg] /" | tee -a "$OUT/info/versions.txt" >/dev/null
done
log "版本：$(grep versionName "$OUT/info/versions.txt" | tr '\n' ' ')"

# ---------------------------------------------------------------- 3. 拉取 APK
if [ "$DO_APK" = 1 ]; then
  log "3/9 拉取桌面与主题商店 APK（含 split）"
  for pkg in "$LAUNCHER_PKG" "$THEME_PKG"; do
    [ -z "$pkg" ] && continue
    mkdir -p "$OUT/apk/$pkg"
    sh_ro pm path "$pkg" | sed 's/^package://' | while read -r p; do
      [ -z "$p" ] && continue
      "$ADB" pull "$p" "$OUT/apk/$pkg/" >>"$LOG" 2>&1 || warn "pull 失败：$p"
    done
  done
else
  log "3/9 跳过 APK（--no-apk）"
fi

# ---------------------------------------------------------------- 4. framework jar
log "4/9 framework 相关 jar"
for d in /system/framework /system_ext/framework /product/framework /vendor/framework; do
  sh_ro ls -la "$d" > "$OUT/framework/ls-$(echo "$d" | tr '/' '_').txt" 2>/dev/null
done
sh_ro 'echo $BOOTCLASSPATH' | tr ':' '\n' > "$OUT/framework/BOOTCLASSPATH.txt"
sh_ro 'echo $SYSTEMSERVERCLASSPATH' | tr ':' '\n' > "$OUT/framework/SYSTEMSERVERCLASSPATH.txt"
if [ "$DO_APK" = 1 ]; then
  # 只拉与主题/资源相关的 jar：framework.jar、hw*/hn*/honor*/magic* framework 等（存在才拉）
  {
    cat "$OUT/framework/BOOTCLASSPATH.txt"
    for d in /system/framework /system_ext/framework /product/framework; do
      sh_ro ls "$d" | sed "s#^#$d/#"
    done
  } | grep -E '\.jar$' | grep -iE '/framework\.jar$|hwframework|hnframework|honor|hihonor|magic|framework-res-ext|hwtheme|hntheme' \
    | sort -u > "$OUT/framework/pull-list.txt"
  while read -r j; do
    [ -z "$j" ] && continue
    "$ADB" pull "$j" "$OUT/framework/" >>"$LOG" 2>&1 || warn "pull 失败：$j"
  done < "$OUT/framework/pull-list.txt"
  log "已拉取 jar：$(ls "$OUT/framework" | grep -c '\.jar$') 个（清单见 framework/pull-list.txt）"
fi

# ---------------------------------------------------------------- 5. root：/data/themes 结构
if [ "$DO_ROOT" = 1 ]; then
  log "5/9 su 读取 /data/themes（只读打包，不在手机上落任何文件）"
  if [ "$(su_ro id | grep -c 'uid=0')" = "0" ]; then
    warn "su 不可用（请在 KernelSU 管理器给 Shell 授权 root），跳过 root 步骤"
    DO_ROOT=0
  fi
fi
if [ "$DO_ROOT" = 1 ]; then
  su_ro 'ls -laR /data/themes' > "$OUT/themes/ls-R-data-themes.txt"
  su_ro 'ls -la /data/skin /data/skin* ; readlink -f /data/skin' > "$OUT/themes/ls-data-skin.txt"
  su_ro 'du -sh /data/themes /data/themes/*' > "$OUT/themes/du.txt"
  # tar 输出到 stdout，经 exec-out 直接写到本机文件
  "$ADB" exec-out "su -c 'tar -cf - -C /data themes'" > "$OUT/themes/data-themes.tar" 2>>"$LOG" \
    || warn "tar /data/themes 失败"
  if [ -s "$OUT/themes/data-themes.tar" ]; then
    mkdir -p "$OUT/themes/extracted"
    tar -xf "$OUT/themes/data-themes.tar" -C "$OUT/themes/extracted" 2>>"$LOG"
    # 列出每个 icons 内层 zip 的条目（命名规则核对用）
    find "$OUT/themes/extracted" -type f -name icons | while read -r z; do
      rel="$(echo "${z#$OUT/themes/extracted/}" | tr '/' '_')"
      unzip -l "$z" > "$OUT/themes/icons-entries-$rel.txt" 2>&1
    done
    find "$OUT/themes/extracted" -maxdepth 3 > "$OUT/themes/tree.txt"
  fi
  # 主题商店私有目录里的主题（只列不拷）
  su_ro "ls -laR /data/data/$THEME_PKG/files 2>/dev/null | head -n 400" > "$OUT/themes/ls-thememanager-files.txt"
  su_ro "ls -laR /sdcard/Android/data/$THEME_PKG/files 2>/dev/null | head -n 400" > "$OUT/themes/ls-thememanager-extfiles.txt"
else
  log "5/9 跳过 root 步骤"
fi

# ---------------------------------------------------------------- 6. 本地主题文件
log "6/9 列出 Honor/Themes 本地主题文件"
sh_ro 'ls -laR /sdcard/Honor/Themes 2>/dev/null' > "$OUT/themes/ls-sdcard-Honor-Themes.txt"
sh_ro 'ls -la /sdcard/Download 2>/dev/null | grep -iE "\.(hwt|hnt)$"' > "$OUT/themes/ls-sdcard-Download-themes.txt"

# ---------------------------------------------------------------- 7. 设置键
log "7/9 读取主题/图标/深色相关设置键"
for ns in system secure global; do
  sh_ro settings list "$ns" > "$OUT/settings/all-$ns.txt"
  grep -iE 'theme|icon|night|dark|ui_mode|launcher' "$OUT/settings/all-$ns.txt" > "$OUT/settings/filtered-$ns.txt"
done
{
  for ns in system secure global; do
    for k in themed_icon_enabled ui_night_mode dark_theme_custom_start_time dark_theme_custom_end_time \
             hn_dark_mode deep_theme launcher_transparency_mode; do
      printf '%s/%s=%s\n' "$ns" "$k" "$(sh_ro settings get "$ns" "$k")"
    done
  done
} > "$OUT/settings/key-values.txt"
# 主题 ContentProvider（只读查询；HonorHook 文档中的观测面）
sh_ro content query --uri content://com.hihonor.systemserver.theme.ThemeProvider/themes \
  > "$OUT/settings/themeprovider-themes.txt" 2>&1

# ---------------------------------------------------------------- 8. uiMode
log "8/9 导出当前 uiMode"
sh_ro cmd uimode night > "$OUT/info/uimode-night.txt"     # 无参数 = 查询当前夜间模式
sh_ro dumpsys uimode > "$OUT/info/dumpsys-uimode.txt"
sh_ro am get-config > "$OUT/info/am-get-config.txt"
log "夜间模式：$(cat "$OUT/info/uimode-night.txt")"

# ---------------------------------------------------------------- 9. 日志与进程
log "9/9 进程与相关日志（logcat -d 只读快照）"
sh_ro pidof "$LAUNCHER_PKG" > "$OUT/logs/launcher-pid.txt"
sh_ro logcat -d -v time > "$OUT/logs/logcat-full.txt"
grep -iE 'ZipFileCache|HwResources|HnResources|IconCache|hwtheme|hntheme|ThemeIcon|NightIcon|LSPosed' \
  "$OUT/logs/logcat-full.txt" > "$OUT/logs/logcat-theme.txt"

# ---------------------------------------------------------------- 汇总
{
  echo "# NightIcon 侦察摘要 $TS"
  cat "$OUT/info/key-packages.txt"
  echo "--- 版本"; cat "$OUT/info/versions.txt" 2>/dev/null | grep -E 'versionName|versionCode'
  echo "--- 关键属性"; head -n 14 "$OUT/info/props-key.txt"
  echo "--- 设置键"; cat "$OUT/settings/key-values.txt"
  echo "--- uiMode"; cat "$OUT/info/uimode-night.txt"
  echo "--- 已拉取文件"; (cd "$OUT" && find apk framework themes -maxdepth 2 -type f \( -name '*.apk' -o -name '*.jar' -o -name '*.tar' \) -exec ls -la {} \;)
} > "$OUT/SUMMARY.txt"
log "完成。摘要：$OUT/SUMMARY.txt"
