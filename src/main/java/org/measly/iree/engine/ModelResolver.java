package org.measly.iree.engine;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The filesystem half of manifest loading: the three front doors, relative-path
 * resolution, containment checking, and existence checks.
 *
 * <p>Per asset, in this order (the order is part of the design):
 * <ol>
 *   <li>resolve the relative path against the manifest document's directory
 *       (not {@code modelPath} — they differ whenever the caller passes an
 *       explicit manifest file);</li>
 *   <li>existence check — missing produces the forgot-to-unarchive message
 *       rather than a confusing realpath failure;</li>
 *   <li>{@code toRealPath()}, then containment via {@code startsWith} on the
 *       real path, never the string — a symlink escape is invisible in the
 *       manifest text;</li>
 *   <li>step 3 is skipped entirely when {@code allowUnsafePaths} is set.</li>
 * </ol>
 *
 * <p>Containment applies to the {@code .vmfb} and every archive — every asset
 * the manifest names — and is all-or-nothing per manifest. The flag lives on
 * the caller's load options and never in the manifest: a manifest that could
 * self-authorize escape defeats the control. All listed assets are checked
 * eagerly, and no size or content check exists anywhere: a zero-byte asset
 * passes and reaches IREE untouched.
 */
public final class ModelResolver {

    private static final String MANIFEST_FILE_NAME = "djl-iree-model.json";

    private ModelResolver() {}

    public static ResolvedModel resolve(Path modelPath, String prefix, IreeLoadOptions options)
            throws IOException {
        Path manifestFile;
        String sourceLabel;
        if (Files.isRegularFile(modelPath)) {
            manifestFile = modelPath;
            sourceLabel = modelPath.toString();
        } else if (Files.isDirectory(modelPath)) {
            Path json = modelPath.resolve(MANIFEST_FILE_NAME);
            if (Files.isRegularFile(json)) {
                manifestFile = json;
                sourceLabel = json.toString();
            } else {
                Path vmfb = modelPath.resolve(prefix + ".vmfb");
                if (Files.isRegularFile(vmfb)) {
                    // Implicit manifest: one downstream path for all three doors.
                    return resolveManifest(
                            new ModelManifest(1, prefix + ".vmfb", null, Map.of()),
                            modelPath, "<implicit manifest>", options);
                }
                throw new FileNotFoundException(modelPath + " contains neither " + MANIFEST_FILE_NAME
                        + " nor " + prefix + ".vmfb");
            }
        } else {
            throw new FileNotFoundException(
                    "modelPath is neither a regular file nor a directory: " + modelPath);
        }
        String text = Files.readString(manifestFile);
        ModelManifest manifest = ModelManifest.parse(text, sourceLabel);
        return resolveManifest(manifest, manifestFile.getParent(), sourceLabel, options);
    }

    private static ResolvedModel resolveManifest(ModelManifest manifest, Path anchor,
                                                 String sourceLabel, IreeLoadOptions options)
            throws IOException {
        Path vmfb = resolveAsset(anchor, manifest.program(), sourceLabel, options);
        List<ParameterBinding> bindings = new ArrayList<>(manifest.parameters().size());
        for (Map.Entry<String, String> e : manifest.parameters().entrySet()) {
            bindings.add(
                    new ParameterBinding(e.getKey(), resolveAsset(anchor, e.getValue(), sourceLabel, options)));
        }
        return new ResolvedModel(vmfb, bindings, manifest.entryPoint());
    }

    // Order is load-bearing (design): resolve -> existence -> realpath containment.
    private static Path resolveAsset(Path anchor, String relative, String sourceLabel,
                                     IreeLoadOptions options) throws IOException {
        Path resolved = anchor.resolve(relative); // absolute paths resolve to themselves; containment decides
        if (!Files.exists(resolved)) { // follows symlinks; a dangling symlink is "missing"
            throw new FileNotFoundException("Asset '" + relative + "' referenced by " + sourceLabel
                    + " is missing at " + resolved
                    + "; if the model was archived (zip/tar), unarchive it before loading");
        }
        if (!options.allowUnsafePaths()) {
            Path real = resolved.toRealPath();
            Path anchorReal = anchor.toRealPath();
            if (!real.startsWith(anchorReal)) {
                throw new ManifestException("Asset '" + relative + "' referenced by " + sourceLabel
                        + " resolves to " + real + ", which is outside the manifest directory "
                        + anchorReal + "; set the allowUnsafePaths load option to true to permit it");
            }
            return real; // pass the validated real path downstream (symlink-safe)
        }
        return resolved; // flag set: no realpath normalization (also avoids realpath failures on exotic mounts)
    }
}
