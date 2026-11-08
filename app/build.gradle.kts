import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
}

// Release signing: a gitignored keystore.properties at
// the repo root names the keystore and alias; the password comes from the system
// keyring (`secret-tool lookup service seniorphone key release-keystore`) or, as
// an escape hatch, a storePassword line in that file. Anything missing leaves the
// release build unsigned instead of failing, so debug builds and tests always work.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingPasswordOrNull(): String? {
    keystoreProperties.getProperty("storePassword")?.takeIf { it.isNotBlank() }?.let { return it }
    val secretTool = listOf("/usr/bin/secret-tool", "/bin/secret-tool").map(::File)
        .firstOrNull { it.canExecute() } ?: return null
    return try {
        val p = ProcessBuilder(secretTool.path, "lookup", "service", "seniorphone", "key", "release-keystore").start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); null }
        else if (p.exitValue() != 0) null
        else out.removeSuffix("\n").takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

val releaseStoreFile = keystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it) }?.takeIf { it.isFile }

// One place for the version, so the APK name below can never disagree with it.
val appVersionName = "0.0.1"
val appVersionCode = 1

// APKs come out as SeniorPhone-<version>-<buildtype>.apk, ready to attach to a
// GitHub release. An unsigned release build ends in "-unsigned.apk", which is
// the cheap check that signing actually happened.
base {
    archivesName.set("SeniorPhone-$appVersionName")
}

android {
    namespace = "app.phueber.seniorphone"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.phueber.seniorphone"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        val password = signingPasswordOrNull()
        if (releaseStoreFile != null && password != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = password
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    // No runtime dependencies on purpose: platform APIs only.
    testImplementation("junit:junit:4.13.2")
}
