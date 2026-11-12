@file:Suppress("ktlint:standard:kdoc")

pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kei") {
            from(files("gradle/kei.versions.toml"))
        }
    }
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Yuzono-Anime"

val maintainedLangs = listOf("all", "ar", "en")

include(":core")

for (lang in maintainedLangs) {
    File(rootDir, "src/$lang").listFiles()
        ?.filter { it.isDirectory && (File(it, "build.gradle").exists() || File(it, "build.gradle.kts").exists()) }
        ?.sortedBy { it.name }
        ?.forEach { include(":src:$lang:${it.name}") }
}

// Load all modules under /lib that contain a build file
File(rootDir, "lib").listFiles()?.filter { 
    it.isDirectory && (File(it, "build.gradle").exists() || File(it, "build.gradle.kts").exists()) 
}?.forEach { include("lib:${it.name}") }

// Load all modules under /lib-multisrc that contain a build file
File(rootDir, "lib-multisrc").listFiles()?.filter { 
    it.isDirectory && (File(it, "build.gradle").exists() || File(it, "build.gradle.kts").exists()) 
}?.forEach { include("lib-multisrc:${it.name}") }
