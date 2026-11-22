plugins {
    kotlin("jvm") version "2.2.10"
    application
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        // O mesmo protocolo Xtream dos outros dois apps. O servidor web é só
        // mais uma "tela" em cima dele.
        kotlin.srcDir("../core/src/main/kotlin")
    }
    test {
        kotlin.srcDir("../core/src/test/kotlin")
    }
}

dependencies {
    // Sem framework web: o servidor HTTP é o que vem no próprio JDK
    // (com.sun.net.httpserver). Para uma casa com três aparelhos ele sobra, e
    // é uma dependência a menos para casar com a versão do Kotlin.
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("br.com.iordan.ranga.web.MainKt")
    applicationName = "ranga-web"
}

tasks.test {
    useJUnit()
}
