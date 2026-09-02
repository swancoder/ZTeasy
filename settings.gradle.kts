rootProject.name = "zte-lightweight"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // gradle/libs.versions.toml is auto-discovered as the "libs" catalog in Gradle 7.4+
    // No explicit versionCatalogs block needed
}

include(
    "gateway-service",
    "auth-library",
    "service-a",
    "service-b",
    "zt-agents",
    // ADR-039: the chat console's backend — the tool-calling loop, run for a person
    "zt-chat"
)
