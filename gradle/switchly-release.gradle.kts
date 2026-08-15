import groovy.json.JsonSlurper
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale

// Release helpers for official signed artifacts and public-repository validation builds.
// Inputs are provided by app/build.gradle.kts through Gradle extra properties.

data class SwitchlyReleaseArtifact(
    val taskName: String,
    val candidates: List<String>,
    val outputName: String,
)

fun normalizeSwitchlySha1(value: String): String = value
    .filter(Char::isLetterOrDigit)
    .uppercase(Locale.US)

fun formatSwitchlySha1(value: String): String = normalizeSwitchlySha1(value)
    .chunked(2)
    .joinToString(":")

fun switchlyReleaseCertificateSha1(
    storeFile: File,
    storePassword: String,
    keyAlias: String,
): String {
    val password = storePassword.toCharArray()
    val keyStore = listOf("PKCS12", "JKS", KeyStore.getDefaultType())
        .distinct()
        .firstNotNullOfOrNull { type ->
            runCatching {
                KeyStore.getInstance(type).apply {
                    storeFile.inputStream().use { input -> load(input, password) }
                }
            }.getOrNull()
        }
        ?: throw GradleException("Could not open release keystore: ${storeFile.absolutePath}")

    val certificate = keyStore.getCertificate(keyAlias)
        ?: throw GradleException("Release key alias not found in keystore: $keyAlias")
    return MessageDigest.getInstance("SHA-1")
        .digest(certificate.encoded)
        .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}

fun switchlyGoogleServicesAndroidSha1s(
    googleServicesJson: File,
    packageName: String,
): Set<String> {
    val root = runCatching { JsonSlurper().parse(googleServicesJson) as? Map<*, *> }
        .getOrNull()
        ?: return emptySet()
    val clients = root["client"] as? List<*> ?: return emptySet()

    return buildSet {
        clients.forEach { rawClient ->
            val client = rawClient as? Map<*, *> ?: return@forEach
            val clientInfo = client["client_info"] as? Map<*, *>
            val androidInfo = clientInfo?.get("android_client_info") as? Map<*, *>
            val clientPackage = androidInfo?.get("package_name")?.toString()
            if (clientPackage != packageName) return@forEach

            val oauthClients = client["oauth_client"] as? List<*> ?: return@forEach
            oauthClients.forEach { rawOauth ->
                val oauth = rawOauth as? Map<*, *> ?: return@forEach
                val clientType = when (val rawType = oauth["client_type"]) {
                    is Number -> rawType.toInt()
                    is String -> rawType.toIntOrNull()
                    else -> null
                }
                if (clientType != 1) return@forEach

                val oauthAndroidInfo = oauth["android_info"] as? Map<*, *>
                val oauthPackage = oauthAndroidInfo?.get("package_name")?.toString()
                if (oauthPackage != null && oauthPackage != packageName) return@forEach
                oauthAndroidInfo?.get("certificate_hash")
                    ?.toString()
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(normalizeSwitchlySha1(it)) }
            }
        }
    }
}

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
val releaseStorePassword = extra["switchlyReleaseStorePassword"] as org.gradle.api.provider.Provider<String>
val releaseKeyAlias = extra["switchlyReleaseKeyAlias"] as org.gradle.api.provider.Provider<String>
val externalCheckoutUrl = extra["switchlyExternalCheckoutUrl"] as org.gradle.api.provider.Provider<String>
val switchlyDownloadsUrl = extra["switchlyDownloadsUrl"] as org.gradle.api.provider.Provider<String>

val switchlyDistDir = rootProject.layout.projectDirectory.dir("dist")

// Official artifacts deliberately accept signed output names only. 
// This prevents an unsigned public-repository build from being copied under a final release filename.
val switchlyOfficialReleaseArtifacts = listOf(
    SwitchlyReleaseArtifact(
        taskName = "assembleFullRelease",
        candidates = listOf("outputs/apk/full/release/app-full-release.apk"),
        outputName = "Switchly-$switchlyVersionName-full.apk",
    ),
    SwitchlyReleaseArtifact(
        taskName = "assembleFirebaseEmailRelease",
        candidates = listOf("outputs/apk/firebaseEmail/release/app-firebaseEmail-release.apk"),
        outputName = "Switchly-$switchlyVersionName-firebase-email.apk",
    ),
    SwitchlyReleaseArtifact(
        taskName = "assembleOfflineRelease",
        candidates = listOf("outputs/apk/offline/release/app-offline-release.apk"),
        outputName = "Switchly-$switchlyVersionName-offline.apk",
    ),
    SwitchlyReleaseArtifact(
        taskName = "bundleFullRelease",
        candidates = listOf("outputs/bundle/fullRelease/app-full-release.aab"),
        outputName = "Switchly-$switchlyVersionName-full-playstore.aab",
    ),
)

