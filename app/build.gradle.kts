plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** A build setting from the environment (GitHub Actions) or from a Gradle property (~/.gradle/gradle.properties). */
fun buildSetting(env: String, property: String): String? =
    (providers.environmentVariable(env).orNull ?: providers.gradleProperty(property).orNull)?.takeIf { it.isNotBlank() }

// Release signing key. It never lives in this public repository: CI decodes it from secrets into a temporary file
// (see docs/SIGNING.md). Without it, release builds fall back to the debug key, which differs on every machine, so
// such an APK cannot be installed over one signed with the release key.
val releaseKeystore = buildSetting("GALAXY_STEWARD_KEYSTORE", "galaxySteward.keystore")
val releaseStorePassword = buildSetting("GALAXY_STEWARD_KEYSTORE_PASSWORD", "galaxySteward.keystorePassword")
val releaseKeyAlias = buildSetting("GALAXY_STEWARD_KEY_ALIAS", "galaxySteward.keyAlias") ?: "galaxy-steward"
val releaseKeyPassword = buildSetting("GALAXY_STEWARD_KEY_PASSWORD", "galaxySteward.keyPassword") ?: releaseStorePassword
if (releaseKeystore != null && releaseStorePassword == null) {
    throw GradleException("GALAXY_STEWARD_KEYSTORE is set but GALAXY_STEWARD_KEYSTORE_PASSWORD is not")
}

// CI passes its run number: every build gets a higher version code, so it installs as an update over the last one,
// and Shizuku restarts the privileged helper with the new code.
val buildNumber = buildSetting("GALAXY_STEWARD_VERSION_CODE", "galaxySteward.versionCode")?.let {
    it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw GradleException("GALAXY_STEWARD_VERSION_CODE must be a positive number, not '$it'")
}

android {
    namespace = "com.galaxy.steward"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.galaxy.steward"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber ?: 1
        versionName = if (buildNumber != null) "1.2.$buildNumber" else "1.2.0-dev"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "3g"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
