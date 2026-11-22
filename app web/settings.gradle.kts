pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// Terceiro build independente, como o "app pc": Kotlin escolhido aqui, código
// de protocolo compartilhado pela pasta ../core, compilada como fonte.
rootProject.name = "RangaWeb"
