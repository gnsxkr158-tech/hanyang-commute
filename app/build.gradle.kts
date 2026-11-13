plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hyt.commute"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hyt.commute"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.11"
    }

    signingConfigs {
        // 개인용 고정 서명키: 같은 키로 서명해야 새 버전이 기존 앱 위에 업데이트로 설치됨
        create("release") {
            storeFile = file("release.jks")
            storePassword = "hytcommute"
            keyAlias = "hyt"
            keyPassword = "hytcommute"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
