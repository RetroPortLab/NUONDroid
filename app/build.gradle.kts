import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val nuanceDir = rootProject.file("external/NuanceResurrection")

android {
    namespace = "com.retroportlab.nuondroid"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.retroportlab.nuondroid"
        minSdk = 26          // AAudio
        targetSdk = 34
        // Same scheme as JagDroid: v0.01 -> versionCode 1, v0.02 -> 2 ...
        versionCode = 4
        versionName = "0.04-experimental"

        // The core's execution engine on non-x86 is the IL interpreter, and the
        // Retroid handhelds are all arm64. (x86_64 would additionally need the
        // asmjit submodule, which is what upstream's JIT uses.)
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DNUANCE_DIR=${nuanceDir.absolutePath}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // BIOS .cof files come straight from the upstream checkout, so there is one
    // copy of them and no binaries committed to this repo.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/nuanceAssets"))

    // Release key: read from keystore.properties (never committed) or the
    // NUONDROID_KEYSTORE* environment variables. Every release must be signed with
    // the same key, or Android refuses to install it over the previous version
    // (and the in-app updater can't update).
    val ksProps = Properties()
    val ksFile = rootProject.file("keystore.properties")
    if (ksFile.exists()) ksFile.inputStream().use { ksProps.load(it) }
    val ksPath: String? = ksProps.getProperty("storeFile") ?: System.getenv("NUONDROID_KEYSTORE")
    signingConfigs {
        create("release") {
            if (ksPath != null) {
                storeFile = rootProject.file(ksPath)
                storePassword = ksProps.getProperty("storePassword") ?: System.getenv("NUONDROID_KEYSTORE_PASSWORD")
                keyAlias = ksProps.getProperty("keyAlias") ?: System.getenv("NUONDROID_KEY_ALIAS")
                keyPassword = ksProps.getProperty("keyPassword") ?: System.getenv("NUONDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the release key (a fresh checkout), fall back to the debug key so it still builds.
            signingConfig = signingConfigs.getByName(if (ksPath != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

val copyBios = tasks.register<Copy>("copyNuanceBios") {
    from(nuanceDir) { include("bios.cof", "minibios.cof", "minibiosX.cof") }
    into(layout.buildDirectory.dir("generated/nuanceAssets/nuance"))
    doFirst {
        require(nuanceDir.resolve("bios.cof").exists()) {
            "external/NuanceResurrection is empty - run: git submodule update --init"
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyBios) }

// Small fixes to upstream that the Android build needs, kept as patches so the
// submodule stays a pristine checkout of andkrau/NuanceResurrection.
val applyPatches = tasks.register<Exec>("applyNuancePatches") {
    workingDir = nuanceDir
    commandLine("bash", "-c", """
        for p in ${rootProject.file("patches").absolutePath}/*.patch; do
            if git apply --reverse --check "${'$'}p" >/dev/null 2>&1; then continue; fi   # already applied
            git apply "${'$'}p"
        done
    """.trimIndent())
}
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(applyPatches) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
