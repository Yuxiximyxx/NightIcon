// 顶层只声明插件，版本统一由 gradle/libs.versions.toml 提供
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
