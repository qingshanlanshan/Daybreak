import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

// Development only: `./gradlew :desktopApp:snapshot` draws screens into build/snapshots/*.png without opening a window.
sourceSets {
    create("snapshot") {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
        runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
    }
}
tasks.register<JavaExec>("snapshot") {
    classpath = sourceSets["snapshot"].runtimeClasspath
    mainClass.set("com.qshlab.daybreak.desktop.SnapshotKt")
    args(layout.buildDirectory.dir("snapshots").get().asFile.path)
    environment("DAYBREAK_HOME", layout.buildDirectory.dir("snapshot-home").get().asFile.path)
}

// Development only: `./gradlew -q :desktopApp:synccheck -Phome=<dir> -Pdo="connect|code|join <code>|run"` (see SyncCheck.kt).
tasks.register<JavaExec>("synccheck") {
    classpath = sourceSets["snapshot"].runtimeClasspath
    mainClass.set("com.qshlab.daybreak.desktop.SyncCheckKt")
    args((project.findProperty("do") as String? ?: "run").split(" "))
    environment("DAYBREAK_HOME", project.findProperty("home") as String? ?: "")
}

compose.desktop {
    application {
        mainClass = "com.qshlab.daybreak.desktop.MainKt"
        // Release builds go through ProGuard: drops unused code (most of the icon set), keeps the app small.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate.set(false)
        }
        nativeDistributions {
            // Only the Java modules Daybreak uses; keeps the bundled runtime small.
            modules("java.instrument", "java.net.http", "java.sql", "jdk.unsupported", "java.naming", "jdk.crypto.ec")
            targetFormats(TargetFormat.Dmg)
            packageName = "Daybreak"
            packageVersion = providers.fileContents(rootProject.layout.projectDirectory.file("mac/VERSION")).asText.get().trim()
            macOS {
                bundleID = "com.qshlab.daybreak"
                iconFile.set(rootProject.file("mac/Daybreak.icns"))
            }
        }
    }
}
