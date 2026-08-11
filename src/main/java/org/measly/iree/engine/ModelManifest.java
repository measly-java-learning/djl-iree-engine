package org.measly.iree.engine;

import ai.djl.util.JsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A parsed model manifest document. Pure: no filesystem access, no path
 * resolution — {@code sourceLabel} is carried solely for error messages.
 *
 * <p>Parsing is a hand-walk over a Gson {@link JsonObject}, not POJO binding:
 * binding would give rule 3 (unknown fields ignored) for free, but Gson's
 * type-mismatch messages are unusable. The five schema rules:
 * <ol>
 *   <li>schemaVersion is a single JSON integer; {@code "1"} and {@code 1.0}
 *       are type errors, never coerced.</li>
 *   <li>schemaVersion is required and has no default.</li>
 *   <li>Unknown fields are ignored silently — the hand-walk reads only keys
 *       it knows.</li>
 *   <li>Unknown values in consumed fields are an error (nothing to enforce in
 *       v1: no consumed field is an enum; types are enforced instead).</li>
 *   <li>{@code requires} must be understood — v1 understands zero keys, so
 *       any non-empty object is an error naming the first unknown key.</li>
 * </ol>
 * schemaVersion is validated and range-checked before any other key is read,
 * so a future manifest reports a clean version error, not a type error from
 * further down the document.
 *
 * <p>Schema v1, the only version this engine understands: {@code schemaVersion} and {@code
 * program} are required, with no defaults — {@code schemaVersion} absent is an error, never
 * assumed to be 1. {@code entryPoint} and {@code parameters} are optional; an absent {@code
 * parameters} is equivalent to {@code {}}. Fields this class does not read are ignored rather
 * than rejected, so the format can grow new keys without breaking this engine on an older
 * manifest written for a newer one.
 */
public record ModelManifest(int schemaVersion, String program, String entryPoint,
                            Map<String, String> parameters) {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /**
     * Parses and validates a manifest document against the schema described in the class
     * comment.
     *
     * @param json the manifest document text
     * @param sourceLabel identifies the document in error messages only (e.g. a file path, or
     *     {@code "<implicit manifest>"} for a bare {@code .vmfb}); carries no other meaning
     * @return the parsed manifest
     * @throws ManifestException if {@code json} is not valid JSON, is empty, or violates any
     *     schema rule — see the class comment for the specific cases
     */
    public static ModelManifest parse(String json, String sourceLabel) throws ManifestException {
        JsonObject doc;
        try {
            doc = JsonUtils.GSON.fromJson(json, JsonObject.class);
        } catch (JsonSyntaxException e) {
            throw new ManifestException(sourceLabel + ": malformed manifest JSON: " + e.getMessage());
        }
        if (doc == null) { // Gson returns null for "", whitespace, and the literal null
            throw new ManifestException(sourceLabel + ": malformed manifest JSON: document is empty or null");
        }
        int version = readSchemaVersion(doc, sourceLabel);
        String program = readRequiredString(doc, "program", sourceLabel);
        String entryPoint = readOptionalString(doc, "entryPoint", sourceLabel);
        Map<String, String> parameters = readParameters(doc, sourceLabel);
        readRequires(doc, sourceLabel);
        return new ModelManifest(version, program, entryPoint, parameters);
    }

    private static int readSchemaVersion(JsonObject doc, String sourceLabel)
            throws ManifestException {
        JsonElement v = doc.get("schemaVersion");
        if (v == null) {
            throw new ManifestException(
                    sourceLabel
                            + ": schemaVersion is required and has no default; the document must declare it explicitly");
        }
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            throw new ManifestException(
                    sourceLabel + ": schemaVersion must be a JSON integer; found " + v);
        }
        BigDecimal number = v.getAsBigDecimal();
        if (number.scale() > 0) { // 1.0 is a float literal, not an integer; do not truncate
            throw new ManifestException(
                    sourceLabel + ": schemaVersion must be a JSON integer; found " + v);
        }
        int version;
        try {
            version = number.intValueExact();
        } catch (ArithmeticException e) {
            throw new ManifestException(
                    sourceLabel + ": schemaVersion must fit in an int; found " + v);
        }
        if (version > SUPPORTED_SCHEMA_VERSION) {
            throw new ManifestException(
                    sourceLabel + ": model manifest requires schema version " + version
                            + "; this engine (" + IreeEngine.getEngineVersion()
                            + ") supports up to " + SUPPORTED_SCHEMA_VERSION
                            + " — upgrade the engine");
        }
        if (version < 1) {
            throw new ManifestException(
                    sourceLabel + ": schemaVersion must be a positive integer; found " + v);
        }
        return version;
    }

    private static String readRequiredString(JsonObject doc, String key, String sourceLabel)
            throws ManifestException {
        JsonElement el = doc.get(key);
        if (el == null) {
            throw new ManifestException(
                    sourceLabel + ": field '" + key + "' is required and has no default");
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new ManifestException(
                    sourceLabel + ": field '" + key + "' must be a string; found " + el);
        }
        return el.getAsString();
    }

    private static String readOptionalString(JsonObject doc, String key, String sourceLabel)
            throws ManifestException {
        JsonElement el = doc.get(key);
        if (el == null) return null;
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new ManifestException(
                    sourceLabel + ": field '" + key + "' must be a string; found " + el);
        }
        return el.getAsString();
    }

    private static Map<String, String> readParameters(JsonObject doc, String sourceLabel)
            throws ManifestException {
        JsonElement el = doc.get("parameters");
        if (el == null) return Map.of();
        if (!el.isJsonObject()) {
            throw new ManifestException(
                    sourceLabel + ": field 'parameters' must be an object; found " + el);
        }
        Map<String, String> out = new LinkedHashMap<>(); // document order, deterministic arrays
        for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
            JsonElement value = e.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new ManifestException(
                        sourceLabel + ": field 'parameters." + e.getKey()
                                + "' must be a string; found " + value);
            }
            out.put(e.getKey(), value.getAsString());
        }
        return out;
    }

    private static void readRequires(JsonObject doc, String sourceLabel) throws ManifestException {
        JsonElement el = doc.get("requires");
        if (el == null) return;
        if (!el.isJsonObject()) {
            throw new ManifestException(
                    sourceLabel + ": field 'requires' must be an object; found " + el);
        }
        JsonObject req = el.getAsJsonObject();
        if (!req.isEmpty()) {
            String first = req.keySet().iterator().next(); // first unknown key in document order
            throw new ManifestException(
                    sourceLabel
                            + ": manifest requires '"
                            + first
                            + "' which this engine does not understand");
        }
    }
}
