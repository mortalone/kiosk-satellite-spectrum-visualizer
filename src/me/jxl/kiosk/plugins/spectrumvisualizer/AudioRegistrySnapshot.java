package me.jxl.kiosk.plugins.spectrumvisualizer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Read a Kotlin object's live registry without taking audio from its consumers. */
final class AudioRegistrySnapshot {
    static Object readSources(Class<?> registry) throws ReflectiveOperationException {
        Field sources = registry.getDeclaredField("sources");
        sources.setAccessible(true);
        if (Modifier.isStatic(sources.getModifiers())) return sources.get(null);
        Field singleton = registry.getDeclaredField("INSTANCE");
        singleton.setAccessible(true);
        return sources.get(singleton.get(null));
    }
}
