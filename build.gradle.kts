plugins {
    `java-library`
    jacoco
    alias(libs.plugins.jacoco.to.cobertura)
}

group = "org.measly"
version = "0.1.0-SNAPSHOT"

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
    useJUnitPlatform()
    jvmArgs("-XX:+HeapDumpOnOutOfMemoryError")
    finalizedBy(tasks.jacocoTestReport)
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

