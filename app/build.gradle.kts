plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jimeng.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jimeng.ime"
        minSdk = 26
        targetSdk = 34
        versionCode = 93
        versionName = "0.89.2-beta1"

        ndk {
            abiFilters += "arm64-v8a"
        }
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

    // 打包产物带上版本号，方便区分不同 beta（不影响安装）
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "jimeng-${versionName}-${buildType.name}.apk"
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
        viewBinding = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/**"
        }
    }
    androidResources {
        // 字体不压缩。
        //
        // 默认情况下 .ttf 是会被 zip 压进 APK 的（aapt 的「不压缩白名单」里只有
        // 图片/音视频，没有字体）。Typeface.createFromAsset 读到压缩流时，
        // 得先把整个字体解压到临时文件再交给底层 —— 我们这里最胖的一款有 10MB，
        // 每次键盘重建、每次设置页刷新字体都要走一遍，明显卡顿。
        // 声明成 noCompress 后字体在 APK 里是原样存储，可以直接 mmap，快得多。
        //
        // 代价是 APK 会大一点（字体本身压不太动，差别很小），值得。
        //
        // onnx：语音模型 239MB，压不动（实测存压都是 239MB），但**必须** Stored。
        // 压缩存放的话 AssetManager 读到的是 deflate 流，sherpa-onnx 拿不到
        // 可 seek 的文件描述符，每次加载都得先整份解到临时文件——冷启动直接崩到十几秒，
        // 而且解压还要额外占 239MB 空间。Stored 就能原地读。
        noCompress += listOf("ttf", "otf", "ttc", "onnx")
    }

    sourceSets["main"].assets.srcDirs("src/main/assets")
}

dependencies {
    // 跟 fcitx5-android 一致：splitties 3.0.0 + constraintlayout 2.2.1
    // 注意 splitties 的产物名带 -android / -jvm 后缀，写裸名会解析不到
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // 候选栏照抄 fcitx5：FlexboxLayoutManager 撑出「固定等分格」
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.louiscad.splitties:splitties-dimensions-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-resources-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-constraintlayout-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-bitflags-jvm:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
