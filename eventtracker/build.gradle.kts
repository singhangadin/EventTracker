import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.dokka")
    id("maven-publish")
}

android {
    namespace = "in.singhangad.eventtracker"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.properties["GROUP"].toString()
                artifactId = "eventtracker"
                version = System.getenv("JITPACK_VERSION")
                    ?: project.properties["VERSION_NAME"].toString()

                pom {
                    name.set("EventTracker")
                    description.set("A pluggable, batched, retryable event-tracking SDK for Android")
                    url.set("https://github.com/singhangad/eventtracker")
                }
            }
        }
        repositories {
            mavenLocal()
        }
    }
}

// Apply source-set config to every Dokka task (dokkaHtml, dokkaGfm, etc.)
tasks.withType<DokkaTask>().configureEach {
    dokkaSourceSets {
        named("main") {
            moduleName.set("eventtracker")
            includes.from("module.md")
            perPackageOption {
                matchingRegex.set("in\\.singhangad\\.eventtracker\\.internal.*")
                suppress.set(true)
            }
        }
    }
}

tasks.named<DokkaTask>("dokkaHtml") {
    outputDirectory.set(layout.buildDirectory.dir("docs/html"))
}

tasks.named<DokkaTask>("dokkaGfm") {
    outputDirectory.set(layout.buildDirectory.dir("docs/markdown"))
}

tasks.register("publishDocs") {
    dependsOn("dokkaHtml", "dokkaGfm")
}

dependencies {
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // AndroidX core (1.13.x is the last release compatible with AGP 8.5.0 / compileSdk 34)
    implementation("androidx.core:core-ktx:1.13.1")

    // Lifecycle (ProcessLifecycleOwner for foreground/background detection)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.7")

    // WorkManager (2.9.x is the last release compatible with AGP 8.5.0 / compileSdk 34)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Room (local persistence)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // OkHttp (backend HTTP client)
    implementation("com.squareup.okhttp3:okhttp:5.3.2")


    // Testing — JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20231013") // standalone org.json for MapToJsonTest

    // Testing — instrumented
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
