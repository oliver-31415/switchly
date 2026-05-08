import groovy.json.JsonSlurper
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services") apply false
    id("com.google.firebase.crashlytics") apply false
}

val switchlyVersionCode = 211
val switchlyVersionName = "2.1.1"

val switchlySecretPropertiesFile = rootProject.file("signing.properties")
val switchlySecretProperties = Properties().apply {
    if (switchlySecretPropertiesFile.isFile) {
        switchlySecretPropertiesFile.inputStream().use { input -> load(input) }
    }
}

fun switchlySecretProperty(name: String) = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orElse(providers.provider { switchlySecretProperties.getProperty(name) ?: "" })

val googleServicesJson = layout.projectDirectory.file("google-services.json").asFile
val googleServicesJsonPath = switchlySecretProperty("GOOGLE_SERVICES_JSON_PATH").get()

if (!googleServicesJson.exists() && googleServicesJsonPath.isNotBlank()) {
    val source = rootProject.file(googleServicesJsonPath)
    if (!source.isFile) {
        throw GradleException("GOOGLE_SERVICES_JSON_PATH does not exist: $googleServicesJsonPath")
    }
    googleServicesJson.parentFile.mkdirs()
    source.copyTo(googleServicesJson, overwrite = true)
}

val googleServicesJsonExists = googleServicesJson.isFile

fun googleWebClientIdFromGoogleServicesJson(file: File): String {
    if (!file.isFile) return ""

    val root = runCatching { JsonSlurper().parse(file) as? Map<*, *> }
        .getOrNull()
        ?: return ""

    val clients = root["client"] as? List<*> ?: return ""
    for (client in clients) {
        val clientMap = client as? Map<*, *> ?: continue
        val oauthClients = clientMap["oauth_client"] as? List<*> ?: continue

        for (oauthClient in oauthClients) {
            val oauthMap = oauthClient as? Map<*, *> ?: continue
            val clientType = when (val rawType = oauthMap["client_type"]) {
                is Number -> rawType.toInt()
                is String -> rawType.toIntOrNull()
                else -> null
            }

            if (clientType == 3) {
                return oauthMap["client_id"]
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            }
        }
    }

    return ""
}

val googleWebClientId = switchlySecretProperty("GOOGLE_WEB_CLIENT_ID")
    .map { value -> value.ifBlank { googleWebClientIdFromGoogleServicesJson(googleServicesJson) } }

// The Google Services and Crashlytics Gradle plugins need google-services.json.
// They are applied only when the Firebase config is available, so public/offline
// builds can still compile without private Firebase files.
if (googleServicesJsonExists) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val mapsApiKey = switchlySecretProperty("MAPS_API_KEY")
val externalCheckoutUrl = switchlySecretProperty("SWITCHLY_EXTERNAL_CHECKOUT_URL")
val externalCustomerPortalUrl = switchlySecretProperty("SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL")
val externalPaymentProvider = switchlySecretProperty("SWITCHLY_EXTERNAL_PAYMENT_PROVIDER")
    .map { value -> value.ifBlank { "external" } }

