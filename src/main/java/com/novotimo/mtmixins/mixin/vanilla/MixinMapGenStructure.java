package com.novotimo.mtmixins.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.novotimo.mtmixins.structure.MineshaftStarts;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraft.world.gen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Register B7 &mdash; BetterCaves makes every saved mineshaft unreadable. This is the read side of the
 * fix; {@code structure/BetterMineshaftStart} is the object it produces.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code MapGenBetterMineshaft.getStructureStart(int, int)} opens with, as its literal first
 * instruction:
 *
 * <pre>
 * MapGenStructureIO.registerStructure(StructureBetterMineshaftStart.class, "Mineshaft");
 * </pre>
 *
 * <p>a global static side effect in a method called once per mineshaft placed. The class it binds
 * cannot be deserialised: {@code StructureBetterMineshaftStart} is a non-static inner class whose only
 * constructor is {@code (MapGenBetterMineshaft, World, Random, int, int, MapGenMineshaft$Type)} and
 * generates a whole mineshaft. Vanilla reads saved structures with {@code Class.newInstance()}, so
 * every read throws {@code NoSuchMethodException: ...StructureBetterMineshaftStart.<init>()} and
 * vanilla logs {@code Skipping Structure with id Mineshaft}.
 *
 * <p>It does not fail everywhere, which is why this took a while to pin down. The registry is static
 * and global; {@code initializeStructureData} is per generator instance and lazy. {@code
 * recursiveGenerate} calls it <em>before</em> anything reaches {@code getStructureStart}, so a
 * generator whose first use is terrain generation loads cleanly and only poisons the registry
 * afterwards. {@code generateStructure} also calls it, with nothing before it. BetterCaves handles only
 * whitelisted dimensions and delegates the rest to a stock {@code MapGenMineshaft}, so outside the
 * whitelist its generator can only ever reach the load through population &mdash; by which time the
 * whitelisted dimension has poisoned the shared registry.
 *
 * <h2>Why here</h2>
 *
 * <p>{@code MapGenStructureIO.getStructureStart(NBTTagCompound, World)} has exactly one call site in
 * the whole of Minecraft: this method. Wrapping it is therefore complete coverage, and it covers every
 * generator that reads the shared {@code Mineshaft.dat} &mdash; both BetterCaves' generator and the
 * stock one it delegates to, which is its own separate {@code MapGenStructure} instance and reads the
 * same file.
 *
 * <p>Note what this does <b>not</b> do: it does not touch BetterCaves' registration. That registration
 * is what puts {@code StructureBetterMineshaftStart} in the registry's class-to-name map, and
 * <em>newly created</em> starts are still that class, so removing it would make
 * {@code getStructureStartName} return null for them and {@code NBTTagString}'s
 * {@code requireNonNull} would take the server down the first time a mineshaft was saved. Only the
 * read side is broken, so only the read side is patched.
 *
 * <p>BetterCaves 1.12.2 is long abandoned upstream, so there is no fix to wait for.
 */
@Mixin(MapGenStructure.class)
public abstract class MixinMapGenStructure {

    /**
     * No {@code @Pseudo} and no dual method names here: {@code MapGenStructure} is a vanilla class on
     * the compile classpath, so the annotation processor resolves both the method and the {@code @At}
     * target and writes them into the refmap. {@code initializeStructureData} has no overloads, so the
     * bare name is unambiguous.
     */
    @WrapOperation(
            method = "initializeStructureData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/structure/MapGenStructureIO;getStructureStart"
                            + "(Lnet/minecraft/nbt/NBTTagCompound;Lnet/minecraft/world/World;)"
                            + "Lnet/minecraft/world/gen/structure/StructureStart;"
            )
    )
    private StructureStart mtmixins$loadMineshaftsBetterCavesMadeUnreadable(
            NBTTagCompound tag, World world, Operation<StructureStart> original) {
        StructureStart start = MineshaftStarts.create(tag, world);
        // Null means "not our problem": not a mineshaft, or BetterCaves is not installed and vanilla's
        // own binding is intact. Everything else in the file - villages, strongholds, monuments - goes
        // straight through untouched.
        return start != null ? start : original.call(tag, world);
    }
}
