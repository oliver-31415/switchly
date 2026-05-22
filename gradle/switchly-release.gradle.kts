import java.io.File

// Release helpers for official Switchly APK/AAB output generation.
// Inputs are provided by app/build.gradle.kts through Gradle extra properties.

data class SwitchlyReleaseArtifact(
    val taskName: String,
    val candidates: List<String>,
    val outputName: String,
)

fun Project.copySwitchlyArtifact(
    candidates: List<String>,
    outputName: String,
    distDir: File,
) {
    val source = candidates
        .map { layout.buildDirectory.file(it).get().asFile }
        .firstOrNull { it.isFile }
        ?: throw GradleException(
            "Could not find build artifact for $outputName. Checked: ${candidates.joinToString()}"
        )

    val output = distDir.resolve(outputName)
    source.copyTo(output, overwrite = true)
    logger.lifecycle("Created: ${output.absolutePath}")
}

val switchlyVersionName = extra["switchlyVersionName"] as String
val googleServicesJson = extra["switchlyGoogleServicesJsonFile"] as File
val googleWebClientId = extra["switchlyGoogleWebClientId"] as org.gradle.api.provider.Provider<String>
val releaseSigningConfigured = extra["switchlyReleaseSigningConfigured"] as Boolean
val releaseStoreFile = extra["switchlyReleaseStoreFile"] as org.gradle.api.provider.Provider<String>
val externalCheckoutUrl = extra["switchlyExternalCheckoutUrl"] as org.gradle.api.provider.Provider<String>
val switchlyDownloadsUrl = extra["switchlyDownloadsUrl"] as org.gradle.api.provider.Provider<String>

val switchlyDistDir = rootProject.layout.projectDirectory.dir("dist")
val switchlyReleaseArtifacts = listOf(
    SwitchlyReleaseArtifact(
        taskName = "assembleFullRelease",
        candidates = listOf(
            "outputs/apk/full/release/app-full-release.apk",
            "outputs/apk/full/release/app-full-release-unsigned.apk",
        ),
        outputName = "Switchly-$switchlyVersionName-full.apk",
    ),
    SwitchlyReleaseArtifact(
        taskName = "assembleFirebaseEmailRelease",
        candidates = listOf(
            "outputs/apk/firebaseEmail/release/app-firebaseEmail-release.apk",
            "outputs/apk/firebaseEmail/release/app-firebaseEmail-release-unsigned.apk",
        ),
        outputName = "Switchly-$switchlyVersionName-firebase-email.apk",
    ),
    SwitchlyReleaseArtifact(
        taskName = "assembleOfflineRelease",
        candidates = listOf(
            "outputs/apk/offline/release/app-offline-release.apk",
            "outputs/apk/offline/release/app-offline-release-unsigned.apk",
        ),
        outputName = "Switchly-$switchlyVersionName-offline.apk",
    ),
    SwitchlyReleaseArtifact(
        taskName = "bundleFullRelease",
        candidates = listOf("outputs/bundle/fullRelease/app-full-release.aab"),
        outputName = "Switchly-$switchlyVersionName-full-playstore.aab",
    ),
)

tasks.register("checkSwitchlyReleaseInputs") {
    group = "switchly"
    description = "Checks inputs required for official Switchly release artifacts."

    doLast {
        if (!googleServicesJson.isFile) {
            throw GradleException(
                "Firebase release artifacts need app/google-services.json. " +
                    "Place it there, set GOOGLE_SERVICES_JSON_PATH in signing.properties, " +
                    "or pass -PGOOGLE_SERVICES_JSON_PATH=/path/to/google-services.json."
            )
        }

        if (googleWebClientId.get().isBlank()) {
            throw GradleException(
                "Google sign-in release artifacts need a Web client ID. " +
                    "Set GOOGLE_WEB_CLIENT_ID in signing.properties or ensure google-services.json " +
                    "contains an OAuth client with client_type 3."
            )
        }

        if (releaseSigningConfigured && !rootProject.file(releaseStoreFile.get()).isFile) {
            throw GradleException("Release keystore not found: ${releaseStoreFile.get()}")
        }

        if (!releaseSigningConfigured) {
            logger.warn(
                "Release signing is not configured. Gradle may create unsigned release artifacts; " +
                    "set SWITCHLY_RELEASE_STORE_FILE, SWITCHLY_RELEASE_STORE_PASSWORD, " +
                    "SWITCHLY_RELEASE_KEY_ALIAS and SWITCHLY_RELEASE_KEY_PASSWORD for signed releases."
            )
        }

        if (externalCheckoutUrl.get().isBlank()) {
            logger.warn(
                "SWITCHLY_EXTERNAL_CHECKOUT_URL is empty. The firebaseEmail APK will build, " +
                    "but external Premium checkout will show as not configured until this is set."
            )
        }

        if (switchlyDownloadsUrl.get().isBlank()) {
            logger.warn("SWITCHLY_DOWNLOADS_URL is empty. Download/About links may be unavailable in this build.")
        }
    }
}

tasks.register("release-apk") {
    group = "switchly"
    description = "Builds Switchly release APK variants and the full Play Store AAB, then copies them to dist/."

    dependsOn("checkSwitchlyReleaseInputs")
    dependsOn(switchlyReleaseArtifacts.map { it.taskName })

    doLast {
        val distDir = switchlyDistDir.asFile
        distDir.mkdirs()

        switchlyReleaseArtifacts.forEach { artifact ->
            copySwitchlyArtifact(
                candidates = artifact.candidates,
                outputName = artifact.outputName,
                distDir = distDir,
            )
        }

        logger.lifecycle("Done. Release files are in: ${distDir.absolutePath}")
    }
}

tasks.register("releaseApk") {
    group = "switchly"
    description = "Alias for release-apk."
    dependsOn("release-apk")
}
