plugins {
    `java-library`
    jacoco
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.jacoco.to.cobertura)
}

group = "org.measly"
version = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .getOrElse("0.1.0-SNAPSHOT")

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories { mavenCentral() }

dependencies {
    compileOnly(libs.djl.api)
    compileOnly(libs.slf4j.api)

    testImplementation(libs.djl.api)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform { excludeTags("leak") }
    jvmArgs("-XX:+HeapDumpOnOutOfMemoryError")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("leakTest") {
    description = "Memory-leak stress tests under constrained heap/direct memory."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("leak") }
    jvmArgs("-Xmx256m", "-XX:MaxDirectMemorySize=64m", "-XX:+HeapDumpOnOutOfMemoryError")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        csv.required = false
        html.required = true
    }
}

// --- Codegen: IreeDataTypes from the iree-runtime-dist element_types.json ---
//
// The dist publishes this manifest inside the CMake build tree (FetchContent's
// extraction dir). Gradle cannot see CMake variables (IREE_RUNTIME_DIST_ELEMENT_TYPES
// is a CMake-only value), so we resolve the path pragmatically here instead: default
// to the known FetchContent layout, but let -PireeElementTypes=<path> override it so
// the build isn't hostage to that internal layout if it ever changes.
//
// We deliberately read straight out of native/build/_deps/... rather than asking the
// native/ CMake build to copy the file to a stable location first: changing native/ is
// out of scope for this task, and the override property already gives callers an escape
// hatch from the coupling without touching CMake.
//
// Parsing, validation, and code generation live in the IreeDataTypeCodegen task class
// (buildSrc/) so this file stays lean. Type mappings between IREE element type names
// and DJL DataType enum constants live in gradle/iree-type-mappings.json.

val ireeElementTypesPath: String =
    (findProperty("ireeElementTypes") as String?)
        ?: project.files(
            fileTree("src/main/resources/native") { include("**/element_types.json") },
            fileTree("build/native-staging") { include("**/element_types.json") },
        ).firstOrNull()?.path
            ?: "${projectDir}/native/build/_deps/iree_runtime_dist-src/share/iree-runtime-dist/element_types.json"

val generatedIreeSourcesDir = layout.buildDirectory.dir("generated/sources/iree")

val generateIreeDataTypes = tasks.register<IreeDataTypeCodegen>("generateIreeDataTypes") {
    description = "Generates IreeDataTypes.java from the iree-runtime-dist element_types.json manifest."
    elementTypesManifest.set(file(ireeElementTypesPath))
    typeMappings.set(file("gradle/iree-type-mappings.json"))
    outputDir.set(generatedIreeSourcesDir)
}

sourceSets {
    main {
        java.srcDir(generatedIreeSourcesDir)
    }
}

tasks.named("compileJava") {
    dependsOn(generateIreeDataTypes)
}

tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(generateIreeDataTypes)
}

// LibUtils resolves the native library from IREE_LIBRARY_PATH before falling
// back to the classpath copy, so this variable changes WHICH .so is under test.
// Undeclared, it is invisible to the up-to-date check: point it elsewhere and
// Gradle would replay a cached pass for a run that loaded something else.
tasks.withType<Test>().configureEach {
    inputs.property(
        "ireeLibraryPath",
        providers.environmentVariable("IREE_LIBRARY_PATH").orElse("")
    )
}

// list of one as preparation for additional platforms
val nativePlatforms = listOf("linux-x86_64")

// MSVC emits no `lib` prefix and a .dll suffix. Keep in sync with LibUtils.libName.
fun nativeLibName(platform: String): String =
    if (platform.startsWith("windows-")) "iree_djl.dll" else "libiree_djl.so"

// Look for the platform's native library in build/native-staging/<platform>/<nativeLibName>
val nativeStaging = layout.buildDirectory.dir("native-staging")
val nativeJarTasks = nativePlatforms.map { platform ->
  tasks.register<Jar>("nativeJar-${platform}") {
    archiveClassifier.set(platform)
    // The native library, excluding the bundled licenses subtree (mapped to META-INF below).
    from(nativeStaging.map { it.dir(platform) }) {
        exclude("licenses/**")
        into("native/${platform}")
    }
    // Third-party notices from the runtime tarball, staged next to the .so by native/build.sh.
    from(nativeStaging.map { it.dir("${platform}/licenses") }) {
        into("META-INF/licenses/iree-runtime")
    }
    // Resolve to plain Files at configuration time so doFirst captures only File + String
    // (config-cache safe) rather than the enclosing script.
    val resolvedLib = nativeStaging.get().dir(platform).file(nativeLibName(platform)).asFile
    val licensesDir = nativeStaging.get().dir(platform).dir("licenses").asFile
    doFirst { // Fail a release rather than ship an empty native jar or a binary with no notices
        require(resolvedLib.exists()) { "Missing native library for ${platform}: ${resolvedLib}" }
        require(licensesDir.isDirectory && (licensesDir.list()?.isNotEmpty() ?: false)) {
            "Missing third-party notices for ${platform}: ${licensesDir}" +
                " (native/build.sh must stage LICENSE + THIRD-PARTY-NOTICES/)"
        }
    }
  }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        nativeJarTasks.forEach { artifact(it) }
    }
}

mavenPublishing {
    //publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.PUBLISHED)
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "djl-iree-engine", version.toString())

    pom {
        name.set("DJL IREE Engine")
        description.set("Enable use of IREE models within DJL")
        inceptionYear.set("2026")
        url.set("https://github.com/measly-java-learning/djl-iree-engine")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("corey-cole")
                name.set("Corey Cole")
                url.set("https://github.com/corey-cole")
            }
        }
        scm {
            url.set("https://github.com/measly-java-learning/djl-iree-engine")
            connection.set("scm:git:git://github.com/measly-java-learning/djl-iree-engine.git")
            developerConnection.set("scm:git:ssh://git@github.com/measly-java-learning/djl-iree-engine.git")
        }
    }
}
