plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nighticon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nighticon"
        // MagicOS 11 为 Android 16 基线；minSdk 放宽到 31 便于在其它机型上调试 UI
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            // 骨架阶段不混淆；后续开启时务必保留 java_init.list 中的入口类
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty(
                "nighticon.recon",
                rootProject.projectDir.resolve("../recon/mac/themes").absolutePath,
            )
            // ./gradlew testDebugUnitTest -Dnighticon.screenshots=/tmp/shots
            System.getProperty("nighticon.screenshots")?.let { dir ->
                it.systemProperty("nighticon.screenshots", dir)
            }
        }
    }

    packaging {
        resources {
            // 确保 META-INF/xposed/* 被打进 APK（现代 LSPosed 依赖它识别模块）
            merges += "META-INF/xposed/*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // libxposed API：仅编译期，运行时由 LSPosed 框架提供
    compileOnly(libs.libxposed.api)
    // libxposed Service：模块 App 与框架通信（远程 prefs / 远程文件下发图标缓存）
    implementation(libs.libxposed.service)
    // 设置页：Miuix（Compose）。版本见 libs.versions.toml 的说明
    implementation(libs.miuix)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // 单元测试（JVM）：主题 zip 解析与路径判断
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
