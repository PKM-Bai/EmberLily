plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.emberlily.app"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.emberlily.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.0.2-m1-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cFlags += "-O2"
                cppFlags += "-O2"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
    sourceSets["androidTest"].java.srcDir("src/androidTest/kotlin")
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenses"))
}

// The About screen reads assets/licenses/notices.txt. Keep that text generated
// from the same source notices and license files that accompany the checkout.
val generateLicenseNotices by tasks.registering {
    val parts = listOf(
        "EmberLily / PokeDaisy attribution" to rootProject.file("NOTICE"),
        "GNU General Public License version 3" to rootProject.file("LICENSE"),
        "mGBA - Mozilla Public License 2.0" to file("src/main/assets/licenses/mgba-MPL-2.0.txt"),
        "blip_buf - GNU Lesser General Public License 2.1" to file("src/main/assets/licenses/blip_buf-LGPL-2.1.txt"),
        "inih - BSD 3-Clause" to file("src/main/assets/licenses/inih-BSD-3-Clause.txt"),
        "AndroidX, Compose, Kotlin, coroutines and Gradle - Apache 2.0" to file("src/main/assets/licenses/Apache-2.0.txt"),
    )
    inputs.files(parts.map { it.second })
    val output = layout.buildDirectory.file("generated/licenses/licenses/notices.txt")
    outputs.file(output)
    doLast {
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(
            parts.joinToString("\n\n") { (name, source) -> "=== $name\n\n${source.readText().trim()}" } + "\n",
        )
    }
}
tasks.named("preBuild") { dependsOn(generateLicenseNotices) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
