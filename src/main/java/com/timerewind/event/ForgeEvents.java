package com.timerewind.event;

import com.timerewind.command.RewindCommand;
import com.timerewind.core.RewindManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

public class ForgeEvents {

    private final RewindManager manager = RewindManager.get();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            manager.onTickStart();
        } else {
            manager.onTickEnd(ServerLifecycleHooks.getCurrentServer());
        }
    }

    /**
     * 防具・軽減・衝撃吸収を計算した後の最終ダメージ。
     * ここでキャンセルすると体力は減らない。実際の巻き戻しは tick 終了時に行う。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (manager.isPending(player) || manager.isRestoring()) {
            event.setCanceled(true);
            return;
        }
        if (manager.tryTrigger(player, event.getSource(), event.getAmount())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (e instanceof Player) return;

        // 巻き戻し中にこぼれたアイテム・経験値は複製の元なので出現させない（復活させたものは除く）
        if (manager.isRestoring() && !manager.isRespawning()
                && (e instanceof ItemEntity || e instanceof ExperienceOrb)) {
            event.setCanceled(true);
            return;
        }
        if (event.loadedFromDisk()) return;
        manager.onEntitySpawn(level, e);
    }

    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (e instanceof Player) return;
        manager.onEntityRemoved(level, e);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel() instanceof ServerLevel level) {
            manager.recordBlockEntity(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            manager.clearPlayerHistory(p.getUUID());
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        manager.clearPlayerHistory(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        manager.forgetPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        manager.clearAll();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        RewindCommand.register(event.getDispatcher());
    }
}
