pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "Daybreak"
// shared: everything (data, sync, calendar, feed, screens); androidApp and desktopApp are thin shells.
include(":shared", ":androidApp", ":desktopApp")
