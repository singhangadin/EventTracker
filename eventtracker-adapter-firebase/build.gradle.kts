plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "in.singhangad.eventtracker.adapter.firebase"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.properties["GROUP"].toString()
                artifactId = "eventtracker-adapter-firebase"
                version = System.getenv("JITPACK_VERSION")
                    ?: project.properties["VERSION_NAME"].toString()

                pom {
                    name.set("EventTracker Firebase Adapter")
                    description.set("Firebase Analytics adapter for the EventTracker Android SDK")
                    url.set("https://github.com/singhangad/eventtracker")
                }
            }
        }
        repositories {
            mavenLocal()
        }
    }
}

dependencies {
    // Core EventTracker library
    api(project(":eventtracker"))

    // Firebase Analytics
    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-analytics")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
