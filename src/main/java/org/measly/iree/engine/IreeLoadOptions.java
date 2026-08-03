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

    private static final String KEY_ENTRY_POINT = "entryPoint";
    private static final String KEY_DEVICE = "device";
    private static final String KEY_ALLOW_UNSAFE_PATHS = "allowUnsafePaths";
    private static final String DEFAULT_DEVICE = "local-sync";

    public static IreeLoadOptions from(Map<String, ?> options) {
        if (options == null) {
            return new IreeLoadOptions(null, DEFAULT_DEVICE, false);
        }
        Object entryPointValue = options.get(KEY_ENTRY_POINT);
        Object deviceValue = options.get(KEY_DEVICE);
        String entryPoint = entryPointValue != null ? String.valueOf(entryPointValue) : null;
        String device = deviceValue != null ? String.valueOf(deviceValue) : DEFAULT_DEVICE;
        boolean allowUnsafePaths =
                Boolean.parseBoolean(String.valueOf(options.get(KEY_ALLOW_UNSAFE_PATHS)));
        return new IreeLoadOptions(entryPoint, device, allowUnsafePaths);
    }
}
