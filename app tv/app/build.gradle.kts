import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Fora do bloco android {} porque a task `dist` lá embaixo também usa.
// Lida na configuração, não na execução — senão quebra o configuration cache.
val appVersionName = "1.6"

// O versionCode sai do versionName: "1.0" vira 10000, "1.1" vira 10100.
// Derivado, e não escrito à mão, porque número solto é número esquecido — e um
// APK novo com o mesmo versionCode do instalado o Android trata como "já tenho
// essa versão". Subir a linha de cima passa a ser a única coisa a fazer.
val appVersionCode = appVersionName.split(".").let { partes ->
    partes[0].toInt() * 10000 + partes[1].toInt() * 100
}

android {
    namespace = "br.com.iordan.ranga"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "br.com.iordan.ranga"
        // 24 cobre praticamente todo TV Box Android que ainda roda hoje.
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A chave de release e as senhas ficam FORA do projeto, em
    // keystore.properties na raiz do `app tv`. O build só lê. Se o arquivo não
    // existir, cai na chave de debug e avisa no log — nunca falha calado, para
    // você não descobrir que assinou errado só na hora de atualizar o app.
    // Como gerar a chave: ASSINATURA.md.
    val arquivoDeChave = rootProject.file("keystore.properties")
    val temChaveDeRelease = arquivoDeChave.exists()
    if (temChaveDeRelease) {
        // Sem `apply {}` aqui: dentro do build script o `apply` que ganha é
        // o do Gradle (o de aplicar plugin), não o da stdlib do Kotlin.
        val chave = Properties()
        arquivoDeChave.inputStream().use { chave.load(it) }
        signingConfigs.create("release") {
            storeFile = rootProject.file(chave.getProperty("storeFile"))
            storePassword = chave.getProperty("storePassword")
            keyAlias = chave.getProperty("keyAlias")
            keyPassword = chave.getProperty("keyPassword")
        }
    } else {
        logger.warn(
            "Ranga: keystore.properties não encontrado. O release vai sair " +
                "assinado com a chave de debug — veja ASSINATURA.md.",
        )
    }

    buildTypes {
        release {
            // R8 ligado: remove classe/método não usado e reescreve o que sobra.
            // O ganho maior aqui vem do material-icons-core e do media3 — o app
            // usa uma fração deles.
            optimization {
                enable = true
            }
            // Descarta drawable/string de biblioteca que nenhuma tela referencia.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Com keystore.properties no lugar, assina com a sua chave. Sem
            // ele, cai na de debug: instala por adb igual, mas a chave de debug
            // é recriada pelas ferramentas de tempos em tempos. Quando ela
            // muda, a assinatura muda, e assinatura diferente não atualiza por
            // cima — o Android exige desinstalar, levando junto login,
            // favoritos e o "continuar assistindo" do DataStore.
            signingConfig = signingConfigs.getByName(
                if (temChaveDeRelease) "release" else "debug",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // A camada de protocolo Xtream mora em C:\ranga\core e é compilada aqui
    // como se fosse código deste módulo.
    //
    // Por que não um módulo Gradle :core normal? Porque o app de Windows é um
    // build separado, com Kotlin próprio (o Compose Desktop pede uma versão
    // diferente da que vem embutida no AGP). Um módulo compartilhado obrigaria
    // os dois builds a casarem a versão do Kotlin — foi exatamente o tipo de
    // briga que já tivemos com o Coil. Compilando a mesma pasta de fontes nos
    // dois, cada build usa o compilador que quiser e o código continua único.
    // Tem que ser o source set do Kotlin, não o `java`: no AGP 9 o `java`
    // alimenta só o javac, e estes arquivos são todos .kt.
    sourceSets["main"].kotlin.srcDir("../../core/src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("../../core/src/test/kotlin")

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Só o -core (uns 100 ícones, poucos KB). O -extended traz ~1.500 vetores,
    // o que com R8 desligado viraria megabytes de APK à toa.
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Player. O modulo hls e obrigatorio para os links .m3u8 do painel;
    // o MPEG-TS (.ts) ja vem no core do exoplayer.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * `./gradlew dist` — gera a pasta de entrega na raiz do projeto.
 *
 * Existe como task, e não como cópia manual, por um motivo prático: assim o
 * APK entregue nunca fica velho. `dependsOn("assembleRelease")` garante que
 * ele é reconstruído a partir do código atual antes de ser copiado — não tem
 * como você mandar para o TV Box um APK de duas alterações atrás.
 */
tasks.register<Copy>("dist") {
    group = "distribution"
    description = "Compila o release e junta APK + guia de instalação em /dist."

    dependsOn("assembleRelease")

    // Resolvo o nome AQUI, numa variável local, e não dentro do rename {}.
    // O rename roda na execução: se ele lesse `appVersionName` direto, estaria
    // segurando uma referência ao objeto do script, que o configuration cache
    // não sabe serializar. Capturando uma String, serializa liso.
    val apkFileName = "Ranga-$appVersionName.apk"

    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { apkFileName }
    }
    from(rootProject.layout.projectDirectory.file("INSTALAR.md"))

    into(rootProject.layout.projectDirectory.dir("dist"))
}
