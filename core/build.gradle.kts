plugins {
    id("org.jetbrains.kotlin.jvm")
    // The shared spec fixture loader, shared with :app-shared's parity tests.
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    // JNA calls the platform's no-replace rename (CaseRename.kt): :app-shared ships the Android AAR, JVM tests the jar.
    compileOnly(libs.jna)

    testImplementation(libs.jna)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.serialization.json)
}

// The shared fixtures (the shared spec) are not part of this public repository. A build that has them points
// -Pmarknotes.specDir at the checkout; without it the parity tests skip, unless marknotes.parity.required is set.
val specDir = providers.gradleProperty("marknotes.specDir").map { file(it) }.getOrElse(file("../spec"))
val specFixtures = fileTree(specDir.resolve("fixtures")) { include("**/*.json") }

tasks.withType<Test>().configureEach {
    // Parity tests (package ...core.parity) run the shared spec fixtures from specDir.
    systemProperty("marknotes.specDir", specDir.absolutePath)
    inputs.files(specFixtures).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("specFixtures")
}

tasks.named<Test>("test") {
    // Without the fixtures the parity tests skip; parityTest requires them.
    systemProperty("marknotes.parity.required", "false")
}

val parityTest by tasks.registering(Test::class) {
    description = "Runs the shared spec fixtures against :core (requires -Pmarknotes.specDir or a spec/ checkout)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("io.github.maxdac.marknotes.core.parity.*")
    systemProperty("marknotes.parity.required", "true")
}
