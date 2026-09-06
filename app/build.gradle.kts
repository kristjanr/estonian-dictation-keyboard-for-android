plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ee.kristjanr.dictation"
    compileSdk = 35

    defaultConfig {
        applicationId = "ee.kristjanr.dictation"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // The prebuilt sherpa-onnx libraries are ~15 MB per ABI. Every phone
            // this project targets is 64-bit ARM, so we ship only that.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

// The sherpa-onnx runtime is not on Maven Central; scripts/fetch-sherpa-onnx.sh
// vendors it into the source tree. Fail early and legibly when it is missing,
// rather than with an unresolved-reference wall from the Kotlin compiler.
val verifySherpaOnnx by tasks.registering {
    val jni = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so").asFile
    val api = layout.projectDirectory.file("src/main/java/com/k2fsa/sherpa/onnx/OnlineRecognizer.kt").asFile
    doLast {
        if (!jni.exists() || !api.exists()) {
            throw GradleException(
                "sherpa-onnx is not vendored into the source tree.\n" +
                    "Run:  ./scripts/fetch-sherpa-onnx.sh\n" +
                    "(missing: " +
                    listOfNotNull(
                        jni.takeUnless { it.exists() }?.relativeTo(rootDir),
                        api.takeUnless { it.exists() }?.relativeTo(rootDir),
                    ).joinToString(", ") + ")"
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(verifySherpaOnnx) }
