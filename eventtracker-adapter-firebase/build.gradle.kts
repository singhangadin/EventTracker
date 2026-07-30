plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    jacoco
}

jacoco {
    toolVersion = "0.8.12"
}

// Robolectric rewrites debug info in its sandbox classloader; without includeNoLocationClasses,
// JaCoCo reports every Robolectric-executed class as 0% (the FirebaseAdapter is entirely
// Robolectric-driven, so its whole coverage was being dropped).
tasks.withType<Test>().configureEach {
    configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

android {
    namespace = "in.singhangad.eventtracker.adapter.firebase"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
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

    // Testing — JVM unit tests (Robolectric; FirebaseAnalytics is mocked)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

// ---- Code coverage (JaCoCo) -----------------------------------------------------------------

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generates a JaCoCo coverage report from the debug unit tests."
    dependsOn("testDebugUnitTest")

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(true)
    }

    val exclusions = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*\$DefaultImpls.*",
    )
    val buildDirFile = layout.buildDirectory.get().asFile
    classDirectories.setFrom(
        fileTree(mapOf("dir" to "$buildDirFile/tmp/kotlin-classes/debug", "excludes" to exclusions))
    )
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(
            mapOf(
                "dir" to buildDirFile,
                "includes" to listOf(
                    "jacoco/testDebugUnitTest.exec",
                    "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                ),
            )
        )
    )
}

tasks.register("printCoverage") {
    group = "verification"
    description = "Prints the overall coverage percentages from the JaCoCo XML report."
    dependsOn("jacocoTestReport")
    doLast {
        val xml = layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml").get().asFile
        if (!xml.exists()) {
            println("Coverage report not found at ${xml.path}")
            return@doLast
        }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isValidating = false
        }
        val doc = factory.newDocumentBuilder().parse(xml)
        val counters = doc.getElementsByTagName("counter")
        fun pct(type: String): String {
            for (i in 0 until counters.length) {
                val n = counters.item(i)
                val attrs = n.attributes
                if (attrs.getNamedItem("type")?.nodeValue == type &&
                    n.parentNode?.nodeName == "report"
                ) {
                    val missed = attrs.getNamedItem("missed").nodeValue.toDouble()
                    val covered = attrs.getNamedItem("covered").nodeValue.toDouble()
                    val total = missed + covered
                    val p = if (total == 0.0) 100.0 else covered / total * 100.0
                    return "%.2f%% (%d/%d)".format(p, covered.toInt(), total.toInt())
                }
            }
            return "n/a"
        }
        println("=".repeat(60))
        println("[firebase] JaCoCo coverage — INSTRUCTION: ${pct("INSTRUCTION")}")
        println("[firebase] JaCoCo coverage — LINE:        ${pct("LINE")}")
        println("[firebase] JaCoCo coverage — BRANCH:      ${pct("BRANCH")}")
        println("[firebase] JaCoCo coverage — METHOD:      ${pct("METHOD")}")
        println("=".repeat(60))
    }
}
