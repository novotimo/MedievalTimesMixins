package com.novotimo.mtmixins;

import com.novotimo.mtmixins.util.OwnClasses;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Container mod. The mixins themselves are registered from the {@code MixinConfigs}
 * manifest attribute, not from here, so this class exists to give Forge something
 * to list, to declare the MixinBooter dependency, and to preload the jar's classes
 * ({@link OwnClasses}) so a jar uploaded over the running one cannot crash it.
 */
@Mod(
        modid = MedievalTimesMixins.MOD_ID,
        name = MedievalTimesMixins.NAME,
        version = MedievalTimesMixins.VERSION,
        dependencies = "required-after:mixinbooter@[11.0,)",
        // This mod registers no blocks, items or entities, so a version difference
        // between client and server cannot desync anything. Accepting any remote
        // version avoids adding one more way for players to fail the handshake.
        acceptableRemoteVersions = "*"
)
public final class MedievalTimesMixins {

    public static final String MOD_ID = "mtmixins";
    public static final String NAME = "Medieval Times Mixins";
    /**
     * Must match {@code mod_version} in gradle.properties. FML takes the version from this annotation
     * value, not from mcmod.info, so this is what the mod list, the handshake and crash reports show.
     */
    public static final String VERSION = "0.3.0";

    public static final Logger LOG = LogManager.getLogger(NAME);

    // No private constructor here, tempting as it is. FML constructs the @Mod class
    // reflectively via Class.newInstance() in ILanguageAdapter$JavaAdapter, so it needs an
    // accessible no-arg constructor or mod loading dies with:
    //   IllegalAccessException: ... can not access a member of ... with modifiers "private"
    public MedievalTimesMixins() {
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        OwnClasses.preload(event.getSourceFile());
    }
}
