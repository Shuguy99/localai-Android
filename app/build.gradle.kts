plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val llamaVulkan = providers.gradleProperty("llamaVulkan").orNull?.toBoolean() ?: false
val llamaArmArch = providers.gradleProperty("llamaArmArch").orNull?.takeIf { it.isNotBlank() }
// Optional cap for concurrent native compile jobs, e.g. -PlocalaiNativeJobs=2 on a
// low-RAM host. Without it ninja uses every core and heavy clang TUs can OOM/crash.
val llamaNativeJobs = providers.gradleProperty("localaiNativeJobs").orNull?.toIntOrNull()?.takeIf { it > 0 }

// Release signing material lives outside the repository. Override the location with
// -PkeystoreProperties=/path/to/keystore.properties (see README, "Release builds").
val releaseKeystoreProperties = java.util.Properties().apply {
    val override = providers.gradleProperty("keystoreProperties").orNull
    val candidate = if (override != null) {
        file(override)
    } else {
        file(System.getProperty("user.home") + "/.localai-release/keystore.properties")
    }
    if (candidate.isFile) candidate.inputStream().use { load(it) }
}
val hasReleaseSigning = releaseKeystoreProperties.getProperty("storeFile") != null

android {
    namespace = "dev.localai.agent"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "dev.localai.agent"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // ARM64 only: every shipping 2B-4B device we support is arm64-v8a.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DLLAMA_VULKAN=$llamaVulkan",
                )
                // Optional per-device tuning, e.g. -PllamaArmArch=armv8.2-a+dotprod.
                // Not a default: older ARMv8.0 devices would SIGILL on dotprod code.
                if (llamaArmArch != null) {
                    arguments += "-DGGML_CPU_ARM_ARCH=$llamaArmArch"
                }
                if (llamaNativeJobs != null) {
                    // Ninja job pools are the only reliable way to cap parallelism:
                    // AGP invokes ninja directly and ignores CMAKE_BUILD_PARALLEL_LEVEL.
                    arguments += listOf(
                        "-DCMAKE_JOB_POOL_COMPILE=compile",
                        "-DCMAKE_JOB_POOLS=compile=$llamaNativeJobs",
                    )
                }
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
            }
        }
    }

    /**
     * Two flavors, because Android 10+ blocks execve() of files written into the
     * app's data directory when targetSdk >= 29 (W^X / "untrusted_app_29" SELinux).
     *
     * proot    : targetSdk 28 => guest binaries inside the rootfs are executable.
     *            Sideload only. Cannot be uploaded to Google Play (min targetSdk policy).
     * standard : targetSdk 35 => Play-compliant, chat + model download work, the
     *            container is disabled with an explanatory error.
     */
    flavorDimensions += "exec"
    productFlavors {
        create("proot") {
            dimension = "exec"
            targetSdk = 28
            buildConfigField("boolean", "PROOT_EXEC_ALLOWED", "true")
            versionNameSuffix = "-proot"
        }
        create("standard") {
            dimension = "exec"
            targetSdk = 35
            buildConfigField("boolean", "PROOT_EXEC_ALLOWED", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // Required so libproot.so is extracted to nativeLibraryDir and can be exec'd.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    androidResources {
        // Do not double-compress the rootfs tarball.
        noCompress += listOf("gz", "gguf")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Without signing material the release APK stays unsigned instead of
            // silently falling back to the debug key.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
}
