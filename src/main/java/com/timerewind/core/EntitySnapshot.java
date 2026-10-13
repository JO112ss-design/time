package com.timerewind.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 1tick分のエンティティ状態（軽量）。消滅したエンティティの完全なNBTは別途 RemovedEntity に保存する。 */
public record EntitySnapshot(
        double x, double y, double z,
        float yRot, float xRot, float yHeadRot,
        Vec3 motion, float fallDistance, int fireTicks,
        float health,      // LivingEntity 以外は NaN
        ItemStack item,    // ItemEntity 以外は null
        int fuse           // PrimedTnt 以外は -1
) {

    public static EntitySnapshot capture(Entity e) {
        float health = e instanceof LivingEntity le ? le.getHealth() : Float.NaN;
        ItemStack item = e instanceof ItemEntity ie ? ie.getItem().copy() : null;
        int fuse = e instanceof PrimedTnt tnt ? tnt.getFuse() : -1;
        return new EntitySnapshot(
                e.getX(), e.getY(), e.getZ(),
                e.getYRot(), e.getXRot(), e.getYHeadRot(),
                e.getDeltaMovement(), e.fallDistance, e.getRemainingFireTicks(),
                health, item, fuse);
    }

    /** 復活させる前のNBTを、この時点の内容に合わせて補正する。 */
    public void patchTag(CompoundTag tag) {
        if (item != null && !item.isEmpty()) {
            tag.put("Item", item.save(new CompoundTag()));
        }
    }

    public void apply(Entity e, boolean freshlyRespawned) {
        boolean fixedPosition = e instanceof HangingEntity;
        // 生存中の飛翔体は位置を戻すと「刺さった状態」とずれるので、位置は触らない
        boolean skipMotion = e instanceof Projectile && !freshlyRespawned;

        if (!fixedPosition && !skipMotion) {
            e.moveTo(x, y, z, yRot, xRot);
            e.setYHeadRot(yHeadRot);
            e.setDeltaMovement(motion);
            e.hurtMarked = true;
        }
        e.fallDistance = fallDistance;
        e.setRemainingFireTicks(fireTicks);

        if (e instanceof LivingEntity le && !Float.isNaN(health)) {
            le.setHealth(health);
            if (freshlyRespawned) {
                le.deathTime = 0;
                le.hurtTime = 0;
            }
        }
        if (e instanceof ItemEntity ie && item != null && !item.isEmpty()) {
            ie.setItem(item.copy());
        }
        if (e instanceof PrimedTnt tnt && fuse >= 0) {
            tnt.setFuse(fuse);
        }
        if (e instanceof Mob mob) {
            mob.getNavigation().stop();
        }
    }
}
