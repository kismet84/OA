plugins {
    id("com.google.gms.google-services")
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Values are supplied only by the release workstation environment.
val releaseSigningVariables = listOf(
    "OA_ANDROID_KEYSTORE_PATH", "OA_ANDROID_STORE_PASSWORD",
    "OA_ANDROID_KEY_PASSWORD", "OA_ANDROID_KEY_ALIAS",
)
val releaseSigningValues = releaseSigningVariables.associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }
val repositoryDirectory = rootProject.projectDir.parentFile.parentFile.canonicalFile
val releaseKeystore = releaseSigningValues["OA_ANDROID_KEYSTORE_PATH"]?.let { file(it).canonicalFile }
fun requireReleaseSigning() {
    val missing = releaseSigningVariables.filter { releaseSigningValues[it] == null }
    if (missing.isNotEmpty()) throw GradleException(
        "Release signing requires external signing credentials; set environment variables: ${missing.joinToString()}")
    if (releaseKeystore == null || !releaseKeystore.isFile ||
        releaseKeystore.toPath().startsWith(repositoryDirectory.toPath()))
        throw GradleException("OA_ANDROID_KEYSTORE_PATH must reference an existing keystore outside the repository")
}
// Validate the resolved task graph, including dry runs and abbreviated release task names.
// Debug and ordinary IDE configuration do not need release credentials.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project &&
        it.name.contains("release", ignoreCase = true) }) {
        requireReleaseSigning()
    }
}
tasks.register("verifyReleaseSigning") {
    description = "Validate external release signing configuration without producing an installable package"
    doLast { requireReleaseSigning() }
}

android {
    namespace = "com.company.oa_employee"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.company.oa_employee"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = releaseSigningValues["OA_ANDROID_STORE_PASSWORD"]
            keyPassword = releaseSigningValues["OA_ANDROID_KEY_PASSWORD"]
            keyAlias = releaseSigningValues["OA_ANDROID_KEY_ALIAS"]
        }
    }

    buildTypes {
        debug { manifestPlaceholders["oaCleartext"] = "true" }
        release {
            manifestPlaceholders["oaCleartext"] = "false"
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
