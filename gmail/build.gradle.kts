plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    implementation(libs.kotlinxSerialization)
    api(project(":core"))
    testImplementation(kotlin("test"))
}
