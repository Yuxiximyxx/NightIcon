// NightIcon —— 深浅色自动切换桌面主题图标的 LSPosed 模块（工程骨架）
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // libxposed 离线兜底：本地 ~/.m2 有货时优先命中（照 HonorHook 写法）
        mavenLocal {
            content { includeGroup("io.github.libxposed") }
        }
    }
}

rootProject.name = "NightIcon"
include(":app")
