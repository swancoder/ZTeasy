import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.spring.webflux)
    implementation(libs.spring.actuator)
    implementation(libs.spring.oauth2.resource)
    implementation(libs.spring.security)
    implementation(libs.kotlin.stdlib)
    implementation(libs.jackson.kotlin)
    // ADR-037: secrets come from the gitignored .env, never from a committed default.
    implementation(libs.spring.dotenv)

    testImplementation(libs.spring.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.mockito.kotlin)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("zt-chat.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
