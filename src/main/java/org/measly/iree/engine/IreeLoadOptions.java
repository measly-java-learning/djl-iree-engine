package org.measly.iree.engine;

import java.util.Map;

/**
 * The {@code Model.load} options that this engine consumes, with defaults.
 * The single home of option-key names and defaults.
 *
 * @param entryPoint entry-point override, applied on top of the manifest
 *     value; null means "let the manifest (or the {@code module.main}
 *     default) decide"
 * @param device the IREE HAL driver; option-only, never read from a manifest
 * @param allowUnsafePaths permit assets that resolve outside the manifest
 *     directory; option-only, never read from a manifest
 */
public record IreeLoadOptions(String entryPoint, String device, boolean allowUnsafePaths) {

    public static IreeLoadOptions from(Map<String, ?> options) {
        if (options == null) {
            return new IreeLoadOptions(null, "local-sync", false);
        }
        String entryPoint =
                options.get("entryPoint") != null ? String.valueOf(options.get("entryPoint")) : null;
        String device = options.get("device") != null ? String.valueOf(options.get("device")) : "local-sync";
        boolean allowUnsafePaths = Boolean.parseBoolean(String.valueOf(options.get("allowUnsafePaths")));
        return new IreeLoadOptions(entryPoint, device, allowUnsafePaths);
    }
}
