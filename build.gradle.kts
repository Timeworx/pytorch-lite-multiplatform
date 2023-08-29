import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.ByteArrayOutputStream

plugins {
    kotlin("multiplatform") version "1.9.10"
    kotlin("native.cocoapods") version "1.9.10"
    id("com.android.library")
    id("com.adarshr.test-logger") version "3.1.0"
    `maven-publish`
}

group = "com.beeftechlabs"
version = "0.6.1"

val localProperties = com.android.build.gradle.internal.cxx.configure.gradleLocalProperties(rootDir)

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/BeeftechLabs/pytorch-lite-multiplatform")
            credentials {
                username = localProperties.getProperty("gpr.user") ?: System.getenv("USERNAME")
                password = localProperties.getProperty("gpr.key") ?: System.getenv("TOKEN")
            }
        }
    }
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    targetHierarchy.default()

    androidTarget {
        publishLibraryVariants("release")
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        ios.deploymentTarget = "13.5"

        homepage = "https://github.com/voize-gmbh/pytorch-lite-multiplatform"
        summary = "Kotlin Multiplatform wrapper for PyTorch Lite"

        framework {
            isStatic = true
        }

        pod("PLMLibTorchWrapper") {
            version = "0.6.0"
            headers = "LibTorchWrapper.h"
            source = path(project.file("ios/LibTorchWrapper"))
        }

        useLibraries()
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("com.squareup.okio:okio:3.3.0")
            }
        }
        val androidMain by getting {
            dependencies {
                rootProject
                implementation("org.pytorch:pytorch_android_lite:1.13.1")
            }
        }
    }
}

tasks.named("linkDebugTestIosX64").configure {
    doFirst {
        val target = (kotlin.targets.getByName("iosX64") as KotlinNativeTarget)

        target.binaries.all {
            val syntheticIOSProjectDir = project.file("build/cocoapods/synthetic/IOS")
            val libTorchPodDir = syntheticIOSProjectDir.resolve("Pods/LibTorch-Lite")
            val libTorchLibsDir = libTorchPodDir.resolve("install/lib")
            val podBuildDir = syntheticIOSProjectDir.resolve("build/Release-iphonesimulator")

            linkerOpts(
                "-L${libTorchLibsDir.absolutePath}",
                "-lc10", "-ltorch", "-ltorch_cpu", "-lXNNPACK",
                "-lclog", "-lcpuinfo", "-leigen_blas", "-lpthreadpool", "-lpytorch_qnnpack",
                "-force_load", libTorchLibsDir.resolve("libtorch.a").absolutePath,
                "-force_load", libTorchLibsDir.resolve("libtorch_cpu.a").absolutePath,
                "-all_load",
                "-L${podBuildDir.resolve("PLMLibTorchWrapper").absolutePath}",
                "-lPLMLibTorchWrapper",
                "-framework", "Accelerate",
            )
        }
    }
}

// inspired by: https://diamantidis.github.io/2019/08/25/kotlin-multiplatform-project-unit-tests-for-ios-and-android
task("iosSimulatorX64Test") {
    val target = (kotlin.targets.getByName("iosX64") as KotlinNativeTarget)

    dependsOn(target.binaries.getTest("DEBUG").linkTaskName)
    group = JavaBasePlugin.VERIFICATION_GROUP
    description = "Runs iOS tests on a simulator"

    doLast {
        println("Retrieving runtime for iOS simulator")
        val iOSRuntimesOutput = ByteArrayOutputStream()
        exec {
            commandLine("xcrun", "simctl", "list", "--json", "runtimes", "iOS")
            standardOutput = iOSRuntimesOutput
        }

        val iOSRuntimesData = groovy.json.JsonSlurper().parseText(iOSRuntimesOutput.toString()) as Map<String, List<Map<String, Any>>>
        val runtimesIdentifiers = iOSRuntimesData["runtimes"]!!.map { it["identifier"]!! } as List<String>
        val latestRuntimeIdentifier = runtimesIdentifiers.maxOrNull()!!
        println("Latest iOS runtime: $latestRuntimeIdentifier")

        println("Retrieving device for iOS simulator")
        val devicesOutput = ByteArrayOutputStream()
        exec {
            commandLine("xcrun", "simctl", "list", "--json", "devices")
            standardOutput = devicesOutput
        }
        val devicesData = groovy.json.JsonSlurper().parseText(devicesOutput.toString()) as Map<String, Map<String, List<Map<String, String>>>>
        val devices = devicesData["devices"]!!
        val deviceName = "iPhone 12"
        val device = devices[latestRuntimeIdentifier]!!.find { it["name"] == deviceName }
        val udid = device!!["udid"]
        println("Using device: $deviceName ($udid)")

        exec {
            println("Building test model")
            commandLine("python3", "build_dummy_model.py")
        }

        val simulatorFilesPath = "/Users/${System.getProperty("user.name")}/Library/Developer/CoreSimulator/Devices/$udid/data/Documents"

        exec {
            println("Setting up iOS simulator documents directory")
            commandLine("mkdir", "-p", simulatorFilesPath)
        }

        exec {
            println("Copying model to iOS simulator files ($simulatorFilesPath)")
            commandLine("cp", "dummy_module.ptl", simulatorFilesPath)
        }

        exec {
            println("Running simulator tests")
            val binary = target.binaries.getTest("DEBUG").outputFile
            commandLine("xcrun", "simctl", "spawn", "--standalone", udid, binary.absolutePath)
        }
    }
}

android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 24
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
