package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.ndarray.types.DataType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Asserts the generated IreeDataTypes mapping against the enriched iree-runtime-dist metadata. */
class IreeDataTypesTest {

    private record Mapping(String iree, String djl, boolean primary) {}

    private static JsonObject types;
    private static JsonObject numericalTypes;
    private static List<Mapping> mappings;

    @BeforeAll
    static void loadFixtures() throws Exception {
        String manifestProp = System.getProperty("ireeElementTypes");
        String mappingsProp = System.getProperty("ireeTypeMappings");
        assertNotNull(manifestProp, "system property ireeElementTypes must point at element_types.json");
        assertNotNull(mappingsProp, "system property ireeTypeMappings must point at iree-type-mappings.json");

        JsonObject manifest =
                JsonParser.parseReader(Files.newBufferedReader(Path.of(manifestProp))).getAsJsonObject();
        types = manifest.getAsJsonObject("element_types");
        numericalTypes = manifest.getAsJsonObject("encoding").getAsJsonObject("numerical_types");

        JsonArray arr =
                JsonParser.parseReader(Files.newBufferedReader(Path.of(mappingsProp))).getAsJsonArray();
        mappings = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            mappings.add(new Mapping(
                    o.get("iree").getAsString(),
                    o.get("djl").getAsString(),
                    !o.has("primary") || o.get("primary").getAsBoolean()));
        }
    }

    private static JsonObject props(String iree) {
        JsonObject p = types.getAsJsonObject(iree);
        assertNotNull(p, "metadata has no entry for " + iree);
        return p;
    }

    @Test
    void metadataIsSelfConsistent() {
        for (String name : types.keySet()) {
            JsonObject p = props(name);
            long expected = (numericalTypes.get(p.get("numerical_type").getAsString()).getAsLong() << 24)
                    | p.get("bit_count").getAsLong();
            assertEquals(expected, p.get("value").getAsLong(), name + " encoding formula");
            assertEquals(
                    String.format("0x%08x", p.get("value").getAsInt()),
                    p.get("hex").getAsString().toLowerCase(),
                    name + " hex");
            assertEquals(p.get("value").getAsInt() & 0xFF, p.get("bit_count").getAsInt(), name + " bit_count");
        }
    }

    @Test
    void generatedConstantsMatchMetadata() throws Exception {
        for (Mapping m : mappings) {
            int expected = props(m.iree()).get("value").getAsInt();
            int actual = IreeDataTypes.class.getField(m.iree()).getInt(null);
            assertEquals(expected, actual, m.iree() + " constant");
            if (m.primary()) {
                assertEquals(expected, IreeDataTypes.toIree(DataType.valueOf(m.djl())), m.iree() + " toIree");
            }
        }
    }

    @Test
    void roundTripThroughMaps() {
        Set<Integer> seen = new HashSet<>();
        for (Mapping m : mappings) {
            int v = props(m.iree()).get("value").getAsInt();
            assertTrue(seen.add(v), "duplicate constant value " + v + " for " + m.iree());
            assertEquals(DataType.valueOf(m.djl()), IreeDataTypes.fromIree(v), m.iree() + " fromIree");
            if (m.primary()) {
                DataType djl = DataType.valueOf(m.djl());
                assertEquals(djl, IreeDataTypes.fromIree(IreeDataTypes.toIree(djl)), m.iree() + " round-trip");
            }
        }
    }

    @Test
    void categoryMatchesDjlFamily() {
        for (Mapping m : mappings) {
            String expected = switch (m.djl()) {
                case "BOOLEAN" -> "boolean";
                case "COMPLEX64" -> "complex";
                default -> m.djl().startsWith("FLOAT") || m.djl().equals("BFLOAT16")
                        ? "float"
                        : m.djl().startsWith("INT") || m.djl().startsWith("UINT") ? "integer" : null;
            };
            assertNotNull(expected, "no expected category for " + m.djl());
            assertEquals(expected, props(m.iree()).get("category").getAsString(), m.iree() + " category");
        }
    }

    @Test
    void signednessPolicy() {
        for (Mapping m : mappings) {
            JsonObject p = props(m.iree());
            JsonElement signed = p.get("signed");
            if (!p.get("category").getAsString().equals("integer")) {
                assertTrue(signed.isJsonNull(), m.iree() + " must be sign-agnostic (signed=null)");
            } else if (m.djl().startsWith("UINT")) {
                assertTrue(!signed.isJsonNull() && !signed.getAsBoolean(),
                        m.iree() + " must be unsigned for " + m.djl());
            } else {
                assertTrue(signed.isJsonNull() || signed.getAsBoolean(),
                        m.iree() + " must be signed or sign-agnostic for " + m.djl());
            }
        }
    }

    @Test
    void sharedDjlTargetsAgreeOnBitWidth() {
        Map<String, Integer> bitCountByDjl = new HashMap<>();
        for (Mapping m : mappings) {
            int bits = props(m.iree()).get("bit_count").getAsInt();
            Integer existing = bitCountByDjl.putIfAbsent(m.djl(), bits);
            if (existing != null) {
                assertEquals(existing.intValue(), bits,
                        m.iree() + " and the other " + m.djl() + " mapping disagree on bit width");
            }
        }
    }
}
