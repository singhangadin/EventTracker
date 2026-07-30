import org.jetbrains.dokka.gradle.DokkaTask
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.dokka")
    id("maven-publish")
    jacoco
}

jacoco {
    toolVersion = "0.8.12"
}

// Robolectric loads app classes in its own sandbox classloader and rewrites their debug info;
// without includeNoLocationClasses, JaCoCo discards coverage for every Robolectric-executed
// class (reporting them as 0%). This flag keeps that coverage.
tasks.withType<Test>().configureEach {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
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
        getByName("debug") {
            // Enables JaCoCo instrumentation of the debug variant so local JVM
            // (Robolectric) unit tests emit coverage execution data.
            enableUnitTestCoverage = true
        }
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
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
        // Room's exported schemas double as Robolectric test assets so that
        // MigrationTestHelper-style checks can also run as local JVM tests.
        getByName("test") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs real Android resources; default-return keeps
            // un-shadowed framework calls from throwing "not mocked".
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
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

// ---- Code coverage (JaCoCo) -----------------------------------------------------------------

// Generated / framework classes that carry no hand-written logic worth covering.
val coverageExclusions = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    // Room code-gen (DAO/Database _Impl) is generated, not authored here.
    "**/*_Impl*.*", "**/*Database_Impl*.*",
    // Kotlin metadata / synthetic helpers.
    "**/*\$DefaultImpls.*", "**/*\$WhenMappings.*",
)

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generates a JaCoCo coverage report from the debug unit tests."
    dependsOn("testDebugUnitTest")

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(true)
    }

    val buildDirFile = layout.buildDirectory.get().asFile
    val kotlinClasses = fileTree(mapOf("dir" to "$buildDirFile/tmp/kotlin-classes/debug", "excludes" to coverageExclusions))
    val javaClasses = fileTree(mapOf("dir" to "$buildDirFile/intermediates/javac/debug/classes", "excludes" to coverageExclusions))
    classDirectories.setFrom(kotlinClasses, javaClasses)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
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

// Prints a one-line line/branch coverage summary to the build log so CI surfaces the number.
tasks.register("printCoverage") {
    group = "verification"
    description = "Prints the overall line coverage percentage from the JaCoCo XML report."
    dependsOn("jacocoTestReport")
    doLast {
        val xml = layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml").get().asFile
        if (!xml.exists()) {
            println("Coverage report not found at ${xml.path}")
            return@doLast
        }
        // The report DTD lives on a remote host; disable validation/DTD loading.
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
        println("JaCoCo coverage — INSTRUCTION: ${pct("INSTRUCTION")}")
        println("JaCoCo coverage — LINE:        ${pct("LINE")}")
        println("JaCoCo coverage — BRANCH:      ${pct("BRANCH")}")
        println("JaCoCo coverage — METHOD:      ${pct("METHOD")}")
        println("=".repeat(60))
    }
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


    // Testing — JVM unit tests (run on the JVM via Robolectric, no emulator required)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20231013") // standalone org.json for MapToJsonTest
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")

    // Testing — instrumented (device/emulator only; not run by the CI `build` task)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
