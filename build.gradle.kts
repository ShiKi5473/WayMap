// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

// Published coordinates match what JitPack serves for this multi-module repo:
//   com.github.ShiKi5473.WayMap:<module>:<tag>
// JitPack passes -Pversion=<tag>; local builds use `version` from gradle.properties.
subprojects {
    group = "com.github.ShiKi5473.WayMap"
}
