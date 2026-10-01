plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    api(libs.kotlinxCoroutines)
    implementation(libs.angusMail)
    implementation(libs.kotlinxSerialization)
    implementation(libs.slf4jApi)
    testImplementation(kotlin("test"))
}
