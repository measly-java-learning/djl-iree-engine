import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class IreeDataTypeCodegen : DefaultTask() {

    @get:InputFile
    abstract val elementTypesManifest: RegularFileProperty

    @get:InputFile
    abstract val typeMappings: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val manifestFile = elementTypesManifest.get().asFile
        val mappingsFile = typeMappings.get().asFile

        if (!manifestFile.exists()) {
            throw GradleException(
                "iree-runtime-dist element_types.json not found at: $manifestFile\n" +
                    "This file is produced by the native CMake build (FetchContent of iree-runtime-dist).\n" +
                    "Run `./native/build.sh` first, or point at an alternate manifest with " +
                    "-PireeElementTypes=/path/to/element_types.json."
            )
        }

        val manifest = parseManifest(manifestFile)
        val mappings = parseMappings(mappingsFile)

        validateMappings(mappings, manifest)

        val outDir = outputDir.get().asFile
        val pkgDir = outDir.resolve("org/measly/iree/engine")
        pkgDir.mkdirs()

        val javaFile = pkgDir.resolve("IreeDataTypes.java")
        javaFile.writeText(generateJava(manifest, mappings, manifestFile))
    }

    // ---------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------

    private fun parseManifest(file: java.io.File): Map<String, Int> {
        val parsed = try {
            JsonSlurper().parse(file)
        } catch (e: Exception) {
            throw GradleException(
                "iree-runtime-dist element_types.json ($file) is not valid JSON: ${e.message}", e
            )
        }

        if (parsed !is Map<*, *>) {
            throw GradleException(
                "element_types.json ($file) has an unexpected shape: " +
                    "expected a flat JSON object of NAME -> integer, got a ${parsed?.javaClass?.name ?: "null"}."
            )
        }

        val entries = LinkedHashMap<String, Int>()
        for ((rawKey, rawValue) in parsed) {
            if (rawKey !is String || rawValue !is Number) {
                throw GradleException(
                    "element_types.json ($file) has an unexpected entry: " +
                        "key=$rawKey (${rawKey?.javaClass?.name}), value=$rawValue (${rawValue?.javaClass?.name})."
                )
            }
            val intValue = rawValue.toLong()
            if (intValue !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw GradleException(
                    "element_types.json entry \"$rawKey\" = $rawValue does not fit in a Java int."
                )
            }
            entries[rawKey] = intValue.toInt()
        }

        if (entries.size < 24) {
            throw GradleException(
                "element_types.json ($file) has only ${entries.size} entries, " +
                    "expected at least 24. The file may be truncated or corrupted."
            )
        }

        return entries
    }

    private data class TypeMapping(
        val iree: String,
        val djl: String,
        val primary: Boolean
    )

    /*
       - "primary" defaults to true — controls which IREE type wins for DJL→IREE direction
       - INT_* entries are "primary": false since SINT_* is the preferred signed representation (consistent with the existing choice of SINT_32 for INT32)
       - 6 IREE types intentionally excluded: NONE, OPAQUE_*, COMPLEX_FLOAT_128 (no DJL equivalent)
    */
    private fun parseMappings(file: java.io.File): List<TypeMapping> {
        if (!file.exists()) {
            throw GradleException("IREE type mappings file not found: $file")
        }

        val parsed = try {
            JsonSlurper().parse(file)
        } catch (e: Exception) {
            throw GradleException("IREE type mappings file ($file) is not valid JSON: ${e.message}", e)
        }

        if (parsed !is List<*>) {
            throw GradleException(
                "IREE type mappings file ($file) has an unexpected shape: " +
                    "expected a JSON array, got a ${parsed?.javaClass?.name ?: "null"}."
            )
        }

        val mappings = mutableListOf<TypeMapping>()
        for ((idx, rawEntry) in parsed.withIndex()) {
            if (rawEntry !is Map<*, *>) {
                throw GradleException(
                    "Entry $idx in mappings file is not a JSON object: ${rawEntry?.javaClass?.name}"
                )
            }
            val iree = rawEntry["iree"] as? String
                ?: throw GradleException("Entry $idx in mappings file is missing the \"iree\" key.")
            val djl = rawEntry["djl"] as? String
                ?: throw GradleException("Entry $idx (iree=$iree) is missing the \"djl\" key.")
            val primary = when (val p = rawEntry["primary"]) {
                null -> true
                is Boolean -> p
                else -> throw GradleException(
                    "Entry $idx (iree=$iree): \"primary\" must be a boolean, got ${p?.javaClass?.name}"
                )
            }
            mappings.add(TypeMapping(iree, djl, primary))
        }

        if (mappings.isEmpty()) {
            throw GradleException("IREE type mappings file ($file) contains no entries.")
        }

        return mappings
    }

    // ---------------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------------

    private fun validateMappings(mappings: List<TypeMapping>, manifest: Map<String, Int>) {
        // 1. Every IREE key in mappings must exist in the manifest
        for (m in mappings) {
            if (m.iree !in manifest) {
                throw GradleException(
                    "Mapping references IREE type \"${m.iree}\" which is not present in " +
                        "element_types.json. Available types: ${manifest.keys}"
                )
            }
        }

        // 2. Every DJL target must be a valid DataType enum constant
        val validDjl = setOf(
            "FLOAT32", "FLOAT64", "FLOAT16",
            "UINT8", "UINT16", "UINT32", "UINT64",
            "INT8", "INT16", "INT32", "INT64",
            "BOOLEAN", "BFLOAT16", "COMPLEX64",
            "UNKNOWN", "STRING"
        )
        for (m in mappings) {
            if (m.djl !in validDjl) {
                throw GradleException(
                    "Mapping iree=${m.iree} references unknown DJL DataType \"${m.djl}\". " +
                        "Valid values: $validDjl"
                )
            }
        }

        // 3. Every primary mapping must have a unique DJL target (no ambiguity for toIree)
        val primaryByDjl = LinkedHashMap<String, String>()
        for (m in mappings) {
            if (!m.primary) continue
            val existing = primaryByDjl[m.djl]
            if (existing != null) {
                throw GradleException(
                    "Both \"$existing\" and \"${m.iree}\" map to DJL ${
                        m.djl
                    } as primary. Only one IREE type can be the primary target for each DJL type."
                )
            }
            primaryByDjl[m.djl] = m.iree
        }
    }

    // ---------------------------------------------------------------------------
    // Code generation
    // ---------------------------------------------------------------------------

    private fun generateJava(
        manifest: Map<String, Int>,
        mappings: List<TypeMapping>,
        manifestFile: java.io.File
    ): String {
        val sb = StringBuilder()

        sb.appendLine("package org.measly.iree.engine;")
        sb.appendLine()
        sb.appendLine("import ai.djl.ndarray.types.DataType;")
        sb.appendLine("import java.util.HashMap;")
        sb.appendLine("import java.util.Map;")
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * Maps between DJL data types and iree_hal_element_type_t values.")
        sb.appendLine(" *")
        sb.appendLine(" * <p>GENERATED CODE - DO NOT EDIT BY HAND.")
        sb.appendLine(" * Generated by the {@code generateIreeDataTypes} Gradle task from:")
        sb.appendLine(" * <ul>")
        sb.appendLine(" *   <li>iree-runtime-dist element_types.json (${manifestFile.absolutePath})</li>")
        sb.appendLine(" *   <li>iree-type-mappings.json</li>")
        sb.appendLine(" * </ul>")
        sb.appendLine(" */")
        sb.appendLine("public final class IreeDataTypes {")
        sb.appendLine()

        // -- Constants for every mapped IREE element type --
        sb.appendLine("    // IREE element type constants")
        val sortedMappings = mappings.sortedBy { it.iree }
        for (m in sortedMappings) {
            val value = manifest[m.iree]!!
            val hex = "0x${value.toUInt().toString(16).uppercase()}"
            sb.appendLine("    public static final int ${m.iree} = $value; // $hex")
        }
        sb.appendLine()

        // -- Static lookup maps --
        sb.appendLine("    private static final Map<DataType, Integer> DJL_TO_IREE = new HashMap<>();")
        sb.appendLine("    private static final Map<Integer, DataType> IREE_TO_DJL = new HashMap<>();")
        sb.appendLine()
        sb.appendLine("    static {")

        // DJL → IREE (primary mappings only)
        for (m in sortedMappings) {
            if (m.primary) {
                sb.appendLine("        DJL_TO_IREE.put(DataType.${m.djl}, ${m.iree});")
            }
        }

        sb.appendLine()

        // IREE → DJL (all mappings)
        for (m in sortedMappings) {
            sb.appendLine("        IREE_TO_DJL.put(${m.iree}, DataType.${m.djl});")
        }

        sb.appendLine("    }")
        sb.appendLine()

        // -- Constructor --
        sb.appendLine("    private IreeDataTypes() {}")
        sb.appendLine()

        // -- toIree --
        sb.appendLine("    /**")
        sb.appendLine("     * Maps a DJL {@link DataType} to its corresponding IREE element type value.")
        sb.appendLine("     *")
        sb.appendLine("     * @param type the DJL data type")
        sb.appendLine("     * @return the IREE iree_hal_element_type_t value")
        sb.appendLine("     * @throws UnsupportedOperationException if the type is not mapped")
        sb.appendLine("     */")
        sb.appendLine("    public static int toIree(DataType type) {")
        sb.appendLine("        Integer value = DJL_TO_IREE.get(type);")
        sb.appendLine("        if (value == null) {")
        sb.appendLine("            throw new UnsupportedOperationException(")
        sb.appendLine("                    \"Unsupported data type for the IREE skeleton: \" + type);")
        sb.appendLine("        }")
        sb.appendLine("        return value;")
        sb.appendLine("    }")
        sb.appendLine()

        // -- fromIree --
        sb.appendLine("    /**")
        sb.appendLine("     * Maps an IREE element type value to its corresponding DJL {@link DataType}.")
        sb.appendLine("     *")
        sb.appendLine("     * @param elementType the IREE iree_hal_element_type_t value")
        sb.appendLine("     * @return the DJL data type")
        sb.appendLine("     * @throws UnsupportedOperationException if the element type is not mapped")
        sb.appendLine("     */")
        sb.appendLine("    public static DataType fromIree(int elementType) {")
        sb.appendLine("        DataType type = IREE_TO_DJL.get(elementType);")
        sb.appendLine("        if (type == null) {")
        sb.appendLine("            throw new UnsupportedOperationException(")
        sb.appendLine("                    \"Unsupported IREE element type: 0x\"")
        sb.appendLine("                            + Integer.toHexString(elementType));")
        sb.appendLine("        }")
        sb.appendLine("        return type;")
        sb.appendLine("    }")
        sb.appendLine("}")

        return sb.toString()
    }

    private fun StringBuilder.appendLine(s: String = "") {
        append(s)
        append('\n')
    }
}
