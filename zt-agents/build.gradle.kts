import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.jvm)
    // Opens Spring-annotated classes for CGLIB proxying (Kotlin classes are final by default)
    alias(libs.plugins.kotlin.spring)
}

// Root build.gradle.kts applies the `java` plugin and JavaCompile --enable-preview to all
// subprojects. Kotlin has its own compiler config below; Java interop files (if any) inherit
// the root config automatically.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict")  // strict null-safety for Spring annotations
    }
}

dependencies {
    implementation(libs.spring.webflux)
    implementation(libs.spring.actuator)
    implementation(libs.kotlin.stdlib)
    implementation(libs.jackson.kotlin)

    testImplementation(libs.spring.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("zt-agents.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
