import java.security.KeyStore
import java.util.Collections

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

fun cleanSecretEnv(name: String): String? {
    val raw = System.getenv(name) ?: return null
    val lastLine = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("SIGNING_") }
        .lastOrNull() ?: return null
    return lastLine.substringAfterLast('=').trim().takeIf { it.isNotEmpty() }
}

android {
    namespace = "io.github.indigodev.kteps"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.indigodev.kteps"
        minSdk = 23
        targetSdk = 34
        versionCode = 7
        versionName = libs.versions.app.get()
    }

    val keystoreFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let(::file)
    val keystorePassword = cleanSecretEnv("KEYSTORE_PASSWORD")
    val requestedAlias = cleanSecretEnv("KEY_ALIAS") ?: "karoo-ep8"
    val keyAliasPassword = cleanSecretEnv("KEY_PASSWORD") ?: keystorePassword
    val hasReleaseSigning = keystoreFile != null &&
        keystoreFile.exists() &&
        keystorePassword != null &&
        keyAliasPassword != null

    signingConfigs {
        if (hasReleaseSigning) {
            val resolvedAlias = runCatching {
                val ks = KeyStore.getInstance(keystoreFile!!, keystorePassword!!.toCharArray())
                if (ks.containsAlias(requestedAlias) && ks.isKeyEntry(requestedAlias)) {
                    requestedAlias
                } else {
                    Collections.list(ks.aliases()).firstOrNull { ks.isKeyEntry(it) } ?: requestedAlias
                }
            }.getOrDefault(requestedAlias)

            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = resolvedAlias
                keyPassword = keyAliasPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
