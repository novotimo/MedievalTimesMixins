package com.novotimo.mtmixins.structure;

import com.novotimo.mtmixins.MedievalTimesMixins;
import com.novotimo.mtmixins.bridge.BetterCavesBridge;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.MapGenStructureIO;
import net.minecraft.world.gen.structure.StructureMineshaftStart;
import net.minecraft.world.gen.structure.StructureStart;

/**
 * Builds a mineshaft {@link StructureStart} from saved NBT, for the one case vanilla cannot:
 * BetterCaves has bound the id {@code "Mineshaft"} to a class that {@code Class.newInstance()} cannot
 * construct. Called from {@code MixinMapGenStructure}.
 */
public final class MineshaftStarts {

    private static final String MINESHAFT_ID = "Mineshaft";

    private static volatile boolean registered = false;
    private static volatile boolean loggedFirstLoad = false;

    private MineshaftStarts() {
    }

    /**
     * @return a populated start, or null to let vanilla's own deserialiser handle this tag. Null for
     *         anything that is not a mineshaft, and null when BetterCaves is absent &mdash; in that
     *         case vanilla's registration is intact and there is nothing to fix.
     */
    public static StructureStart create(NBTTagCompound tag, World world) {
        if (!MINESHAFT_ID.equals(tag.getString("id")) || !BetterCavesBridge.isPresent()) {
            return null;
        }

        ensureRegistered();

        // Decided per dimension, not per generator. In a dimension BetterCaves does not handle it
        // delegates terrain generation to a stock MapGenMineshaft, so the starts there were written
        // by vanilla and must come back as vanilla: BetterCaves' liquid-altitude rule would truncate
        // them for no reason, since nothing flooded those caves.
        final boolean betterCaves = BetterCavesBridge.isActiveFor(world);
        StructureStart start = betterCaves ? new BetterMineshaftStart() : new StructureMineshaftStart();

        // What MapGenStructureIO.getStructureStart would have done next, had it managed to construct
        // anything. Reads ChunkX/ChunkZ, the bounding box and every child component.
        start.readStructureComponentsFromNBT(world, tag);

        if (!loggedFirstLoad) {
            loggedFirstLoad = true;
            MedievalTimesMixins.LOG.info(
                    "Loading saved mineshafts as {} (BetterCaves {} for dim {}). Without this they "
                            + "would all be skipped, because BetterCaves registers a mineshaft class "
                            + "that has no no-arg constructor.",
                    start.getClass().getSimpleName(),
                    betterCaves ? "active" : "inactive",
                    world.provider.getDimension());
        }

        return start;
    }

    /**
     * Teaches {@code MapGenStructureIO} the name for {@link BetterMineshaftStart}, so that a start
     * loaded through here can be written back out: {@code writeStructureComponentsToNBT} does
     * {@code setString("id", getStructureStartName(this))}, that lookup is a plain nullable map get on
     * the class, and {@code NBTTagString} does {@code requireNonNull}. An unregistered start class is
     * therefore not a silent no-op, it is a crash the next time the chunk is saved.
     *
     * <p>{@code registerStructure} writes both of the registry's maps, so this also points
     * {@code "Mineshaft"} at this class. That is harmless either way: BetterCaves re-registers its own
     * class on every mineshaft it places, so in practice its binding wins again immediately, and the
     * read path is intercepted before the binding is consulted. If this one did somehow stick, vanilla
     * would deserialise into this class unaided, which is also correct.
     *
     * <p>Deliberately not done at mod init: it has to happen after {@code MapGenStructureIO}'s own
     * static initialiser, and doing it lazily on first use guarantees that without depending on load
     * order.
     */
    private static void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        MapGenStructureIO.registerStructure(BetterMineshaftStart.class, MINESHAFT_ID);
    }
}
