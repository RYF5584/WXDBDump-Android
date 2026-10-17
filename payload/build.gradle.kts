plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// payload deliberately has no Android dependency. That is what lets one engine serve
//   - Xposed mode runs it inside WeChat's process as part of :xposed
//   - root mode runs it in the app process
//   - a future Frida backend could inject the same classes as a dex
// Any import android.* or Android dependency in payload breaks that.
dependencies {
    // org.json exists in the Android runtime; this only satisfies javac and is not packaged
    compileOnly(libs.json)

    // Plain JUnit: payload is pure Java, so its logic is testable without a device.
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    archiveFileName.set("payload.jar")
    manifest {
        attributes(
            "Implementation-Title" to "WXDB-Dump-Android payload",
            "Implementation-Version" to "1.0",
        )
    }
}
