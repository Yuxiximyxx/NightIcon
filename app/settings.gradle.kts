// NightIcon —— 深浅色自动切换桌面主题图标的 LSPosed 模块
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
        // libxposed API 101 / service 101 已发布到 Maven Central，全新克隆不必再准备 mavenLocal
    }
}

rootProject.name = "NightIcon"
include(":app")
