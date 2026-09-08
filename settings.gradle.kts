pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Breez SDK - Spark (pagamentos Lightning self-custodial, opt-in) —
        // não está publicada no Maven Central, só no repositório próprio da Breez.
        maven { url = uri("https://mvn.breez.technology/releases") }
    }
}

rootProject.name = "pkmbtcwallet"
