# NightIcon

荣耀 MagicOS 桌面图标随日夜间模式切换的 LSPosed 模块（libxposed API 101）。

系统切换深浅色时，荣耀桌面（`com.hihonor.android.launcher`）的图标在两套荣耀主题（.hwt/.hnt）的图标之间自动切换；不碰锁屏、壁纸、SystemUI，也不修改主题文件。

- 已验证设备：荣耀 PTP-AN10，MagicOS 11，KernelSU + LSPosed（0.1.0 的日夜切换）
- 当前版本：0.2.0（Miuix 设置页、自适应图标；Hook 行为与 0.1.0 相同）

## 目录

| 路径 | 内容 |
|---|---|
| `app/` | Android 工程（Gradle Kotlin DSL + Kotlin）；原理、用法、风险、调试见 [app/README.md](app/README.md) |
| `docs/ANALYSIS.md` | 侦察与反编译结论（类名/方法签名级别） |
| `docs/RESEARCH.md` | 前期调研：Hook 候选点、主题包结构、风险 |
| `scripts/recon.sh` | 只读侦察脚本（在电脑上经 adb 运行，产物不入库） |

## 构建

依赖都从 Google Maven 和 Maven Central 解析（含 libxposed 101），不需要本地 Maven 仓库。

```bash
cd app
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug testDebugUnitTest   # 需要 JDK 17+
```

推送和拉取请求会在 GitHub Actions 里构建 debug APK 并上传产物。

> 本仓库不包含任何系统文件、反编译源码或侦察数据。
