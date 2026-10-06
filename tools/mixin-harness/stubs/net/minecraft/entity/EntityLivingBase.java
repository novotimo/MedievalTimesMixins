package net.minecraft.entity;

import net.minecraft.world.World;

/** onUpdate reaches Entity.onUpdate through super, and the push loop calls this.collideWithEntity per neighbour, as in vanilla. */
public abstract class EntityLivingBase extends Entity {
    public EntityLivingBase(World w) { super(w); }

    public void onUpdate() { super.onUpdate(); onLivingUpdate(); }

    public void onLivingUpdate() { collideWithNearbyEntities(); }

    protected void collideWithNearbyEntities() {
        for (Entity neighbour : world.getEntitiesInAABBexcluding(this)) {
            this.collideWithEntity(neighbour);
        }
    }

    protected void collideWithEntity(Entity entityIn) { entityIn.applyEntityCollision(this); }
}
