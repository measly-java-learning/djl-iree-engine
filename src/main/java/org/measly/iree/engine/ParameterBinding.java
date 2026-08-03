package org.measly.iree.engine;

import java.nio.file.Path;

/**
 * One parameter archive bound to a runtime scope name. The scope is what the
 * compiled program references (e.g. {@code "model"} for
 * {@code #stream.parameter.named<"model"::"weight">}); an empty scope binds
 * the archive's global scope.
 */
public record ParameterBinding(String scope, Path path) {}
