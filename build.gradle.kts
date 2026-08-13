import java.io.FileNotFoundException
import java.util.Properties

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

// javac's javadoc doesn't recognize @apiNote out of the box (it's a JDK-internal convention,
// wired up only inside the JDK's own build); register it here so the jni package's internal-API
// markers render instead of failing the doc build with "unknown tag".
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).tags("apiNote:a:API Note:")
}

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

// --- Codegen manifest resolution ---
//
// Single source of truth for the release is native/cmake/IreeRuntimePin.cmake.
// tools/fetch-iree-metadata.sh reads it, downloads the metadata zip via `gh release download`,
// and writes third-party/iree-runtime-metadata.properties (zip path + tag). Gradle consumes
// that properties file; there is no version literal in this file. Run
// `bash tools/fetch-iree-metadata.sh` once before building.
//
// Declared before tasks.test: the test task's configuration action below reads
// ireeElementTypesPath, and Gradle may run it before later top-level declarations
// are initialized.
val ireeMetadataProps = file("third-party/iree-runtime-metadata.properties")

val ireeElementTypesOverride = findProperty("ireeElementTypes") as String?
val ireeMetadata = Properties()
if (ireeElementTypesOverride == null) {
    try {
        ireeMetadataProps.inputStream().use { ireeMetadata.load(it) }
    } catch (e: FileNotFoundException) {
        throw GradleException(
            "iree-runtime-dist metadata properties not found at $ireeMetadataProps\n" +
                "Run the one-time prerequisite first:\n" +
                "  bash tools/fetch-iree-metadata.sh   # requires gh CLI (https://cli.github.com/)"
        )
    }
}
val ireeMetadataZip = file(
    ireeMetadata.getProperty("ireeRuntimeMetadataZip", "third-party/iree-runtime-metadata.zip")
)
val ireeMetadataDir = layout.buildDirectory.dir("iree-metadata")

val ireeElementTypesPath = ireeElementTypesOverride
    ?: ireeMetadataDir.get().file("element_types.json").asFile.path

tasks.test {
    useJUnitPlatform { excludeTags("leak", "oom", "stress") }
    jvmArgs("-XX:+HeapDumpOnOutOfMemoryError")
    finalizedBy(tasks.jacocoTestReport)
    // IreeDataTypesTest validates the codegen against the same manifest/mappings it consumed.
    // ireeElementTypesPath is declared above (see the codegen comment) because this
    // configuration action runs before later top-level declarations are initialized.
    systemProperty("ireeElementTypes", ireeElementTypesPath)
    systemProperty("ireeTypeMappings", file("gradle/iree-type-mappings.json").path)
}

tasks.register<Test>("leakTest") {
    description = "Memory-leak stress tests under constrained heap/direct memory."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("leak") }
    jvmArgs("-Xmx256m", "-XX:MaxDirectMemorySize=64m", "-XX:+HeapDumpOnOutOfMemoryError")
}

tasks.register<Test>("stressTest") {
    description = "Concurrency stress tests for the observability snapshot."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("stress") }
}

// The OOM-contract fixture is a 512 MiB-output splat module (134217728 x f32),
// compiled at test time by the pinned iree-compile — never committed. The
// export task and the test task both resolve the fixture dir from the same
// Gradle-managed location so oomTest is self-contained (see
// tools/export_oom_fixture.sh).
val exportOomFixture = tasks.register<Exec>("exportOomFixture") {
    group = "verification"
    commandLine("bash", "tools/export_oom_fixture.sh")
    environment("IREE_FIXTURE_DIR", layout.buildDirectory.dir("oom-models").get().asFile.absolutePath)
}

tasks.register<Test>("oomTest") {
    description = "JNI output-marshalling failure-contract tests under a constrained heap."
    group = "verification"
    dependsOn(exportOomFixture)
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("oom") }
    jvmArgs("-Xmx128m")   // deliberately NO HeapDumpOnOutOfMemoryError: the OOM is the expected outcome
    systemProperty("ireeOomFixture", layout.buildDirectory.dir("oom-models").get().file("splat_134217728.vmfb").asFile.absolutePath)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        csv.required = false
        html.required = true
    }
}

// --- Codegen: IreeDataTypes from the iree-runtime-metadata zip ---
//
// Single source of truth for the release is native/cmake/IreeRuntimePin.cmake; the manifest
// resolution happens above (see the comment before tasks.test) because the test task consumes
// ireeElementTypesPath at configuration time.

val syncIreeMetadata = tasks.register<Sync>("syncIreeMetadata") {
    inputs.file(ireeMetadataProps)
    from(zipTree(ireeMetadataZip))
    into(ireeMetadataDir)
    // Resolve to a plain File local so the execution closure captures only File + String
    // (config-cache safe), not the enclosing script.
    val zip = ireeMetadataZip
    doFirst {
        require(zip.isFile) {
            "iree-runtime-dist metadata zip not found at $zip\n" +
                "Re-run the prerequisite: bash tools/fetch-iree-metadata.sh"
        }
    }
}

