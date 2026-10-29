// embedded-compose-a2ui: an A2UI v0.9 renderer (Google's Agent-to-User
// Interface protocol) for embedded-compose, drawn with its Material 3 module
// (:core:material). It builds inside an embedded-compose checkout, as a
// library of an app kept outside that repo: the app's app.env lists it,
//     APP_LIBS="a2ui=<path to this directory>"
// and its build.gradle.kts adds implementation(project(":libs:a2ui")).
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    linuxArm64()
    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                api(project(":core:material"))
            }
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}
