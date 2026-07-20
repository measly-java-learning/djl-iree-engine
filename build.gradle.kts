plugins {
    `java-library`
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
val ireeElementTypesPath: String =
    (findProperty("ireeElementTypes") as String?)
        ?: "${projectDir}/native/build/_deps/iree_runtime_dist-src/share/iree-runtime-dist/element_types.json"

val generatedIreeSourcesDir = layout.buildDirectory.dir("generated/sources/iree")

val generateIreeDataTypes = tasks.register("generateIreeDataTypes") {
    description = "Generates IreeDataTypes.java from the iree-runtime-dist element_types.json manifest."

    val elementTypesFile = file(ireeElementTypesPath)
    val outputDir = generatedIreeSourcesDir

    inputs.property("ireeElementTypesPath", ireeElementTypesPath)
    outputs.dir(outputDir)

    doLast {
        if (!elementTypesFile.exists()) {
            throw GradleException(
                "iree-runtime-dist element_types.json not found at: $elementTypesFile\n" +
                    "This file is produced by the native CMake build (FetchContent of iree-runtime-dist).\n" +
                    "Run `./native/build.sh` first, or point at an alternate manifest with " +
                    "-PireeElementTypes=/path/to/element_types.json."
            )
        }

        // Manifest is a flat JSON object of NAME -> decimal int, e.g. {"FLOAT_32": 553648160, ...}.
        // No JSON library dependency is added: Gradle already bundles Groovy, so
        // groovy.json.JsonSlurper is on the build classpath for free. Parsing structurally
        // (rather than regex-scanning the raw text) means a nested envelope or a truncated
        // file is *detected*, not silently tolerated.
        val minExpectedEntryCount = 24 // per handover doc §4; upstream may legitimately add more later

        val parsed = try {
            groovy.json.JsonSlurper().parse(elementTypesFile)
        } catch (e: Exception) {
            throw GradleException(
                "iree-runtime-dist element_types.json ($elementTypesFile) is not valid JSON: ${e.message}",
                e
            )
        }

        if (parsed !is Map<*, *>) {
            throw GradleException(
                "iree-runtime-dist element_types.json ($elementTypesFile) has an unexpected shape: " +
                    "expected a flat JSON object of NAME -> integer, got a ${parsed?.javaClass?.name ?: "null"}. " +
                    "The manifest's schema appears to have changed; do not trust the generated constants " +
                    "until this is reconciled."
            )
        }

        val entries = LinkedHashMap<String, Long>()
        for ((rawKey, rawValue) in parsed) {
            if (rawKey !is String || rawValue !is Number) {
                throw GradleException(
                    "iree-runtime-dist element_types.json ($elementTypesFile) has an unexpected entry: " +
                        "key=$rawKey (${rawKey?.javaClass?.name}), value=$rawValue (${rawValue?.javaClass?.name}). " +
                        "Expected every entry to be a String name mapped to a numeric value (a nested " +
                        "envelope or non-numeric value means the manifest's schema changed)."
                )
            }
            entries[rawKey] = rawValue.toLong()
        }

        if (entries.size < minExpectedEntryCount) {
            throw GradleException(
                "iree-runtime-dist element_types.json ($elementTypesFile) has only ${entries.size} entries, " +
                    "expected at least $minExpectedEntryCount per the handover doc (§4). Fewer entries than " +
                    "the known-good manifest means the file is truncated or corrupted; refusing to generate " +
                    "IreeDataTypes from it until this is reconciled. (More than $minExpectedEntryCount is fine " +
                    "-- that's a legitimate upstream addition.)"
            )
        }

        fun require(name: String): Long =
            entries[name]
                ?: throw GradleException(
                    "iree-runtime-dist element_types.json ($elementTypesFile) has no entry named " +
                        "\"$name\". Found ${entries.size} entries: ${entries.keys}."
                )

        fun requireInt(name: String): Int {
            val value = require(name)
            if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw GradleException(
                    "iree-runtime-dist element_types.json entry \"$name\" = $value does not fit in a Java int."
                )
            }
            return value.toInt()
        }

        val float32 = requireInt("FLOAT_32")
        val sint32 = requireInt("SINT_32")

        val packageDir = outputDir.get().dir("org/measly/iree/engine").asFile
        packageDir.mkdirs()
        val outFile = packageDir.resolve("IreeDataTypes.java")

        outFile.writeText(
            """
            package org.measly.iree.engine;

            import ai.djl.ndarray.types.DataType;

            /**
             * Maps DJL data types to iree_hal_element_type_t values.
             *
             * <p>GENERATED CODE - DO NOT EDIT BY HAND.
             * Generated by the {@code generateIreeDataTypes} Gradle task from
             * iree-runtime-dist's {@code element_types.json} manifest ($ireeElementTypesPath).
             * Only the types the skeleton exercises are mapped.
             */
            public final class IreeDataTypes {

                public static final int FLOAT_32 = $float32;
                public static final int SINT_32 = $sint32;

                private IreeDataTypes() {}

                public static int toIree(DataType type) {
                    switch (type) {
                        case FLOAT32:
                            return FLOAT_32;
                        case INT32:
                            return SINT_32;
                        default:
                            throw new UnsupportedOperationException(
                                    "Unsupported data type for the IREE skeleton: " + type);
                    }
                }

                public static DataType fromIree(int elementType) {
                    switch (elementType) {
                        case FLOAT_32:
                            return DataType.FLOAT32;
                        case SINT_32:
                            return DataType.INT32;
                        default:
                            throw new UnsupportedOperationException(
                                    "Unsupported IREE element type: 0x"
                                            + Integer.toHexString(elementType));
                    }
                }
            }
            """.trimIndent()
        )
    }
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
