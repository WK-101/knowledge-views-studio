import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Parley's own detekt rules (see ParleyRuleSetProvider). detekt runs them with its own Kotlin (2.0), so this module
// is compiled for that language and API level.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    compileOnly(libs.detekt.api)
    // The rules' tests parse Kotlin with detekt's own parser (already used by the detekt task), no extra test library.
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.parser)
    testImplementation(libs.junit)
}

// detekt runs the rules with its own Kotlin (2.0), so the tests do too.
configurations.matching { it.name.startsWith("test") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-compiler")) useVersion("2.0.21")
    }
}
