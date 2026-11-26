import java.io.File

plugins {
    id("com.android.application")
}

// The repository's root (protocol/, the LICENSE): the Gradle project is android/.
val repoDir: File = rootProject.projectDir.parentFile

// One version: gradle.properties' companion.version. 0.1.0 -> versionCode 100.
val appVersion: String = providers.gradleProperty("companion.version").get()
val appVersionCode: Int = appVersion.substringBefore('-').split('.')
    .map { it.toInt() }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// The release key, from the environment only (the release workflow sets it
// from the repository's secrets; see README, "Signing"). Without it a release
// build is left unsigned rather than signed with some other key.
val keystorePath: String? = System.getenv("COMPANION_KEYSTORE_FILE")?.takeIf { it.isNotEmpty() }

android {
    namespace = "io.github.c0decave.axross.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.c0decave.axross.companion"
        // 26: Android 8.0 - startForegroundService, adaptive icons,
        // notification channels.
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        keystorePath?.let { path ->
            create("release") {
                storeFile = File(path)
                storePassword = System.getenv("COMPANION_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("COMPANION_KEY_ALIAS")
                keyPassword = System.getenv("COMPANION_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // (a few small classes and no library: nothing worth shrinking,
            // and no rules to keep anything by)
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The licence text, as an asset (licenses/LICENSE in the APK).
    sourceSets {
        getByName("main").assets.srcDir(layout.buildDirectory.dir("licenses").get().asFile)
    }

    lint {
        // (reported, not fatal: the first builds happen in CI only; the
        // report is an artifact of the build job)
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// The unit tests read the shared protocol definition (protocol/fixtures) and
// check the code against it.
tasks.withType<Test>().configureEach {
    systemProperty("protocol.dir", File(repoDir, "protocol").absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val licenseAssets = tasks.register<Copy>("licenseAssets") {
    from(File(repoDir, "LICENSE"))
    into(layout.buildDirectory.dir("licenses/licenses"))
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(licenseAssets) }