val generatedIreeSourcesDir = layout.buildDirectory.dir("generated/sources/iree")

val generateIreeDataTypes = tasks.register<IreeDataTypeCodegen>("generateIreeDataTypes") {
    // With an explicit -PireeElementTypes override, don't require the properties file.
    if (ireeElementTypesOverride == null) {
        dependsOn(syncIreeMetadata)
    }
    elementTypesManifest.set(file(ireeElementTypesPath))
    typeMappings.set(file("gradle/iree-type-mappings.json"))
    outputDir.set(generatedIreeSourcesDir)
}

// The pinned dist tag as a Java constant. Sourced from the same properties file the
// native build's pin generates, so there is no version literal in Java and no second
// source of truth.
//
// Its OWN output directory, not generatedIreeSourcesDir. Two tasks declaring the same
// output directory is a state Gradle explicitly flags: it disables build-cache
// eligibility for both, and because nothing orders these two against each other, it is
// a live hazard the moment IreeDataTypeCodegen becomes @CacheableTask — Gradle clears a
// cached task's output directory before unpacking into it, which would delete
// IreeRuntimeInfo.java if this task had already run. Separate directories, both on the
// main source set, cost one line and remove the coupling entirely.
val ireeDistTag = ireeMetadata.getProperty("ireeRuntimeDistTag", "unknown")

val generatedIreeRuntimeInfoDir = layout.buildDirectory.dir("generated/sources/iree-runtime-info")

val generateIreeRuntimeInfo = tasks.register("generateIreeRuntimeInfo") {
    val outDir = generatedIreeRuntimeInfoDir
    val tag = ireeDistTag
    inputs.property("ireeDistTag", tag)
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("org/measly/iree/engine")
        pkgDir.mkdirs()
        pkgDir.resolve("IreeRuntimeInfo.java").writeText(
            """
            package org.measly.iree.engine;

            /** Generated from third-party/iree-runtime-metadata.properties. Do not edit. */
            public final class IreeRuntimeInfo {

                /** The pinned iree-runtime-dist release tag, e.g. "v3.11.0-11". */
                public static final String DIST_TAG = "$tag";

                private IreeRuntimeInfo() {}
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets {
    main {
        java.srcDir(generatedIreeSourcesDir)
        java.srcDir(generatedIreeRuntimeInfoDir)
    }
}

tasks.named("compileJava") {
    dependsOn(generateIreeDataTypes, generateIreeRuntimeInfo)
}

tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(generateIreeDataTypes, generateIreeRuntimeInfo)
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
    // -Xcheck:jni is the only lever that catches this defect class: JNI calls
    // made with a pending exception, and null array arguments. It is on the
    // umbrella rather than on tasks.test deliberately -- tasks.test excludes the
    // leak/oom/stress tags, and oomTest is the one task that drives the
    // allocation-failure paths where those defects hide. Costs nothing: it runs
    // against the plain shipping library.
    jvmArgs("-Xcheck:jni")
}

// one entry per platform the native build workflow produces (see
// .github/workflows/native-build-job.yml); each gets a classifier jar +
// consumable variant below.
val nativePlatforms = listOf("linux-x86_64", "linux-aarch64", "windows-x86_64")

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

// Publish each native jar as a real GMM variant (not just a bare classified artifact) so Gradle
// consumers can select it via attributes/capability instead of a Maven classifier string. The
// per-platform capability keeps the variants out of default resolution: without it, both native
// variants match the standard JVM attribute set and an attribute-less consumer hits ambiguity.
val nativeVariants = nativePlatforms.map { platform ->
    val osFamily = platform.substringBefore("-") // linux / windows
    // Gradle's MachineArchitecture has no AARCH64 constant; aarch64 maps to ARM64.
    val machineArch = when (platform) {
        "linux-x86_64" -> MachineArchitecture.X86_64
        "windows-x86_64" -> MachineArchitecture.X86_64
        "linux-aarch64" -> MachineArchitecture.ARM64
        else -> error("unhandled platform: $platform")
    }
    configurations.consumable("nativeRuntimeElements-${platform}") {
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named(osFamily))
            attribute(
                MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
                objects.named(machineArch)
            )
        }
        outgoing {
            capability("${project.group}:djl-iree-engine-${platform}:${project.version}")
            artifact(tasks.named("nativeJar-${platform}"))
        }
    }
}

(components["java"] as AdhocComponentWithVariants).apply {
    nativeVariants.forEach { variant ->
        addVariantsFromConfiguration(variant.get()) { mapToOptional() }
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
