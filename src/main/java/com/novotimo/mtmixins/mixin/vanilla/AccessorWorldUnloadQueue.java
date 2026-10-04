package com.novotimo.mtmixins.mixin.vanilla;

import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches {@code World.unloadedEntityList} ({@code field_72997_g}) from register B15.
 *
 * <p>A separate accessor rather than a {@code @Shadow} on the {@code WorldServer} mixin, because the
 * field is declared on {@code World} and the annotation processor will not resolve an inherited
 * field against a subclass target &mdash; it compiles with "Cannot find target for @Shadow field"
 * and then writes no obfuscation mapping, so the shadow would look fine at build time and fail to
 * bind on a production server. An {@code @Accessor} against the declaring class is checked at build
 * time and remapped properly.
 *
 * <p>What B15 needs it for: an entity queued for unload stays in {@code entitiesByUuid} until
 * {@code World.updateEntities} drains this list at the end of the tick, so a chunk that unloads and
 * reloads inside one tick produces an entity that appears to be a duplicate of itself. Vanilla
 * checks this list before calling anything a duplicate; B15 did not, and deleted 31 things players
 * had placed.
 */
@Mixin(World.class)
public interface AccessorWorldUnloadQueue {

    @Accessor("unloadedEntityList")
    List<Entity> mtmixins$unloadedEntityList();
}
