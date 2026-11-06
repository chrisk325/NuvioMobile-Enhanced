plugins {
    alias(libs.plugins.androidTest)
    alias(libs.plugins.androidx.baselineprofile)
}

// Generates the app's baseline profile (ahead-of-time compiled code for startup and the main
// scrolling surfaces). Run with a device or emulator (API 28+) attached:
//   ./gradlew :androidApp:generateFullBaselineProfile
// The installed app should be set up (profile chosen, addons installed) so the home screen has
// rows to scroll; otherwise only the first-run screens are covered.
android {
    namespace = "com.nuvio.baselineprofile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = 28
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Must mirror :androidApp's flavors so each app variant has a matching generator variant.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":androidApp"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.testExt.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
}

// Hands the tested app's real applicationId to the generator as the `targetAppId` argument.
androidComponents {
    onVariants { variant ->
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            variant.testedApks.map { artifactsLoader.load(it)?.applicationId.orEmpty() },
        )
    }
}
