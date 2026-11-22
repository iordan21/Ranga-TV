pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

// Build independente do "app tv" de propósito: aqui o Kotlin é escolhido por
// mim, lá quem manda é o que vem embutido no AGP. As duas apps compartilham
// código pela pasta ../core, compilada como fonte nos dois lados.
rootProject.name = "RangaPC"
