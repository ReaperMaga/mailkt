plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    implementation(libs.msal4j)
    implementation(project(":core"))
}
