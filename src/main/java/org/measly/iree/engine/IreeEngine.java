package org.measly.iree.engine;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.engine.Engine;
import ai.djl.ndarray.NDManager;
import ai.djl.nn.SymbolBlock;
import ai.djl.training.GradientCollector;

/** DJL engine backed by the IREE runtime. CPU only, inference only. */
public final class IreeEngine extends Engine {

    public static final String ENGINE_NAME = "IREE";
    static final int RANK = 10;

    private IreeEngine() {}

    static Engine newInstance() {
        LibUtils.loadLibrary();
        return new IreeEngine();
    }

    @Override
    public Engine getAlternativeEngine() {
        return null;
    }

    @Override
    public String getEngineName() {
        return ENGINE_NAME;
    }

    @Override
    public int getRank() {
        return RANK;
    }

    @Override
    public String getVersion() {
        return "0.1.0-SNAPSHOT";
    }

    @Override
    public boolean hasCapability(String capability) {
        return false;
    }

    @Override
    public Model newModel(String name, Device device) {
        return new IreeModel(name, newBaseManager(device));
    }

    @Override
    public SymbolBlock newSymbolBlock(NDManager manager) {
        throw new UnsupportedOperationException(
                "IREE models are loaded from .vmfb; build a block via Model.load");
    }

    @Override
    public NDManager newBaseManager() {
        return newBaseManager(null);
    }

    @Override
    public NDManager newBaseManager(Device device) {
        return IreeNDManager.getSystemManager().newSubManager(device);
    }

    @Override
    public GradientCollector newGradientCollector() {
        throw new UnsupportedOperationException("IREE engine is inference-only");
    }

    @Override
    public void setRandomSeed(int seed) {
        throw new UnsupportedOperationException("IREE engine is inference-only");
    }
}
