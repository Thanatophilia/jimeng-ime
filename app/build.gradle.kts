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
        versionCode = 3
        versionName = "0.3.0"

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

    sourceSets["main"].assets.srcDirs("src/main/assets")
}

dependencies {
    // 跟 fcitx5-android 一致：splitties 3.0.0 + constraintlayout 2.2.1
    // 注意 splitties 的产物名带 -android / -jvm 后缀，写裸名会解析不到
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.louiscad.splitties:splitties-dimensions-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-resources-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-appcompat-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-constraintlayout-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-recyclerview-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-recyclerview-android:3.0.0")
    implementation("com.louiscad.splitties:splitties-collections-jvm:3.0.0")
    implementation("com.louiscad.splitties:splitties-bitflags-jvm:3.0.0")
    implementation("com.louiscad.splitties:splitties-experimental-jvm:3.0.0")
    implementation("org.mechdancer:dependency:0.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
