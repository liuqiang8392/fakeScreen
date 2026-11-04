import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
}

// 签名配置从项目根目录的 keystore.properties 读取（该文件不进 git）。
// 文件不存在时 release 包退回 debug 签名，方便他人 clone 后直接编译。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "top.chuyb.fakeScreen"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 31
        minSdk = 26
        versionCode = 22
        versionName = "2.2"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["release"]
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.core)
    implementation(libs.appcompat)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation("com.google.android.material:material:1.12.0")
}
