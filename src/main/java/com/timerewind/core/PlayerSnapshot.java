package com.timerewind.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** 1tick分のプレイヤー状態。 */
public record PlayerSnapshot(
        long tick,
        ResourceKey<Level> dimension,
        double x, double y, double z,
        float yRot, float xRot, float yHeadRot,
        Vec3 motion, float fallDistance, boolean fallFlying,
        float health, float absorption,
        int food, float saturation,
        int fireTicks, int air, int frozenTicks,
        List<CompoundTag> effects,
        ListTag inventory, int selectedSlot,
        int xpLevel, float xpProgress, int xpTotal
) {

    public static PlayerSnapshot capture(ServerPlayer p, long tick, boolean withInventory) {
        List<CompoundTag> effects = new ArrayList<>();
        for (MobEffectInstance e : p.getActiveEffects()) {
            effects.add(e.save(new CompoundTag()));
        }
        ListTag inv = withInventory ? p.getInventory().save(new ListTag()) : null;
        FoodData food = p.getFoodData();
        return new PlayerSnapshot(
                tick,
                p.level().dimension(),
                p.getX(), p.getY(), p.getZ(),
                p.getYRot(), p.getXRot(), p.getYHeadRot(),
                p.getDeltaMovement(), p.fallDistance, p.isFallFlying(),
                p.getHealth(), p.getAbsorptionAmount(),
                food.getFoodLevel(), food.getSaturationLevel(),
                p.getRemainingFireTicks(), p.getAirSupply(), p.getTicksFrozen(),
                effects,
                inv, p.getInventory().selected,
                p.experienceLevel, p.experienceProgress, p.totalExperience
        );
    }

    public Vec3 position() {
        return new Vec3(x, y, z);
    }

    /** このスナップショットの状態をプレイヤーに書き戻す。 */
    public void restore(ServerPlayer p, MinecraftServer server) {
        if (p.isPassenger()) p.stopRiding();
        if (p.isSleeping()) p.stopSleeping();

        // 開いているGUIを閉じる（カーソルに持っているアイテムの複製を防ぐ）
        if (inventory != null) p.closeContainer();

        ServerLevel dest = server.getLevel(dimension);
        if (dest == null) dest = p.serverLevel();
        p.teleportTo(dest, x, y, z, yRot, xRot);
        p.setYHeadRot(yHeadRot);
        p.setDeltaMovement(motion);
        p.hurtMarked = true; // 速度をクライアントへ送る
        p.fallDistance = fallDistance;
        if (fallFlying) p.startFallFlying(); else p.stopFallFlying();

        p.setHealth(health);
        p.setAbsorptionAmount(absorption);
        FoodData f = p.getFoodData();
        f.setFoodLevel(food);
        f.setSaturation(saturation);

        p.setRemainingFireTicks(fireTicks);
        p.setAirSupply(air);
        p.setTicksFrozen(frozenTicks);

        p.removeAllEffects();
        for (CompoundTag t : effects) {
            MobEffectInstance inst = MobEffectInstance.load(t);
            if (inst != null) p.addEffect(inst);
        }

        if (inventory != null) {
            p.getInventory().load(inventory);
            p.getInventory().selected = selectedSlot;
            p.connection.send(new ClientboundSetCarriedItemPacket(selectedSlot));
            p.inventoryMenu.broadcastFullState();

            p.experienceLevel = xpLevel;
            p.experienceProgress = xpProgress;
            p.totalExperience = xpTotal;
            p.connection.send(new ClientboundSetExperiencePacket(xpProgress, xpTotal, xpLevel));
        }
    }
}
