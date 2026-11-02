// Top-level build script: declares plugin versions only; modules configure themselves.
//
// Version choice:
//   AGP 8.7.3 with Gradle 8.9, Kotlin 2.1.10 and compileSdk 36 is a combination that has
//   been built and tested locally, so the project stays on it.
//   Moving to another AGP or Gradle version requires a fresh download and a full rebuild.
plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.android.library") version "9.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
