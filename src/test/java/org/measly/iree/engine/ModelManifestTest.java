package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure schema-rule tests: string literals only, no temp dirs, no native library. */
class ModelManifestTest {

    private static ModelManifest parse(String json) throws ManifestException {
        return ModelManifest.parse(json, "test-manifest.json");
    }

    private static String message(Class<? extends Exception> type, String json) {
        return assertThrows(type, () -> parse(json)).getMessage();
    }

    @Test
    void parsesFullDocument() throws ManifestException {
        ModelManifest m = parse("""
                {"schemaVersion":1,"program":"model.vmfb","entryPoint":"module.main",\
                 "parameters":{"model":"weights.irpa","bias":"bias.irpa"}}""");
        assertEquals(1, m.schemaVersion());
        assertEquals("model.vmfb", m.program());
        assertEquals("module.main", m.entryPoint());
        assertEquals(Map.of("model", "weights.irpa", "bias", "bias.irpa"), m.parameters());
    }

    @Test
    void parsesMinimalDocument() throws ManifestException {
        ModelManifest m = parse("""
                {"schemaVersion":1,"program":"model.vmfb"}""");
        assertEquals("model.vmfb", m.program());
        assertNull(m.entryPoint());
        assertEquals(Map.of(), m.parameters());
    }

    @Test
    void absentParametersIsEmptyMap() throws ManifestException {
        ModelManifest m = parse("""
                {"schemaVersion":1,"program":"model.vmfb"}""");
        assertTrue(m.parameters().equals(Map.of()));
    }

    @Test
    void ignoresUnknownFields() throws ManifestException {
        ModelManifest m = parse("""
                {"schemaVersion":1,"program":"model.vmfb",\
                 "variants":{"sse":"a.vmfb"},"futureField":123}""");
        assertEquals("model.vmfb", m.program());
        assertEquals(1, m.schemaVersion());
        assertEquals(Map.of(), m.parameters());
    }

    @Test
    void rejectsMissingSchemaVersion() {
        String msg = message(ManifestException.class, """
                {"program":"model.vmfb"}""");
        assertTrue(msg.contains("schemaVersion is required"), msg);
    }

    @Test
    void rejectsStringSchemaVersion() {
        String msg =
                message(ManifestException.class, """
                        {"schemaVersion":"1","program":"model.vmfb"}""");
        assertTrue(msg.contains("must be a JSON integer"), msg);
    }

    @Test
    void rejectsFloatSchemaVersion() {
        String msg =
                message(ManifestException.class, """
                        {"schemaVersion":1.0,"program":"model.vmfb"}""");
        assertTrue(msg.contains("must be a JSON integer"), msg);
    }

    @Test
    void rejectsFutureSchemaVersionBeforeOtherKeys() {
        String msg = message(ManifestException.class, """
                {"schemaVersion":2,"program":123}""");
        assertTrue(msg.contains("requires schema version 2"), msg);
        assertTrue(msg.contains("supports up to 1"), msg);
        assertTrue(msg.contains("0.1.0-SNAPSHOT"), msg);
        assertTrue(!msg.contains("program"), "version error must win before later-key errors: " + msg);
    }

    @Test
    void rejectsNonPositiveSchemaVersion() {
        String msg0 =
                message(ManifestException.class, """
                        {"schemaVersion":0,"program":"model.vmfb"}""");
        assertTrue(msg0.contains("positive integer"), msg0);
        String msgNeg =
                message(ManifestException.class, """
                        {"schemaVersion":-1,"program":"model.vmfb"}""");
        assertTrue(msgNeg.contains("positive integer"), msgNeg);
    }

    @Test
    void rejectsMissingProgram() {
        String msg = message(ManifestException.class, """
                {"schemaVersion":1}""");
        assertTrue(msg.contains("'program' is required"), msg);
    }

    @Test
    void rejectsWrongTypeProgram() {
        String msg = message(ManifestException.class, """
                {"schemaVersion":1,"program":5}""");
        assertTrue(msg.contains("field 'program' must be a string"), msg);
    }

    @Test
    void rejectsWrongTypeEntryPoint() {
        String msg =
                message(
                        ManifestException.class,
                        """
                        {"schemaVersion":1,"program":"m.vmfb","entryPoint":5}""");
        assertTrue(msg.contains("field 'entryPoint' must be a string"), msg);
    }

    @Test
    void rejectsWrongTypeParameters() {
        String msg =
                message(
                        ManifestException.class,
                        """
                        {"schemaVersion":1,"program":"m.vmfb","parameters":[1]}""");
        assertTrue(msg.contains("field 'parameters' must be an object"), msg);
    }

    @Test
    void rejectsWrongTypeParameterValue() {
        String msg =
                message(
                        ManifestException.class,
                        """
                        {"schemaVersion":1,"program":"m.vmfb","parameters":{"model":7}}""");
        assertTrue(msg.contains("parameters.model"), msg);
    }

    @Test
    void emptyRequiresPasses() throws ManifestException {
        ModelManifest m =
                parse(
                        """
                        {"schemaVersion":1,"program":"m.vmfb","requires":{}}""");
        assertEquals("m.vmfb", m.program());
    }

    @Test
    void rejectsUnsatisfiedRequires() {
        String msg =
                message(
                        ManifestException.class,
                        """
                        {"schemaVersion":1,"program":"m.vmfb","requires":{"checksum":"sha256"}}""");
        assertTrue(msg.contains("'checksum'"), msg);
    }

    @Test
    void rejectsWrongTypeRequires() {
        String msg =
                message(
                        ManifestException.class,
                        """
                        {"schemaVersion":1,"program":"m.vmfb","requires":[1]}""");
        assertTrue(msg.contains("field 'requires' must be an object"), msg);
    }

    @Test
    void rejectsMalformedJson() {
        assertTrue(message(ManifestException.class, "{not json").contains("malformed manifest JSON"));
        assertTrue(message(ManifestException.class, "").contains("malformed manifest JSON"));
    }

    @Test
    void rejectsNonObjectDocument() {
        String msg = message(ManifestException.class, "[1,2]");
        assertTrue(msg.contains("malformed manifest JSON"), msg);
    }

    @Test
    void duplicateKeysLastWins() throws ManifestException {
        ModelManifest m =
                parse(
                        """
                        {"schemaVersion":1,"program":"a.vmfb","program":"b.vmfb"}""");
        assertEquals("b.vmfb", m.program());
    }

    @Test
    void errorMessagesCarrySourceLabel() {
        ManifestException e =
                assertThrows(
                        ManifestException.class,
                        () -> ModelManifest.parse("{\"program\":\"x\"}", "src/test/foo.json"));
        assertTrue(e.getMessage().startsWith("src/test/foo.json"), e.getMessage());
    }
}
