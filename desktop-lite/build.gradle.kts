import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin", "../shared/src")
}

sourceSets["main"].kotlin.exclude("**/UiCommon.kt")

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("javazoom:jlayer:1.0.1")
    implementation("de.sciss:jump3r:1.0.5")
}

application {
    mainClass.set("id.my.radioislamsragen.studio.LiteUiKt")
    applicationName = "RaisStreamRingan"
}
