package org.measly.iree.engine;

import ai.djl.engine.Engine;
import ai.djl.engine.EngineProvider;

/**
 * DJL's discovery entry point for this engine, registered via {@code
 * META-INF/services/ai.djl.engine.EngineProvider} and found through {@code ServiceLoader}.
 *
 * <p>{@link #getEngine()} lazily creates and caches a single {@link IreeEngine} instance
 * (double-checked locking on the class), which is what triggers the native library load — the
 * engine is otherwise inert until first requested.
 */
public class IreeEngineProvider implements EngineProvider {

    private static volatile Engine engine;

    @Override
    public String getEngineName() {
        return IreeEngine.ENGINE_NAME;
    }

    @Override
    public int getEngineRank() {
        return IreeEngine.RANK;
    }

    @Override
    public Engine getEngine() {
        if (engine == null) {
            synchronized (IreeEngineProvider.class) {
                if (engine == null) {
                    engine = IreeEngine.newInstance();
                }
            }
        }
        return engine;
    }
}
