plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.augustana.tfold"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.augustana.tfold"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    /**
     * 签名**跟着仓库走**（`keystore/tfold.jks`）。
     *
     * 为什么把密钥提交进来：这个 App 是自用 sideload —— 换一台机器 clone 下来打包，
     * 装上去必须是**同一个 App 的更新**（同 package + 同签名），而不是"签名冲突，先卸载"。
     * debug 构建也用这把钥匙，否则每台机器的 `~/.android/debug.keystore` 都不一样，
     * 装第二次就报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
     *
     * 代价写在明处：密钥进了仓库，谁拿到仓库谁就能打出能覆盖安装的包。
     * 真要去应用商店上架 / 公开分发，务必换成不进仓库的密钥（口令走环境变量）。
     */
    signingConfigs {
        create("tfold") {
            storeFile = rootProject.file("keystore/tfold.jks")
            storePassword = "tfold2026"
            keyAlias = "tfold"
            keyPassword = "tfold2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("tfold")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("tfold")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    // 折叠屏官方姿势：FoldingFeature（开合态）+ WindowArea（前后屏呈现区域）
    implementation("androidx.window:window:1.3.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
