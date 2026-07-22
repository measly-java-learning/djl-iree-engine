package org.measly.iree.engine;

import ai.djl.BaseModel;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.measly.iree.jni.IreeNative;

/** Loads a .vmfb and owns the native handle. */
public class IreeModel extends BaseModel {

    /**
     * IREE prefixes exported functions with the module name. Confirmed against
     * add.vmfb with iree-dump-module — see tools/export_add.sh.
     */
    private static final String DEFAULT_ENTRY_POINT = "module.main";

    /** IREE driver. "local-sync" (default, single-threaded) or "local-task" (worker pool). */
    private static final String DEFAULT_DEVICE = "local-sync";

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
        Path file = modelDir.resolve(prefix + ".vmfb");
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("No .vmfb found at " + file);
        }

        String entryPoint = DEFAULT_ENTRY_POINT;
        if (options != null && options.get("entryPoint") != null) {
            entryPoint = options.get("entryPoint").toString();
        }

        String device = DEFAULT_DEVICE;
        if (options != null && options.get("device") != null) {
            device = options.get("device").toString();
        }

        byte[] bytes = Files.readAllBytes(file);
        long handle = IreeNative.load(bytes, entryPoint, device);
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
