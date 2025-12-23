plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    api(libs.bundles.kotlinxEcosystem)
    api(libs.jakartaMail)
}