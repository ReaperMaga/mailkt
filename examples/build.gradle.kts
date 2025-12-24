plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":outlook"))
    implementation(libs.dotenv)
}