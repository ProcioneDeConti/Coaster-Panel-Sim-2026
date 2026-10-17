import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.abemu.coaster"
    // Station auto-spiel text (spiels/spiels.txt) ships as an asset; its recordings live in src/main/assets/spiels.
    sourceSets["main"].assets.srcDir("../spiels")
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abemu.coaster"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            // R8: shrink, optimize and obfuscate; drop unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Non-debuggable build signed with the debug key, for day-to-day use on the tablet: debuggable builds run
        // mostly interpreted with JNI checks on, which made the UI several times more expensive (battery drain).
        create("fast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

composeCompiler {
    // The UI reads plain (non-snapshot) PLC fields and redraws off vm.frame ticks. Strong skipping skips any child
    // whose RidePlc / SimViewModel argument is the same instance (and memoizes draw lambdas the same way), so the
    // plan, block table, weather etc. froze until a tab switch rebuilt them. Unstable params must always recompose.
    featureFlags.add(org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag.StrongSkipping.disabled())
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Applies the baseline profiles bundled with Compose on sideloaded installs, so startup/UI code is AOT-compiled.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
}
