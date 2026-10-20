plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidLibrary {
        namespace = "com.qshlab.daybreak.shared"
        compileSdk = 37
        minSdk = 29
    }
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        // Both targets run on the JVM, so nearly all code lives in jvmShared and can use java.time,
        // javax.crypto, OkHttp, etc. commonMain stays empty.
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api(compose.materialIconsExtended)
                api(compose.ui)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                api(libs.okhttp)
                api(libs.zxing.core)
                api(libs.anthropic.java)
                // Mail (IMAP): the javax.mail API here; each platform brings its implementation.
                compileOnly(libs.javamail)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.android.mail)
            implementation(libs.android.activation)
        }
        val desktopMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation(libs.javamail)
                implementation(libs.jakarta.activation)
            }
        }
    }
}
