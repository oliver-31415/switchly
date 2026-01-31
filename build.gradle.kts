// Root build.gradle.kts
// everything is in settings.gradle.kts

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}