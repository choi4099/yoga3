plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yogacoach.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yogacoach.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // 고정 서명키: 새 버전을 설치해도 '업데이트'로 덮어쓰기 됨 (삭제 후 재설치 불필요)
    signingConfigs {
        create("yoga") {
            storeFile = file("yoga-release.keystore")
            storePassword = "yoga1234"
            keyAlias = "yoga"
            keyPassword = "yoga1234"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("yoga")
        }
        release {
            signingConfig = signingConfigs.getByName("yoga")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 카메라
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // 사람 자세 인식 (Google ML Kit, 기기 내 처리 - 인터넷 불필요)
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")

    testImplementation("junit:junit:4.13.2")
}
