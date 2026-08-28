plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.evsuite.profile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evsuite.profile"
        minSdk = 28
        targetSdk = 34
        versionCode = 21
        versionName = "3.0.2"
    }

    // Signing with the ROM platform key (required by sharedUserId=android.uid.system).
    // Secrets read from the local environment (CI) or gradle.properties — NEVER committed.
    val keystorePath = System.getenv("EV_KEYSTORE") ?: (project.findProperty("evsuite.keystore") as String?)
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("platform") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("EV_KEYSTORE_PASSWORD") ?: (project.findProperty("evsuite.keystore.password") as String?)
                keyAlias = System.getenv("EV_KEY_ALIAS") ?: (project.findProperty("evsuite.key.alias") as String?) ?: "platform"
                keyPassword = System.getenv("EV_KEY_PASSWORD") ?: (project.findProperty("evsuite.key.password") as String?)
            }
        }
    }

    // Stable is the tagged, offline/manual channel. Unstable is the rolling pre-release
    // channel; its updater implementation and network manifest live only in src/unstable.
    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
            buildConfigField("boolean", "OFFLINE", "true")
            buildConfigField("boolean", "OTA_ENABLED", "false")
        }
        create("unstable") {
            dimension = "channel"
            applicationIdSuffix = ".unstable"
            versionName = "${defaultConfig.versionName}.${project.findProperty("unstableBuild") ?: "0"}"
            versionNameSuffix = "-unstable"
            buildConfigField("boolean", "OFFLINE", "false")
            buildConfigField("boolean", "OTA_ENABLED", "true")
        }
    }

    buildTypes {
        release {
            // [T-909] R8 enabled: shrink + obfuscation. The code is very reflective —
            // proguard-rules.pro lists the targets to keep. Any release MUST pass
            // manual test on vehicle (Katman1/2/3, HVAC, ADAS/AEB/ELK, ignition, OTA)
            // before publishing.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    buildFeatures {
        viewBinding = true
        buildConfig = true
        // ITaskerBridge: IPC contract shared with EVTasker (same AIDL package on both sides).
        aidl = true
    }


    // JVM unit testing (no vehicle, no emulator): Robolectric needs the
    // Android resources to instantiate a Context.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // AGP 9 removed the legacy applicationVariants output API. Release APK naming is
    // handled by the release workflow (renames app-<flavor>-release.apk on collect).
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.register("printUnstableVersion") {
    doLast {
        println("${android.defaultConfig.versionName}.${project.findProperty("unstableBuild") ?: "0"}")
    }
}

dependencies {
    // Shared vehicle layer (git submodule ./EVHardware, subproject :evhardware).
    implementation(project(":evhardware"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.viewpager2)

    // QR code (generation in the Info dialog), in both flavors. The QR is the only
    // way to output a URL from a car screen to a phone: the stable build has it
    // need it more than the other, since it can't download anything itself. ZXing core
    // is pure Java, without networks or permissions.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
