import groovy.json.JsonSlurper
import java.io.File
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services") apply false
    id("com.google.firebase.crashlytics") apply false
}

val switchlyVersionCode = 222
val switchlyVersionName = "2.2.2"

val switchlySecretPropertiesFile = rootProject.file("signing.properties")
val switchlySecretProperties = Properties().apply {
    if (switchlySecretPropertiesFile.isFile) {
        switchlySecretPropertiesFile.inputStream().use { input -> load(input) }
    }
}

fun switchlySecretProperty(name: String) = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orElse(providers.provider { switchlySecretProperties.getProperty(name) ?: "" })

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun String.switchlyTrimUnquoted(): String {
    val trimmed = trim()
    if (trimmed.length >= 2) {
        val first = trimmed.first()
        val last = trimmed.last()
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return trimmed.substring(1, trimmed.length - 1).trim()
        }
    }
    return trimmed
}

fun Provider<String>.switchlyTrimmedUnquoted(): Provider<String> = map { it.switchlyTrimUnquoted() }

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
val googleWebClientId = switchlySecretProperty("GOOGLE_WEB_CLIENT_ID")
    .map { value -> value.ifBlank { googleWebClientIdFromGoogleServicesJson(googleServicesJson) } }

// The Google Services and Crashlytics Gradle plugins need google-services.json.
// They are applied only when the Firebase config is available, so public/offline builds can still compile without private Firebase files.
if (googleServicesJsonExists) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val mapsApiKey = switchlySecretProperty("MAPS_API_KEY").switchlyTrimmedUnquoted()
val externalCheckoutUrl = switchlySecretProperty("SWITCHLY_EXTERNAL_CHECKOUT_URL")
val externalCustomerPortalUrl = switchlySecretProperty("SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL")
val switchlyRedeemApiUrl = switchlySecretProperty("SWITCHLY_REDEEM_API_URL")
    .map { value -> value.ifBlank { "https://switchly.saltyy.at/pages/pay/redeem-code/" } }
val offlineRedeemCodePattern = Regex("^SALT-OFFLINE-[A-Z0-9]{4}-[A-Z0-9]{4}$")
val offlineRedeemCodeAllowlist = switchlySecretProperty("SWITCHLY_OFFLINE_REDEEM_CODE_ALLOWLIST")
    .switchlyTrimmedUnquoted()
    .map { raw ->
        val codes = raw.split(',', ';', '\n')
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotBlank() }
            .distinct()

        val invalidCodes = codes.filterNot(offlineRedeemCodePattern::matches)
        require(invalidCodes.isEmpty()) {
            "SWITCHLY_OFFLINE_REDEEM_CODE_ALLOWLIST contains invalid codes: ${invalidCodes.joinToString(", ")}. " +
                "Expected SALT-OFFLINE-XXXX-XXXX."
        }

        codes.joinToString(",")
    }
// Offline redeem is enabled only when a private allowlist is supplied at build time.
val offlineRedeemEnabled = offlineRedeemCodeAllowlist.map { it.isNotBlank().toString() }
val externalPaymentProvider = switchlySecretProperty("SWITCHLY_EXTERNAL_PAYMENT_PROVIDER")
    .map { value -> value.ifBlank { "external" } }

