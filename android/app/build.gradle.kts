import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun gradleOrLocalProperty(name: String): String =
    (findProperty(name) as String?)
        ?: System.getenv(name)
        ?: localProperties.getProperty(name, "")

fun parseVersionCode(versionName: String): Int {
    val parts = versionName.split('-', '+').first().split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 10_000 + minor * 100 + patch
}

val releaseVersionName = gradleOrLocalProperty("VERSION_NAME").ifBlank { "1.0.0-SNAPSHOT" }
val releaseVersionCode =
    gradleOrLocalProperty("VERSION_CODE").toIntOrNull() ?: parseVersionCode(releaseVersionName)

android {
    namespace = "com.mapbox.toolkit.example"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mapbox.toolkit.example"
        minSdk = libs.versions.appMinSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        buildConfigField(
            "String",
            "MAPBOX_ACCESS_TOKEN",
            "\"${gradleOrLocalProperty("MAPBOX_ACCESS_TOKEN")}\"",
        )
    }

    signingConfigs {
        create("release") {
            val storeFilePath = gradleOrLocalProperty("ANDROID_KEYSTORE_PATH")
            if (storeFilePath.isNotBlank()) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = gradleOrLocalProperty("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = gradleOrLocalProperty("ANDROID_KEY_ALIAS")
                keyPassword = gradleOrLocalProperty("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // The OpenAI key is only embedded in debug builds.
            buildConfigField("String", "OPENAI_API_KEY", "\"${gradleOrLocalProperty("OPENAI_API_KEY")}\"")
        }
        release {
            buildConfigField("String", "OPENAI_API_KEY", "\"\"")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "dash-sdk.properties"
        }
    }

    configurations.all {
        exclude(group = "org.chromium.net", module = "cronet-embedded")
        exclude(group = "org.chromium.net", module = "cronet-common")
        exclude(group = "org.chromium.net", module = "cronet-shared")
        exclude(group = "org.chromium.net", module = "httpengine-native-provider")
    }
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

dependencies {
    // Toolkit
    implementation(project(":toolkit"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Mapbox Navigation SDK UX Framework
    implementation(libs.mapbox.navigation.ux)
    implementation(libs.mapbox.navigation.agent)
    implementation(libs.mapbox.navigation.agent.ui)

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests: jUnit rules and runners
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)

    // GPT-Live: Ktor HTTP for the session handshake, Ktor WebRTC for media and events
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.webrtc)
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.kotlinx.serialization.json)

    // Mapbox MCP server client, for the feedback tool
    implementation(libs.mcp.kotlin.sdk.client)
}
