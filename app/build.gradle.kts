plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.vpnshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.vpnshare"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // 用 flavor 而不是 splits：ABI splits 只拆 .so，不拆 assets，
    // 会把两份内核（各 ~21MB）都塞进每个 APK。flavor 可以给每个 ABI
    // 指定独立的 assets 目录，内核/geo 才真正只打一份。
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("armv7") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
        }
    }

    // flavor 的 assets 目录必须写在 android 层级：productFlavors 的 lambda
    // 接收者是 ApplicationProductFlavor，那里没有 sourceSets 属性。
    // （其实 src/<flavor>/assets 是 AGP 默认源集，写出来只是让它显式可见。）
    sourceSets {
        getByName("arm64") { assets.srcDirs("src/arm64/assets") }
        getByName("armv7") { assets.srcDirs("src/armv7/assets") }
    }

    androidResources {
        // 这些本来就是压缩数据或二进制，别再让 aapt 压一遍
        noCompress += listOf("dat", "metadb", "mmdb", "gz")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 扫码导入订阅。用 zxing-android-embedded：自带相机封装与扫码界面，
    // 比 MLKit+CameraX 轻得多（CMFA 用的是后者，但我们要控制体积）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    // org.json 在 Android 运行时由系统提供；JVM 单测需要显式依赖才能跑 CoreApi 的解析
    testImplementation("org.json:json:20231013")
}
