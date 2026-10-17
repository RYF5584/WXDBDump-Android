import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.ryf5584.wxdbdump"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ryf5584.wxdbdump"
        minSdk = 26
        targetSdk = 34
        // Derived from the release tag so an upgrade install is never blocked by an
        // unchanged versionCode: v1.2.3 becomes versionCode 1002003 and name "1.2.3".
        versionCode = resolveVersion().code
        versionName = resolveVersion().name

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    // Release signing.
    //
    // No keystore is committed: that would publish the key alongside the APK it signs. The
    // values come from keystore.properties locally, or from the environment in CI.
    //
    // Signing matters more than it looks. A CI runner has no ~/.android/debug.keystore, so a
    // release build that falls back to the debug config gets a freshly generated key every
    // run. Every release would then be signed by a different key, and Android refuses to
    // install an update whose signature differs - the versionCode scheme below cannot help,
    // because the check happens first.
    // Loaded as UTF-8 on purpose. Properties.load(InputStream) decodes ISO-8859-1, which turns a
    // path containing non-ASCII characters into mojibake, and the build then fails to find a
    // keystore that is right there.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
    }
    fun signingValue(property: String, env: String): String? =
        keystoreProps.getProperty(property) ?: System.getenv(env)

    val storePath = signingValue("storeFile", "RELEASE_STORE_FILE")
    val storePass = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
    val aliasName = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
    val aliasPass = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")

    signingConfigs {
        if (storePath != null && storePass != null && aliasName != null && aliasPass != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = aliasName
                keyPassword = aliasPass
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only refuse when a release build was actually asked for. Gradle configures every
            // project regardless of the task, so failing here unconditionally would break
            // :payload:test and anything else that never touches this variant.
            val releaseSigning = signingConfigs.findByName("release")
            val wantsRelease = gradle.startParameter.taskNames.any {
                it.contains("Release", ignoreCase = true)
            }
            if (releaseSigning == null && wantsRelease &&
                !providers.gradleProperty("allowDebugSigning").isPresent
            ) {
                throw GradleException(
                    "No release signing key. Provide keystore.properties, or pass " +
                        "-PallowDebugSigning for a local build you will not distribute.",
                )
            }
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
        debug {
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

    packaging {
        resources.excludes.add("META-INF/*.kotlin_module")
        resources.excludes.add("META-INF/{AL2.0,LGPL2.1}")
        resources.excludes.add("kotlin/**")
        resources.excludes.add("**/*.kotlin_builtins")
        resources.excludes.add("DebugProbesKt.bin")
        // Legacy packaging leaves the native libraries in the APK and extracts them at
        // install time. The root path loads them by file path, which does not work for
        // libraries mapped straight out of the APK.
        jniLibs.useLegacyPackaging = true
    }

    dependenciesInfo {
        includeInApk = false
    }

    // Fixed artifact name so CI downloads and release assets stay predictable
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "WXDB-Dump-Android-$name.apk"
        }
    }

    lint {
        // With this AGP and Kotlin combination the lifecycle lint check throws
        // IncompatibleClassChangeError and crashes lintVitalRelease.
        // Release therefore packages without running lint.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Aggregate module: supplies the module metadata, assets and the shared engine
    api(project(":xposed"))
    implementation(project(":payload"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    // Root mode drives su through ProcessBuilder directly, with no shell library, and
    // decrypts in process using the same format WeChat's own SQLCipher uses.
    // Root-mode decryption needs no cipher library: WeChat uses standard PBKDF2-HMAC-SHA1
    // with AES-256-CBC, and only the page layout is SQLCipher's variant where the IV sits in
    // the last 16 bytes of each page. See SqlCipherDecryptor.kt.
    // The SQLite base class is needed on the compile classpath
    // 
    // Read-only MMKV parsing, used by root mode to recover the uin
    implementation(libs.mmkv)

    debugImplementation(libs.compose.ui.tooling)
}

/**
 * Release tags drive the version. A tag such as `v1.2.3` yields versionCode 1002003, so
 * every release can upgrade over the previous one instead of colliding on a fixed code.
 * Anything that is not a tag falls back to 1.0.0 for local builds.
 */
/**
 * Version numbers derived from the release tag.
 *
 * `v1.2.3` becomes versionCode 1002003 and name 1.2.3. A two-part tag pads the patch, so
 * `v2.1` is 2001000 rather than falling back to 1 — a lower code than an earlier release
 * could not be installed over it. A pre-release suffix sorts one below its final release,
 * so `v1.0.0-rc1` upgrades cleanly to `v1.0.0`.
 *
 * A tag that cannot be parsed fails the build rather than quietly producing 1.0.0: a
 * silent downgrade would ship an APK that no installed copy can upgrade to. Only a build
 * with no tag at all — a local debug build — falls back to 1.0.0.
 */
data class BuildVersion(val code: Int, val name: String)

fun resolveVersion(): BuildVersion {
    val raw = (System.getenv("GITHUB_REF_NAME")
        ?: providers.gradleProperty("versionTag").orNull
        ?: "").removePrefix("v")
    if (raw.isEmpty()) return BuildVersion(1, "1.0.0")

    val core = raw.substringBefore("-").split(".")
    val nums = core.map { it.toIntOrNull() }
    if (core.size !in 2..3 || nums.any { it == null }) {
        throw GradleException("Cannot parse version tag '$raw'; expected vMAJOR.MINOR[.PATCH]")
    }
    val (major, minor, patch) = listOf(
        nums[0]!!, nums.getOrElse(1) { 0 }!!, nums.getOrElse(2) { 0 }!!,
    )
    if (major > 999 || minor > 999 || patch > 999) {
        throw GradleException("Version components must be 0..999, got '${raw}'")
    }

    // Each 0..999 component gets its own three digits, so any two distinct x.y.z differ.
    var code = major * 1_000_000 + minor * 1_000 + patch
    if (raw.contains("-")) code -= 1
    return BuildVersion(code, raw)
}
