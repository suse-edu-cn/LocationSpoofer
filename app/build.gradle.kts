import java.io.FileInputStream
import org.yaml.snakeyaml.Yaml

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

// 读取 local.yml 配置（本地开发），CI 从环境变量读取
fun loadLocalYml(): Map<String, String> {
    val ymlFile = rootProject.file("local.yml")
    return if (ymlFile.exists()) {
        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        yaml.load(FileInputStream(ymlFile)) as Map<String, String>
    } else {
        emptyMap()
    }
}

val localYml = loadLocalYml()
fun secret(key: String): String = System.getenv(key) ?: localYml[key] ?: ""

android {
    namespace = "com.suseoaa.locationspoofer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.suseoaa.locationspoofer"
        minSdk = 26
        targetSdk = 34
        versionCode = 149
        versionName = "1.4.9"

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "WIGLE_TOKEN", "\"${secret("WIGLE_TOKEN")}\"")
        buildConfigField("String", "AMAP_API_KEY", "\"${secret("AMAP_API_KEY")}\"")
        manifestPlaceholders["AMAP_API_KEY"] = secret("AMAP_API_KEY")
    }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE_PATH")
                ?: "/Users/vincent/Desktop/SUSE-APP-Key/APP-Key.jks"
            if (file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.libsu.core)
    implementation(libs.koin.androidx.compose)
    implementation(libs.amap.map)
    implementation(libs.amap.search)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
}