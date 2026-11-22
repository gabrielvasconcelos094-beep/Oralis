import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val ndkVersionUsed = "28.2.13676358"
val cmakeVersionUsed = "3.31.6"

android {
    namespace = "app.oralis"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = ndkVersionUsed

    defaultConfig {
        applicationId = "app.oralis"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // MediaPipe TTS ships arm64-v8a (phones) and x86_64 (emulators/Chromebooks) natives
            // that are relevant here; 32-bit devices cannot hold the ~0.9 GB peak of the model.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    buildTypes {
        release {
            // Signed with the debug key so the APK is installable for personal use; replace with a
            // private release keystore before any distribution.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = cmakeVersionUsed
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Keep .so files uncompressed and page-aligned (faster load, 16 KB page support).
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // tasks-audio is compiled with a newer Kotlin metadata version (same flag as Google's
        // official MediaPipe TTS sample).
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.mediapipe.tasks.audio)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ---------------------------------------------------------------------------------------------
// Host build of the LAME JNI bridge so the MP3 encoder can be exercised by JVM unit tests with
// the exact same C sources that ship in the APK.
// ---------------------------------------------------------------------------------------------
val sdkDir: String? = run {
    val f = rootProject.file("local.properties")
    if (f.exists()) Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
    else System.getenv("ANDROID_HOME")
}
val hostNativeDir = layout.buildDirectory.dir("host-native")

val buildHostNative by tasks.registering(Exec::class) {
    val cmake = sdkDir?.let { File(it, "cmake/$cmakeVersionUsed/bin/cmake") }
    onlyIf { cmake != null && cmake.exists() && !System.getProperty("os.name").startsWith("Windows") }
    val out = hostNativeDir.get().asFile
    inputs.dir("src/main/cpp")
    outputs.dir(out)
    doFirst { out.mkdirs() }
    workingDir = out
    commandLine(
        "sh", "-c",
        "'${cmake?.absolutePath}' -S '${file("src/main/cpp").absolutePath}' -B . " +
            "-DCMAKE_BUILD_TYPE=Release > cmake.log && '${cmake?.absolutePath}' --build . -j 4 > build.log",
    )
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostNative)
    maxHeapSize = "3g"
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("oralis.screenshotDir", rootProject.layout.projectDirectory.dir("docs/screenshots").asFile.absolutePath)
    systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
    systemProperty("oralis.testOutputDir", layout.buildDirectory.dir("test-output").get().asFile.absolutePath)
    // Optional: path to a real Kokoro .litertlm for host-side validation scripts.
    System.getenv("ORALIS_MODEL_PATH")?.let { systemProperty("oralis.modelPath", it) }
}
