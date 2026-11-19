import java.util.Properties

fun localProperty(name: String): String? {
    val file = rootDir.resolve("local.properties")
    if (!file.isFile) return null
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty(name)
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val mapboxDownloadsToken: Provider<String> = providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN")
    .orElse(providers.environmentVariable("MAPBOX_DOWNLOADS_TOKEN"))
    .orElse(providers.provider {
        localProperty("MAPBOX_DOWNLOADS_TOKEN")
            ?: error(
                "MAPBOX_DOWNLOADS_TOKEN is not set. Add it to local.properties, " +
                    "~/.gradle/gradle.properties, or export it as an environment variable.",
            )
    })

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("org\\.chromium.*")
            }
        }
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/snapshots/maven")
            authentication { create<BasicAuthentication>("basic") }
            credentials {
                username = "mapbox"
                password = mapboxDownloadsToken.get()
            }
            content {
                includeModule("com.mapbox.navigationcore", "agent")
                includeModule("com.mapbox.navigationcore", "agent-android")
                includeModule("com.mapbox.navigationcore", "ui-agent")
                includeModule("com.mapbox.navigationcore", "ui-agent-android")
            }
        }
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication { create<BasicAuthentication>("basic") }
            credentials {
                username = "mapbox"
                password = mapboxDownloadsToken.get()
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Mapbox AI Voice Navigation Sample App"

include(":app")
include(":toolkit")
