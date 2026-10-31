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

// Robolectric fetched its Android jar itself at test time, from Maven Central and outside Gradle's repositories and cache,
// and Central's HTTP 429s failed the tests for no reason in the code. Gradle fetches it now, like any dependency; the
// version is the one Robolectric (libs.versions.toml) runs the tests' SDK 35 on, so a Robolectric update that needs
// another fails the tests loudly instead of downloading it.
val robolectricRuntime: Configuration by configurations.creating { isTransitive = false }
dependencies {
    robolectricRuntime(libs.robolectric.android.all)
}
val robolectricJarsDir = layout.buildDirectory.dir("robolectric-jars")
val robolectricJars = tasks.register<Sync>("robolectricJars") {
    from(robolectricRuntime)
    into(robolectricJarsDir)
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
        // A debug build installs beside the real app instead of over it: their keys differ, and Android only lets a build
        // in over an app signed with another key by uninstalling it, which wipes History (every Undo), rules and choices.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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
                // Robolectric's Android jar comes from Gradle (below), not from Maven Central at test time.
                it.dependsOn(robolectricJars)
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", robolectricJarsDir.get().asFile.path)
            }
        }
    }
}

// A release without a version code would be 1 and could never update an install (Android refuses a lower code), and
// getting it on would mean uninstalling, which wipes History. CI always passes one; so must a local release build.
val versionCodeMissing = buildNumber == null
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        if (versionCodeMissing) {
            throw GradleException("Set the version code for a release build: -PgalaxySteward.versionCode=<n>, higher than the installed one")
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