// Public links/contact values for official builds.
// Keep these configurable so forks can build Switchly without official project URLs compiled into the APK.
val switchlyWebsiteUrl = switchlySecretProperty("SWITCHLY_WEBSITE_URL")
val switchlyDownloadsUrl = switchlySecretProperty("SWITCHLY_DOWNLOADS_URL")
val switchlySupportEmail = switchlySecretProperty("SWITCHLY_DEV_EMAIL")

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
        applicationId = "at.saltyy.switchly"
        minSdk = 27
        targetSdk = 36

        versionCode = switchlyVersionCode
        versionName = switchlyVersionName

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey.get()

        resValue("string", "about_website_url", switchlyWebsiteUrl.get())
        resValue("string", "about_downloads_url", switchlyDownloadsUrl.get())
        resValue("string", "about_mail_address", switchlySupportEmail.get())

        buildConfigField("String", "SWITCHLY_GOOGLE_WEB_CLIENT_ID", buildConfigString(googleWebClientId.get()))
        buildConfigField("boolean", "SWITCHLY_HAS_MAPS_API_KEY", mapsApiKey.get().isNotBlank().toString())
    }

    flavorDimensions += "services"

    productFlavors {
        create("full") {
            dimension = "services"

            buildConfigField("Boolean", "SWITCHLY_FIREBASE_ENABLED", "true")
            buildConfigField("Boolean", "SWITCHLY_GOOGLE_SIGN_IN_ENABLED", "true")
            buildConfigField("Boolean", "SWITCHLY_PLAY_BILLING_ENABLED", "true")
            buildConfigField("Boolean", "SWITCHLY_EXTERNAL_PAYMENTS_ENABLED", "false")
            buildConfigField("Boolean", "SWITCHLY_REDEEM_CODES_ENABLED", "false")
            buildConfigField("Boolean", "SWITCHLY_ONLINE_REDEEM_CODES_ENABLED", "false")
            buildConfigField("Boolean", "SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED", "false")
            buildConfigField("String", "SWITCHLY_REDEEM_API_URL", buildConfigString(""))
            buildConfigField("String", "SWITCHLY_OFFLINE_REDEEM_CODE_ALLOWLIST", buildConfigString(""))
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
            buildConfigField("Boolean", "SWITCHLY_REDEEM_CODES_ENABLED", "true")
            buildConfigField("Boolean", "SWITCHLY_ONLINE_REDEEM_CODES_ENABLED", "true")
            buildConfigField("Boolean", "SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED", "false")
            buildConfigField("String", "SWITCHLY_REDEEM_API_URL", buildConfigString(switchlyRedeemApiUrl.get()))
            buildConfigField("String", "SWITCHLY_OFFLINE_REDEEM_CODE_ALLOWLIST", buildConfigString(""))
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
            buildConfigField("Boolean", "SWITCHLY_EXTERNAL_PAYMENTS_ENABLED", "false")
            buildConfigField("Boolean", "SWITCHLY_REDEEM_CODES_ENABLED", offlineRedeemEnabled.get())
            buildConfigField("Boolean", "SWITCHLY_ONLINE_REDEEM_CODES_ENABLED", "false")
            buildConfigField("Boolean", "SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED", offlineRedeemEnabled.get())
            buildConfigField("String", "SWITCHLY_REDEEM_API_URL", buildConfigString(""))
            buildConfigField("String", "SWITCHLY_OFFLINE_REDEEM_CODE_ALLOWLIST", buildConfigString(offlineRedeemCodeAllowlist.get()))
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
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro",
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

// Values needed by gradle/switchly-release.gradle.kts.
extra["switchlyVersionName"] = switchlyVersionName
extra["switchlyGoogleServicesJsonFile"] = googleServicesJson
extra["switchlyGoogleWebClientId"] = googleWebClientId
extra["switchlyReleaseSigningConfigured"] = releaseSigningConfigured
extra["switchlyReleaseStoreFile"] = releaseStoreFile
extra["switchlyExternalCheckoutUrl"] = externalCheckoutUrl
extra["switchlyDownloadsUrl"] = switchlyDownloadsUrl

// Shared dependency declarations for the Android app module.
dependencies {
    // AndroidX core, UI, and preferences
    add("implementation", "androidx.preference:preference-ktx:1.2.1")
    add("implementation", "androidx.core:core-ktx:1.18.0")
    add("implementation", "androidx.appcompat:appcompat:1.7.1")
    add("implementation", "com.google.android.material:material:1.13.0")

    // Local statistics archive (Room). SharedPreferences remain the compatibility cache while
    // Room stores the durable, structured copy used for long-range history and backup/restore.
    val roomVersion = "2.8.4"
    add("implementation", "androidx.room:room-runtime:$roomVersion")
    add("ksp", "androidx.room:room-compiler:$roomVersion")

    // Coroutines helpers (lifecycleScope) + DataStore (for small user prefs)
    add("implementation", "androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    add("implementation", "androidx.datastore:datastore-preferences:1.2.1")

    // Firebase BOM + modules.
    // These remain available to all flavors because shared source files reference Firebase types.
    // Runtime behavior is controlled by per-flavor BuildConfig flags; offline does not initialize Firebase.
    add("implementation", platform("com.google.firebase:firebase-bom:34.13.0"))
    add("implementation", "com.google.firebase:firebase-auth")
    add("implementation", "com.google.firebase:firebase-firestore")
    add("implementation", "com.google.firebase:firebase-crashlytics")

    // Google Sign-In, location, maps, and Credential Manager
    add("implementation", "com.google.android.gms:play-services-auth:21.5.1")
    add("implementation", "com.google.android.gms:play-services-location:21.3.0")
    add("implementation", "com.google.android.gms:play-services-maps:20.0.0")
    add("implementation", "androidx.credentials:credentials:1.6.0")
    add("implementation", "androidx.credentials:credentials-play-services-auth:1.6.0")
    add("implementation", "com.google.android.libraries.identity.googleid:googleid:1.2.0")

    // Play Billing + Play Store in-app updates
    add("implementation", "com.android.billingclient:billing-ktx:8.3.0")
    add("implementation", "com.google.android.play:app-update-ktx:2.1.0")
    add("implementation", "com.google.android.play:integrity:1.6.0")

    // CameraX
    val camerax = "1.6.1"
    add("implementation", "androidx.camera:camera-core:$camerax")
    add("implementation", "androidx.camera:camera-camera2:$camerax")
    add("implementation", "androidx.camera:camera-lifecycle:$camerax")
    add("implementation", "androidx.camera:camera-view:$camerax")

    // Provides com.google.common.util.concurrent.ListenableFuture for CameraX
    add("implementation", "com.google.guava:guava:33.5.0-android")

    // Barcode scanning and QR generation
    add("implementation", "com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    add("implementation", "com.google.zxing:core:3.5.4")
}

// Fix for duplicated classes: com.intellij:annotations vs org.jetbrains:annotations.
configurations.configureEach {
    exclude(group = "com.intellij", module = "annotations")
}

apply(from = rootProject.file("gradle/switchly-release.gradle.kts"))
