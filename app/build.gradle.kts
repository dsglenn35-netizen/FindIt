import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 版本号唯一来源：APK 产物命名为 findit-<版本号>.apk
val appVersionName = "2.2"

// 签名配置从 keystore.properties 读取（该文件已被 .gitignore 忽略，不进仓库）
// 没有该文件时 release 退回 debug 签名，保证任何人 clone 后都能直接构建
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}
val hasReleaseKeystore = !keystoreProps.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "com.home.findit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.home.findit"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = appVersionName
    }

    // 所有变体的 APK 文件名统一为 findit-<版本号>.apk
    // （本地产物、CI artifact、GitHub Release 资产同名）
    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "findit-${appVersionName}.apk"
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.pinyin4j)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
}
