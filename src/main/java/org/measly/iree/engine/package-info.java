/**
 * A DJL engine that runs IREE {@code .vmfb} programs.
 *
 * <p>DJL discovers this engine through {@link org.measly.iree.engine.IreeEngineProvider};
 * callers normally touch it only through DJL's own {@code Model}, {@code Predictor}, and
 * {@code NDManager} interfaces. The types here are the ones worth knowing about directly:
 *
 * <ul>
 *   <li>{@link org.measly.iree.engine.ModelResolver} — what {@code Model.load} accepts: a
 *       manifest file, a directory holding one, or a bare {@code .vmfb}.
 *   <li>{@link org.measly.iree.engine.ModelManifest} — the manifest schema, which names the
 *       program and binds {@code .irpa} parameter archives to runtime scopes.
 *   <li>{@link org.measly.iree.engine.IreeLoadOptions} — the load options DJL passes through,
 *       and their precedence against the manifest.
 *   <li>{@link org.measly.iree.engine.IreeEngineStats} — always-on observability: engine
 *       configuration, process totals, and per-model counters, exposed over JMX.
 * </ul>
 *
 * <p>The native contract these types sit on lives in {@link org.measly.iree.jni}, which is
 * internal and not part of the supported API.
 */
package org.measly.iree.engine;
