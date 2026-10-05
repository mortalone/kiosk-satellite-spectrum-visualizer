package me.jxl.kiosk.plugins.spectrumvisualizer;

import java.util.concurrent.CopyOnWriteArrayList;

/** Fixtures match retained registries when release shrinking drops app holder fields. */
public final class AudioRegistrySnapshotTest {
    private static final class Source {
        int unreadSamples = 1024;
        Object take() { unreadSamples = 0; throw new AssertionError("Must not consume echo data"); }
    }
    private static final class StaticRegistry {
        private static final CopyOnWriteArrayList<Source> sources = new CopyOnWriteArrayList<>();
    }
    private static final class InstanceRegistry {
        private static final InstanceRegistry INSTANCE = new InstanceRegistry();
        private final CopyOnWriteArrayList<Source> sources = new CopyOnWriteArrayList<>();
    }
    public static void main(String[] args) throws Exception {
        Source source = new Source();
        StaticRegistry.sources.add(source);
        Object live = AudioRegistrySnapshot.readSources(StaticRegistry.class);
        if (live != StaticRegistry.sources || ((Iterable<?>) live).iterator().next() != source)
            throw new AssertionError("Must preserve the live registry and source identities");
        if (source.unreadSamples != 1024) throw new AssertionError("Audio was consumed");
        StaticRegistry.sources.clear();
        if (((CopyOnWriteArrayList<?>) live).size() != 0)
            throw new AssertionError("Removed sources must disappear from the registry");
        if (AudioRegistrySnapshot.readSources(InstanceRegistry.class) != InstanceRegistry.INSTANCE.sources)
            throw new AssertionError("Instance-backed registry not supported");
        System.out.println("Audio registry regression checks passed");
    }
}
