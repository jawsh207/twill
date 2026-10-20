import dev.detekt.gradle.Detekt
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("messaging.licenses")
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
    alias(libs.plugins.hilt)
    jacoco
    id("messaging.jacoco")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

detekt {
    basePath.set(rootDir)
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    ignoredBuildTypes = listOf("release")
    parallel = true
    source.setFrom(files("../src"))
}

tasks.withType<Detekt>().configureEach {
    setSource(files("../src"))
    include("**/*.kt")
    include("**/*.kts")
    exclude("**/build/**")
    exclude("**/com/android/messaging/datamodel/**")
    exclude("**/com/android/messaging/debug/**")
}

android {
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    namespace = "com.android.messaging"

    defaultConfig {
        // Twill's own package ID, so every build type installs alongside the system
        // Messaging app (com.android.messaging) instead of replacing it.
        // Releases are renamed to app.twill.messages below; test builds keep app.twill.perf
        // and app.twill.debug.
        applicationId = "app.twill"
        // versionCode only has to keep increasing (test installs started at 20000016);
        // versionName is Twill's own version, shown to users and used as the release tag.
        versionCode = 20000000 + 23
        versionName = "1.0.6"
        minSdk = 36
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Twill: 64-bit ARM only (every phone GrapheneOS supports), which keeps the APK
        // smaller. Add "x86_64" here temporarily to run in an x86 emulator.
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets.getByName("main") {
        assets.srcDir("../assets")
        manifest.srcFile("../AndroidManifest.xml")
        java.srcDirs("../src")
        kotlin.srcDirs("../src")
        res.srcDir("../res")
        // Twill: the Delta Chat core and its JNI bridge, built by
        // scripts/build-deltachat-core.sh (not in git). Loaded straight from the APK.
        jniLibs.srcDir("../deltachat/jniLibs")
    }

    sourceSets.getByName("test") {
        kotlin.srcDir("src/sharedTest/kotlin")
    }

    sourceSets.getByName("androidTest") {
        kotlin.srcDir("src/sharedTest/kotlin")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val useKeystoreProperties = keystorePropertiesFile.canRead()
    val keystoreProperties = Properties()
    if (useKeystoreProperties) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    if (useKeystoreProperties) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../proguard.flags",
                "../proguard-release.flags",
            )

            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Twill d")
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }

        // Release-optimized test build (R8, not debuggable) signed with the test key. This is
        // the build to actually use day to day; debug builds are slow by design.
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".perf"
            matchingFallbacks += listOf("release")
            resValue("string", "app_name", "Twill")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { unitTest ->
                unitTest.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
                unitTest.extensions.configure(JacocoTaskExtension::class.java) {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }

    lint {
        abortOnError = false
    }
}

// Twill: release builds are app.twill.messages (installs alongside the system Messaging app).
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set("app.twill.messages")
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.androidx.photo.picker)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.glide)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.guava)

    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.libphonenumber)

    // Twill: Nostr
    implementation(libs.quartz)
    implementation(libs.okhttp)

    implementation(project(":lib:platform_frameworks_opt_chips"))
    implementation(project(":lib:platform_frameworks_opt_vcard"))

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.mockk.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)

    androidTestImplementation(libs.mockk)
    androidTestImplementation(libs.mockk.agent)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.turbine)
}