tasks.register("checkSwitchlyOfficialReleaseInputs") {
    group = "switchly"
    description = "Checks private inputs required for official signed Switchly releases."

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

        if (!releaseSigningConfigured) {
            throw GradleException(
                "Official release signing is not configured. " +
                "The public repository intentionally contains no signing key. " +
                "Use public-release-apk for an explicitly unsigned offline validation artifact, " +
                "or provide SWITCHLY_RELEASE_STORE_FILE, " +
                "SWITCHLY_RELEASE_STORE_PASSWORD, SWITCHLY_RELEASE_KEY_ALIAS and " +
                "SWITCHLY_RELEASE_KEY_PASSWORD for an official release."
            )
        }

        val releaseKeystore = rootProject.file(releaseStoreFile.get())
        if (!releaseKeystore.isFile) {
            throw GradleException("Release keystore not found: ${releaseStoreFile.get()}")
        }

        val releaseSha1 = switchlyReleaseCertificateSha1(
            storeFile = releaseKeystore,
            storePassword = releaseStorePassword.get(),
            keyAlias = releaseKeyAlias.get(),
        )
        val configuredAndroidSha1s = switchlyGoogleServicesAndroidSha1s(
            googleServicesJson = googleServicesJson,
            packageName = "at.saltyy.switchly",
        )
        if (releaseSha1 !in configuredAndroidSha1s) {
            throw GradleException(
                "Google sign-in is not configured for the direct-download release APK. " +
                    "Add SHA-1 ${formatSwitchlySha1(releaseSha1)} for package at.saltyy.switchly " +
                    "in Firebase/Google Cloud, download the updated google-services.json, and rebuild."
            )
        }
        logger.lifecycle(
            "Verified Google sign-in Android OAuth client for release SHA-1 ${formatSwitchlySha1(releaseSha1)}"
        )

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

// Backwards-compatible check name for existing maintainer scripts.
tasks.register("checkSwitchlyReleaseInputs") {
    group = "switchly"
    description = "Alias for checkSwitchlyOfficialReleaseInputs."
    dependsOn("checkSwitchlyOfficialReleaseInputs")
}

tasks.register("release-apk") {
    group = "switchly"
    description = "Builds, lints and copies all official signed Switchly APK/AAB variants to dist/."

    dependsOn("checkSwitchlyOfficialReleaseInputs")
    dependsOn("lintFullRelease", "lintFirebaseEmailRelease", "lintOfflineRelease")
    dependsOn(switchlyOfficialReleaseArtifacts.map { it.taskName })

    doLast {
        val distDir = switchlyDistDir.asFile
        distDir.mkdirs()

        switchlyOfficialReleaseArtifacts.forEach { artifact ->
            copySwitchlyArtifact(
                candidates = artifact.candidates,
                outputName = artifact.outputName,
                distDir = distDir,
            )
        }

        logger.lifecycle("Done. Official signed release files are in: ${distDir.absolutePath}")
    }
}

// Public repositories can always validate and package the offline flavor without private
// Firebase or signing files. Unsigned output is named explicitly so it cannot be mistaken for an install-ready official release artifact.
tasks.register("public-release-apk") {
    group = "switchly"
    description = "Builds and lints the public offline release; unsigned output is labelled explicitly."

    dependsOn("lintOfflineRelease", "assembleOfflineRelease")

    doLast {
        val distDir = switchlyDistDir.asFile
        distDir.mkdirs()

        val source = if (releaseSigningConfigured) {
            listOf("outputs/apk/offline/release/app-offline-release.apk")
        } else {
            listOf("outputs/apk/offline/release/app-offline-release-unsigned.apk")
        }
        val outputName = if (releaseSigningConfigured) {
            "Switchly-$switchlyVersionName-offline.apk"
        } else {
            "Switchly-$switchlyVersionName-offline-unsigned.apk"
        }

        copySwitchlyArtifact(
            candidates = source,
            outputName = outputName,
            distDir = distDir,
        )
        logger.lifecycle("Done. Public release validation file is in: ${distDir.absolutePath}")
    }
}

tasks.register("releaseApk") {
    group = "switchly"
    description = "Alias for release-apk."
    dependsOn("release-apk")
}

tasks.register("publicReleaseApk") {
    group = "switchly"
    description = "Alias for public-release-apk."
    dependsOn("public-release-apk")
}
