package com.novotimo.mtmixins.structure;

import com.novotimo.mtmixins.bridge.BetterCavesBridge;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;
import net.minecraft.world.gen.structure.StructureMineshaftStart;

import java.util.Random;

/**
 * A mineshaft start that behaves like BetterCaves' one and can actually be read back off disk.
 *
 * <p>BetterCaves' own {@code MapGenBetterMineshaft$StructureBetterMineshaftStart} is a non-static
 * inner class whose only constructor generates a whole mineshaft, so it has no no-arg constructor and
 * vanilla's {@code Class.newInstance()} deserialiser can never build one. This class exists to be the
 * thing vanilla builds instead: same superclass, same behaviour, public no-arg constructor.
 *
 * <p>The only thing BetterCaves' subclass adds is one extra rule in {@code generateStructure}, and
 * {@link #generateStructure} below reproduces it exactly &mdash; see the javadoc there for the
 * bytecode it was read from. Because that rule reads only an {@code int} out of BetterCaves' config,
 * this class needs no reference to a {@code MapGenBetterMineshaft} at all, which is what makes a plain
 * subclass sufficient and keeps {@code Unsafe} out of it.
 *
 * <p>Instances are only ever created by {@link MineshaftStarts}, which registers this class with
 * {@code MapGenStructureIO} under {@code "Mineshaft"} so that they can be written back out again.
 */
public class BetterMineshaftStart extends StructureMineshaftStart {

    /**
     * Required, and the entire point of this class. {@code StructureStart()} is what initialises
     * {@code components} to a mutable {@code LinkedList}, which the {@code removeIf} below needs.
     */
    public BetterMineshaftStart() {
        super();
    }

    /**
     * BetterCaves' override, reproduced. From
     * {@code MapGenBetterMineshaft$StructureBetterMineshaftStart.func_75068_a} and its
     * {@code lambda$generateStructure$0}:
     *
     * <pre>
     * components.removeIf(c -&gt; {
     *     if (c.getBoundingBox().minY &lt; this$0.liquidAltitude + 5) return true;
     *     if (c.getBoundingBox().intersectsWith(box) &amp;&amp; !c.addComponentParts(world, rand, box)) return true;
     *     return false;
     * });
     * </pre>
     *
     * <p>Vanilla {@code StructureStart.generateStructure} is the same loop without the first clause:
     * build any component that intersects the chunk being populated, and drop the ones that report
     * they are finished. BetterCaves adds "and drop anything that sits below the liquid altitude",
     * which is what stops mineshafts generating inside its flooded caverns. Everything else about it
     * is vanilla, which is why this is three lines rather than a fork.
     *
     * <p>If the altitude cannot be determined the whole rule is skipped and vanilla's loop runs. That
     * is the one safe fallback: defaulting the altitude to zero would delete every component below
     * y=5, and defaulting it low enough to never match would be a lie about which mod is in charge.
     */
    @Override
    public void generateStructure(World world, Random rand, StructureBoundingBox structurebb) {
        Integer liquidAltitude = BetterCavesBridge.liquidAltitudeFor(world);
        if (liquidAltitude == null) {
            super.generateStructure(world, rand, structurebb);
            return;
        }

        final int floor = liquidAltitude + 5;
        this.components.removeIf(component -> isBelowFloor(component, floor)
                || isFinished(component, world, rand, structurebb));
    }

    private static boolean isBelowFloor(StructureComponent component, int floor) {
        return component.getBoundingBox().minY < floor;
    }

    private static boolean isFinished(StructureComponent component, World world, Random rand,
                                      StructureBoundingBox structurebb) {
        return component.getBoundingBox().intersectsWith(structurebb)
                && !component.addComponentParts(world, rand, structurebb);
    }
}
