package com.novotimo.mtmixins.util;

import java.io.File;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.novotimo.mtmixins.MedievalTimesMixins;

import org.spongepowered.asm.mixin.transformer.ClassInfo;

/**
 * Loads every class in mtmixins' own jar during pre-initialisation, while the jar on disk is still the one the
 * server started with, so that a new jar uploaded over it later cannot crash the running server.
 *
 * <h2>The fault</h2>
 *
 * <pre>
 * [2026-10-25 03:01:43] [Server thread/WARN] [net.minecraft.world.chunk.Chunk]: Wrong location! ... EntityLamprey[...]
 * java.lang.NoClassDefFoundError: com/novotimo/mtmixins/util/MisplacedEntities
 *     at net.minecraft.world.chunk.Chunk.handler$zgm000$mtmixins$noteMisplaced(Chunk.java:3316)
 * Caused by: java.lang.ClassNotFoundException: com.novotimo.mtmixins.util.MisplacedEntities
 *     at net.minecraft.launchwrapper.LaunchClassLoader.findClass(LaunchClassLoader.java:191)
 * Caused by: java.lang.NullPointerException
 * </pre>
 *
 * <p>The class was in the jar. The JVM reads a class out of its jar the first time it is used, and the helpers
 * here are first used whenever their event first happens: {@code MisplacedEntities} on the first entity loaded
 * into the wrong chunk, 23 hours into that run. A new mtmixins jar had been built at 01:53 UTC and was on prod by
 * then, written over the file the running server had open since its 04:00 start. {@code LaunchClassLoader} then
 * looked the class up in the old jar's index and got nothing back from the new file, and the server stopped.
 *
 * <h2>The fix</h2>
 *
 * <p>Read them all now. Mixins themselves are read by Mixin at startup and kept in memory; what is left are the
 * classes our mixins call ({@code util}, {@code bridge}, {@code structure}) and the accessor interfaces, which the
 * JVM loads when their target class loads, possibly long after startup (MFR's {@code ContainerCrucible} on the
 * first crucible opened). Only accessors Mixin has registered as loadable are touched; loading any other class in
 * the mixin package is an error, and an accessor of a config that is not active (client side, or switched off in
 * {@code mixinbooter.cfg}) is not registered.
 *
 * <p>{@code Class.forName(name, false, ...)}: loaded but not initialised, so the bridges' static initialisers still
 * resolve Civilizations and Ice and Fire when first used, as before. None of these classes has a supertype from
 * another mod, so loading one loads nothing else early. Fails soft: a class that will not load is logged and
 * skipped, and anything else wrong only costs the protection.
 */
public final class OwnClasses {

    private static final String ROOT = "com/novotimo/mtmixins/";

    private static final String MIXIN_PACKAGE = ROOT + "mixin/";

    private OwnClasses() {
    }

    /** {@code source} is the mod's jar, from {@code FMLPreInitializationEvent.getSourceFile()}. */
    public static void preload(File source) {
        if (source == null || !source.isFile()) {
            MedievalTimesMixins.LOG.debug("Not preloading mtmixins' classes: running from {}, not a jar", source);
            return;
        }
        ClassLoader loader = OwnClasses.class.getClassLoader();
        int loaded = 0;
        int failed = 0;
        try (JarFile jar = new JarFile(source)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String path = entries.nextElement().getName();
                if (!path.startsWith(ROOT) || !path.endsWith(".class")) {
                    continue;
                }
                String internalName = path.substring(0, path.length() - ".class".length());
                if (internalName.startsWith(MIXIN_PACKAGE) && !loadableMixin(internalName)) {
                    continue;
                }
                try {
                    Class.forName(internalName.replace('/', '.'), false, loader);
                    loaded++;
                } catch (Throwable t) {
                    failed++;
                    MedievalTimesMixins.LOG.warn("Could not preload {}: {}", internalName, t.toString());
                }
            }
        } catch (Throwable t) {
            MedievalTimesMixins.LOG.warn("Could not preload mtmixins' classes from {}; replacing that jar while the server runs"
                    + " can crash it", source, t);
            return;
        }
        MedievalTimesMixins.LOG.info("Preloaded {} mtmixins classes ({} failed), so a jar replaced under the running server"
                + " cannot break them", loaded, failed);
    }

    private static boolean loadableMixin(String internalName) {
        ClassInfo info = ClassInfo.fromCache(internalName);
        return info != null && info.isLoadable();
    }
}
