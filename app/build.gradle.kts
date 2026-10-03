import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从仓库根目录的 keystore.properties 读 release 签名配置。
// 该文件不入库（.gitignore 已排除 *.jks / keystore.properties）。
// 没有它时也能构建，只是产物是 unsigned 包 —— 能编译，但装不上。
// 这样做的用意：贡献者 clone 后无需任何签名材料就能跑通构建与测试，
// 而维护者本地有该文件时会自动打出可发布的签名包。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() && file(releaseStoreFile).exists()

// 版本号只在这里写一次：产物名也要用（见文件末尾的 androidComponents）
val appVersionName = "1.0.2"

android {
    namespace = "io.vpnshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.vpnshare"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = appVersionName
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

    lint {
        // release 打包时不做 lint 阻塞检查。两个理由：
        //  1) lintVitalAnalyze 需要额外下载 lint-checks / intellij-core / kotlin-compiler
        //     三个大 jar，国内网络访问 dl.google.com 经常超时 —— 一个本来能成功的打包
        //     会先卡 6 分钟再失败，而且报的是「Read timed out」，看不出是网络问题；
        //  2) 这类静态检查在 IDE 或 CI 里跑更合适，不该阻塞出包。
        // 需要时手动执行 ./gradlew :app:lint 即可，配置没丢。
        checkReleaseBuilds = false
        abortOnError = false
    }

    androidResources {
        // 这些本来就是压缩数据或二进制，别再让 aapt 压一遍
        noCompress += listOf("dat", "metadb", "mmdb", "gz")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

// 产物名：GuGuGu-clash-<版本>-<flavor>-<buildType>.apk
// 默认名是 app-<flavor>-<buildType>.apk，认不出是哪个项目（沿用了旧仓库名的时代），
// 发布页上容易和其它项目混在一起 —— 这里统一改成仓库名。
// 注：AGP 8.5 的公开 Variant API（androidComponents/VariantOutput）**没有**改产物名的入口
// （VariantOutput 上没有 outputFileName），所以只能用这个沿用多年的内部实现类。
// 将来升级 AGP 若这里编译不过，就是它被移走了，替换成新 API 即可。
android {
    applicationVariants.all {
        val flavor = flavorName
        val type = buildType.name
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "GuGuGu-clash-" + appVersionName + "-" + flavor + "-" + type + ".apk"
        }
    }
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
