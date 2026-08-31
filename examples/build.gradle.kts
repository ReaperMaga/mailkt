plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    implementation(project(":core"))
    implementation(project(":outlook"))
    implementation(project(":gmail"))
    implementation(libs.dotenv)
    runtimeOnly(libs.slf4jSimple)
}
