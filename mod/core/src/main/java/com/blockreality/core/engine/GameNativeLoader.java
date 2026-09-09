package com.blockreality.core.engine;

import com.blockreality.api.AnalysisResult;
import com.blockreality.core.bsi.BsiHeaders;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Lazy game-side library discovery and vocabulary handshake. Invoke only on the analysis worker. */
public final class GameNativeLoader {
    private GameNativeLoader() { }

    public static NativeGameRuntime.Session open(String configured, Path root, int threads, Consumer<String> log) {
        EngineLocator.Located located = EngineLocator.locateFromSystem(configured, root, null);
        if (located.source() == EngineLocator.Source.NONE) {
            Path bundled = BundledNatives.ensure(root, BundledNatives.class::getResourceAsStream,
                    System.getProperty("os.name"), System.getProperty("os.arch"), log).orElse(null);
            located = EngineLocator.locate(null, null, null, null, bundled);
        }
        if (located.path() == null) throw new IllegalStateException("no native engine for " + EngineLocator.platform());
        final String sha256;
        try { sha256 = BundledNatives.sha256(located.path()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException("cannot fingerprint native engine", e); }
        log.accept("native engine source=" + located.source() + " path=" + located.path().toAbsolutePath()
                + " fileSha256=" + sha256);
        InProcessEngine engine = InProcessEngine.open(located.path(), threads);
        try {
            if (engine.status() != InProcessEngine.Status.READY)
                throw new IllegalStateException("native handshake refused: " + engine.disabledReason());
            if (!engine.declareVocabulary(GameVocabulary.declaration()))
                throw new IllegalStateException("native game vocabulary refused: " + engine.disabledReason());
            log.accept("native engine READY: " + engine.engineName() + " " + engine.engineVersion()
                    + " contract=" + com.blockreality.core.bsi.BsiContract.sha256());
            return new NativeGameRuntime.Session() {
                @Override public AnalysisResult analyze(GameWorldSnapshot world, Integer count, BsiHeaders.EigenBuckling buckling) {
                    return engine.analyze(world, count, BsiHeaders.Storage.F64, buckling);
                }
                @Override public boolean ready() { return engine.status() == InProcessEngine.Status.READY; }
                @Override public void close() { engine.close(); }
            };
        } catch (RuntimeException | LinkageError e) { engine.close(); throw e; }
    }
}
