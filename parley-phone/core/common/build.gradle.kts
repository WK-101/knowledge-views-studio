import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Pure-Java vCard parser/writer. Its HTML (jsoup, freemarker) and jCard (jackson) extras are never used,
    // so they are excluded to keep the APK small and free of unused code.
    api(libs.ezvcard) {
        exclude(group = "org.jsoup")
        exclude(group = "org.freemarker")
        exclude(group = "com.fasterxml.jackson.core")
    }
    testImplementation(libs.junit)
}

// Offline spam-list pack builder (see app.parley.common.spam.PackTool), e.g.
// ./gradlew :core:common:buildSpamPack --args="--ftc dnc.csv --out ftc.parleylist --id gov.ftc.dnc --name 'FTC reported calls'"
tasks.register<JavaExec>("buildSpamPack") {
    group = "parley"
    description = "Builds a .parleylist spam-list pack from public data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.parley.common.spam.PackTool")
    workingDir = rootProject.projectDir
}
