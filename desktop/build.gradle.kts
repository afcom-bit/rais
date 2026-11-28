import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

val ver = "1.0." + (System.getenv("VERSION_CODE")?.takeIf { it.isNotBlank() } ?: "1")

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin", "../shared/src")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("javazoom:jlayer:1.0.1")
    implementation("de.sciss:jump3r:1.0.5")
}

compose.desktop {
    application {
        mainClass = "id.my.radioislamsragen.studio.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Rais Stream"
            packageVersion = ver
            description = "Studio siaran Radio Islam Sragen"
            vendor = "Radio Islam Sragen"
            modules("java.desktop", "java.prefs", "java.logging")
            windows {
                iconFile.set(project.file("icon.ico"))
                menu = true
                menuGroup = "Rais Stream"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "8c2f6a5e-3b1d-4c7a-9f2e-5d4b7a1c9e30"
            }
        }
    }
}
