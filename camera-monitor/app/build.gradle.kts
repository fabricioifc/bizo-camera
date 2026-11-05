import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Dados da assinatura ficam em keystore.properties (fora do git).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Endereço do servidor de retransmissão. Troque sem editar este arquivo:
// -PrelayUrl=https://outro.dominio na linha de comando, ou relayUrl=... em ~/.gradle/gradle.properties.
val relayUrl: String = (findProperty("relayUrl") as String?) ?: "https://camera.bizo.dev.br"

// Client ID do tipo "Aplicativo da Web" no Google Cloud (o mesmo GOOGLE_CLIENT_ID do servidor).
// Informe com -PgoogleServerClientId=... ou em ~/.gradle/gradle.properties.
val googleServerClientId: String = (findProperty("googleServerClientId") as String?) ?: ""

android {
    namespace = "br.dev.bizo.cameramonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.dev.bizo.cameramonitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    val cameraxVersion = "1.3.4"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
