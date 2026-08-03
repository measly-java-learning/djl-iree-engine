package org.measly.iree.engine;

import ai.djl.BaseModel;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.measly.iree.jni.IreeNative;

/** Loads a .vmfb (optionally with scope-bound .irpa parameter archives) and owns the native handle. */
public class IreeModel extends BaseModel {

    /**
     * IREE prefixes exported functions with the module name. Confirmed against
     * add.vmfb with iree-dump-module — see tools/export_add.sh.
     */
    private static final String DEFAULT_ENTRY_POINT = "module.main";

    IreeModel(String name, NDManager manager) {
        super(name);
        this.manager = manager;
        this.manager.setName("ireeModel");
        this.dataType = DataType.FLOAT32;
    }

    @Override
    public void load(Path modelPath, String prefix, Map<String, ?> options)
            throws IOException {
        setModelDir(modelPath);
        if (prefix == null) {
            prefix = modelName;
        }
        IreeLoadOptions opts = IreeLoadOptions.from(options);
        ResolvedModel resolved = ModelResolver.resolve(modelPath, prefix, opts);

        // entryPoint precedence: option > manifest > default.
        String entryPoint =
                opts.entryPoint() != null
                        ? opts.entryPoint()
                        : resolved.entryPoint() != null ? resolved.entryPoint() : DEFAULT_ENTRY_POINT;

        byte[] bytes = Files.readAllBytes(resolved.vmfb());

        List<ParameterBinding> params = resolved.parameters();
        final int count = params.size();
        String[] scopes = new String[count];
        String[] paths = new String[count];
        int i = 0;
        for (ParameterBinding binding : params) {
            scopes[i] = binding.scope();
            paths[i] = binding.path().toString();
            i++;
        }

        long handle = IreeNative.load(bytes, entryPoint, opts.device(), scopes, paths);
        block = new IreeSymbolBlock((IreeNDManager) manager, handle);
    }

    @Override
    public void close() {
        if (block instanceof IreeSymbolBlock symbolBlock) {
            symbolBlock.close();
        }
        super.close();
    }
}
