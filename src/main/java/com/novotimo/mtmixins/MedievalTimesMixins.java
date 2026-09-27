package com.novotimo.mtmixins;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Container mod. The mixins themselves are registered from the {@code MixinConfigs}
 * manifest attribute, not from here, so this class exists only to give Forge something
 * to list and to declare the MixinBooter dependency.
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
    public static final String VERSION = "0.1.0";

    public static final Logger LOG = LogManager.getLogger(NAME);

    private MedievalTimesMixins() {
    }
}
