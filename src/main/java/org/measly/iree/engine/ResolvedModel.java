package org.measly.iree.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * The resolved model artifact: everything {@code IreeModel.load} needs after
 * manifest parsing and path resolution. {@code entryPoint} is the manifest's
 * value and may be null; precedence (option &gt; manifest &gt; default) is
 * applied by {@code IreeModel}, not here.
 */
public record ResolvedModel(Path vmfb, List<ParameterBinding> parameters, String entryPoint) {}
