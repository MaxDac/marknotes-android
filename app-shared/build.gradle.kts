plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Everything the libre (F-Droid) and Plus apps share: Compose UI, Room and shared-content import. Cloud sync is not
// part of this repository: an app that has it provides SyncHooks through MarkNotesApp (see sync/SyncHooks.kt).
android {
    namespace = "io.github.maxdac.marknotes.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    api(variantOf(libs.jna) { artifactType("aar") })

    api(libs.androidx.core.ktx)
    api(libs.androidx.activity.compose)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.markwon.core)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.tasklist)
    implementation(libs.markwon.image)
    implementation(libs.markwon.syntax.highlight) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation(libs.prism4j) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    // Prism4j generates its grammar locator with a javac annotation processor (see CodeGrammars.java).
    annotationProcessor(libs.prism4j.bundler)
    // Text extraction from shared PDFs (see ui/importing).
    implementation(libs.pdfbox.android)
    constraints {
        // pdfbox-android's Bouncy Castle 1.72 has known vulnerabilities.
        implementation(libs.bouncycastle.bcprov.jdk15to18)
        implementation(libs.bouncycastle.bcpkix.jdk15to18)
        implementation(libs.bouncycastle.bcutil.jdk15to18)
        // prism4j-bundler's commons-io 2.6 (GHSA-78wr-2p64-hpwj, GHSA-gwrp-pvrq-jmwv); compile time only.
        annotationProcessor(libs.commons.io)
        // prism4j-bundler's Guava 22.0 (GHSA-xxph-c9ww-hj94, fixed in 33.7.2); compile time only.
        annotationProcessor(libs.guava)
    }
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // shared spec fixture loader (core/src/testFixtures).
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// The shared fixtures (the shared spec) are not part of this public repository, see :core.
val specDir = providers.gradleProperty("marknotes.specDir").map { file(it) }.getOrElse(file("../spec"))

tasks.withType<Test>().configureEach {
    // MentionParityTest runs a shared spec fixture from specDir; -Pmarknotes.parity.required=true makes
    // missing fixtures fail instead of skip.
    systemProperty("marknotes.specDir", specDir.absolutePath)
    systemProperty(
        "marknotes.parity.required",
        providers.gradleProperty("marknotes.parity.required").getOrElse("false")
    )
    inputs.files(fileTree(specDir.resolve("fixtures")) { include("**/*.json") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("specFixtures")
}