// Public links/contact values for official builds. Keep these configurable so forks can
// build Switchly without official project URLs compiled into the APK.
val switchlyWebsiteUrl = switchlySecretProperty("SWITCHLY_WEBSITE_URL")
val switchlyDownloadsUrl = switchlySecretProperty("SWITCHLY_DOWNLOADS_URL")
val switchlySupportEmail = switchlySecretProperty("SWITCHLY_SUPPORT_EMAIL")
val switchlyDevEmail = switchlySecretProperty("SWITCHLY_DEV_EMAIL")

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val releaseStoreFile = switchlySecretProperty("SWITCHLY_RELEASE_STORE_FILE")
val releaseStorePassword = switchlySecretProperty("SWITCHLY_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = switchlySecretProperty("SWITCHLY_RELEASE_KEY_ALIAS")
val releaseKeyPassword = switchlySecretProperty("SWITCHLY_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.get().isNotBlank() }

android {
    namespace = "at.saltyy.switchly"
    compileSdk = 36

    defaultConfig {
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey.get()
        resValue("string", "about_website_url", switchlyWebsiteUrl.get())
        resValue("string", "about_downloads_url", switchlyDownloadsUrl.get())
        resValue("string", "about_mail_address", switchlySupportEmail.get())
        resValue("string", "dev_contact_email", switchlyDevEmail.get())
        buildConfigField("String", "SWITCHLY_GOOGLE_WEB_CLIENT_ID", buildConfigString(googleWebClientId.get()))
        applicationId = "at.saltyy.switchly"
        minSdk = 27
        targetSdk = 36

        versionCode = switchlyVersionCode
        versionName = switchlyVersionName
    }

    flavorDimensions += "services"

    productFlavors {
        create("full") {
            dimension = "services"

            buildConfigField("Boolean", "SWITCHLY_FIREBASE_ENABLED", "true")
            buildConfigField("Boolean", "SWITCHLY_GOOGLE_SIGN_IN_ENABLED", "true")
            buildConfigField("Boolean", "SWITCHLY_PLAY_BILLING_ENABLED", "true")
            buildConfigField("Boolean", "SWITCHLY_EXTERNAL_PAYMENTS_ENABLED", "false")
            buildConfigField("String", "SWITCHLY_EXTERNAL_PAYMENT_PROVIDER", buildConfigString("google-play"))
            buildConfigField("String", "SWITCHLY_EXTERNAL_CHECKOUT_URL", buildConfigString(""))
            buildConfigField("String", "SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL", buildConfigString(""))
            buildConfigField("String", "SWITCHLY_APK_VARIANT", buildConfigString("full"))
        }

        create("firebaseEmail") {
            dimension = "services"

            buildConfigField("Boolean", "SWITCHLY_FIREBASE_ENABLED", "true")
            buildConfigField("Boolean", "SWITCHLY_GOOGLE_SIGN_IN_ENABLED", "false")
            buildConfigField("Boolean", "SWITCHLY_PLAY_BILLING_ENABLED", "false")
            buildConfigField("Boolean", "SWITCHLY_EXTERNAL_PAYMENTS_ENABLED", "true")
            buildConfigField("String", "SWITCHLY_EXTERNAL_PAYMENT_PROVIDER", buildConfigString(externalPaymentProvider.get()))
            buildConfigField("String", "SWITCHLY_EXTERNAL_CHECKOUT_URL", buildConfigString(externalCheckoutUrl.get()))
            buildConfigField("String", "SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL", buildConfigString(externalCustomerPortalUrl.get()))
            buildConfigField("String", "SWITCHLY_APK_VARIANT", buildConfigString("firebase-email"))
        }

        create("offline") {
            dimension = "services"

            buildConfigField("Boolean", "SWITCHLY_FIREBASE_ENABLED", "false")
            buildConfigField("Boolean", "SWITCHLY_GOOGLE_SIGN_IN_ENABLED", "false")
            buildConfigField("Boolean", "SWITCHLY_PLAY_BILLING_ENABLED", "false")
            // Offline builds intentionally do not sell/restore Premium via Stripe/Adyen.
            // There is no account identity to safely restore purchases after reinstall.
            buildConfigField("Boolean", "SWITCHLY_EXTERNAL_PAYMENTS_ENABLED", "false")
            buildConfigField("String", "SWITCHLY_EXTERNAL_PAYMENT_PROVIDER", buildConfigString("none"))
            buildConfigField("String", "SWITCHLY_EXTERNAL_CHECKOUT_URL", buildConfigString(""))
            buildConfigField("String", "SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL", buildConfigString(""))
            buildConfigField("String", "SWITCHLY_APK_VARIANT", buildConfigString("offline"))
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // AndroidX core, UI, and preferences
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")

    // Coroutines helpers (lifecycleScope) + DataStore (for small user prefs)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Local database (Room)
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Firebase BOM + Modules.
    // These remain available to all flavors because shared source files reference Firebase types.
    // Runtime behavior is controlled by per-flavor BuildConfig flags; offline does not initialize Firebase.
    implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
    // Auth for Google-Login
    implementation("com.google.firebase:firebase-auth")
    // Cloud Firestore (for Sync & Stats)
    implementation("com.google.firebase:firebase-firestore")
    // Crash reporting
    implementation("com.google.firebase:firebase-crashlytics")

    // Google Sign-In/Credential Manager
    implementation("com.google.android.gms:play-services-auth:21.5.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-maps:20.0.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    // Play Billing
    implementation("com.android.billingclient:billing-ktx:8.3.0")

    // Play Store in-app update (check for updates)
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // CameraX
    val camerax = "1.6.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Provides com.google.common.util.concurrent.ListenableFuture for CameraX
    implementation("com.google.guava:guava:33.5.0-android")

    // ML Kit barcode scanning (Play Services variant)
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // QR generate (ZXing core)
    implementation("com.google.zxing:core:3.5.4")
}

// fix for duplicated classes: com.intellij:annotations vs org.jetbrains:annotations
configurations.configureEach {
    exclude(group = "com.intellij", module = "annotations")
}

val switchlyDistDir = rootProject.layout.projectDirectory.dir("dist")

tasks.register("checkSwitchlyReleaseInputs") {
    group = "switchly"
    description = "Checks inputs required for Switchly Firebase release artifacts."

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

    dependsOn(
        "checkSwitchlyReleaseInputs",
        "assembleFullRelease",
        "assembleFirebaseEmailRelease",
        "assembleOfflineRelease",
        "bundleFullRelease",
    )

    doLast {
        val distDir = switchlyDistDir.asFile
        distDir.mkdirs()

        fun copyArtifact(candidates: List<String>, outputName: String) {
            val source = candidates
                .map { layout.buildDirectory.file(it).get().asFile }
                .firstOrNull { it.isFile }
                ?: throw GradleException(
                    "Could not find build artifact for $outputName. Checked: ${candidates.joinToString()}"
                )

            source.copyTo(distDir.resolve(outputName), overwrite = true)
            logger.lifecycle("Created: ${distDir.resolve(outputName).absolutePath}")
        }

        copyArtifact(
            listOf(
                "outputs/apk/full/release/app-full-release.apk",
                "outputs/apk/full/release/app-full-release-unsigned.apk",
            ),
            "Switchly-$switchlyVersionName-full.apk",
        )

        copyArtifact(
            listOf(
                "outputs/apk/firebaseEmail/release/app-firebaseEmail-release.apk",
                "outputs/apk/firebaseEmail/release/app-firebaseEmail-release-unsigned.apk",
            ),
            "Switchly-$switchlyVersionName-firebase-email.apk",
        )

        copyArtifact(
            listOf(
                "outputs/apk/offline/release/app-offline-release.apk",
                "outputs/apk/offline/release/app-offline-release-unsigned.apk",
            ),
            "Switchly-$switchlyVersionName-offline.apk",
        )

        copyArtifact(
            listOf("outputs/bundle/fullRelease/app-full-release.aab"),
            "Switchly-$switchlyVersionName-full-playstore.aab",
        )

        logger.lifecycle("Done. Release files are in: ${distDir.absolutePath}")
    }
}

tasks.register("releaseApk") {
    group = "switchly"
    description = "Alias for release-apk."
    dependsOn("release-apk")
}
