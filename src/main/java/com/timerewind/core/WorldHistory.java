package com.timerewind.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 1ディメンション分の履歴。 */
public final class WorldHistory {

    /** ブロック変更「前」の状態。tick はその変更が起きたtick。 */
    public record BlockChange(long tick, BlockPos pos, BlockState oldState, CompoundTag oldBlockEntity) {}

    /** あるtick終了時点で、プレイヤー周辺にいたエンティティ。 */
    public record EntityFrame(long tick, Map<UUID, EntitySnapshot> entities) {}

    /** 消滅したエンティティの完全なNBT（"id" 付き）。 */
    public record RemovedEntity(long tick, CompoundTag nbt) {}

    final ArrayDeque<BlockChange> blockChanges = new ArrayDeque<>();
    final ArrayDeque<EntityFrame> frames = new ArrayDeque<>();
    /** ワールドに「新しく」出現したtick（ディスクからの読み込みは含まない） */
    final Map<UUID, Long> spawnTicks = new HashMap<>();
    /** 最近フレームに記録されたエンティティと最終記録tick */
    final Map<UUID, Long> lastSeen = new HashMap<>();
    final Map<UUID, RemovedEntity> removed = new HashMap<>();

    /** 記録対象エリアの中心（プレイヤー位置）。x,y,z の並び。 */
    double[] watchers = new double[0];

    boolean isWatched(double x, double y, double z, int radius) {
        double[] w = watchers;
        for (int i = 0; i + 2 < w.length; i += 3) {
            if (Math.abs(w[i] - x) <= radius && Math.abs(w[i + 1] - y) <= radius && Math.abs(w[i + 2] - z) <= radius) {
                return true;
            }
        }
        return false;
    }

    void addBlockChange(BlockChange c, int max) {
        blockChanges.addLast(c);
        while (blockChanges.size() > max) blockChanges.pollFirst();
    }

    void prune(long minTick) {
        while (!blockChanges.isEmpty() && blockChanges.peekFirst().tick() < minTick) blockChanges.pollFirst();
        while (frames.size() > 1 && frames.peekFirst().tick() < minTick) frames.pollFirst();
        spawnTicks.values().removeIf(t -> t < minTick);
        lastSeen.values().removeIf(t -> t < minTick);
        removed.values().removeIf(r -> r.tick() < minTick);
    }

    void clear() {
        blockChanges.clear();
        frames.clear();
        spawnTicks.clear();
        lastSeen.clear();
        removed.clear();
        watchers = new double[0];
    }
}
