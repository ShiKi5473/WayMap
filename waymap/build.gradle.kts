plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

android {
    namespace = "com.waymap"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // LatLng / GeoUtils appear in this library's public API.
    api(project(":geo"))
    // Modifier is part of the public API, so Compose UI (and its BOM) is exported.
    api(platform("androidx.compose:compose-bom:2026.06.01"))
    api("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    // 3.0.2+ uses org.maplibre.android.gestures, so the legacy Mapbox gestures
    // library that 3.0.0 needed is no longer required.
    implementation("org.maplibre.gl:android-plugin-annotation-v9:3.0.2")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; use the real implementation.
    testImplementation("org.json:json:20240303")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                artifactId = "waymap"
                from(components["release"])
            }
        }
    }
}
