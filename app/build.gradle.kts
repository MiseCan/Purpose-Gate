plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.purposegate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.purposegate"
        minSdk = 26
        targetSdk = 37
        // 版本变化会让桌面重新读取图标，换图标后不需要用户手动删掉再添加桌面快捷方式。
        versionCode = 5
        versionName = "2.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation3:navigation3-runtime:1.2.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    // material3 内部会用到 core 图标集，这里显式固定版本；扩展图标集已不再需要（改用 Material Symbols 字体）。
    implementation("androidx.compose.material:material-ripple:1.12.0")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.12.0")
    testImplementation("org.robolectric:robolectric:4.17")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.0")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
layout.buildDirectory.set(file("build-upgraded"))
