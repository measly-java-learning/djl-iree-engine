package org.measly.iree.engine;

import ai.djl.engine.Engine;
import ai.djl.engine.EngineProvider;

/** Registered via META-INF/services/ai.djl.engine.EngineProvider. */
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
