package com.timerewind.core;

import com.timerewind.Config;
import com.timerewind.TimeRewindMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 記録と巻き戻しの中心。すべてサーバースレッドで動く。
 *
 * <p>時系列の約束事:
 * <ul>
 *   <li>tick N の開始時に {@link #tick} が N になる</li>
 *   <li>tick N 中に起きたブロック変更・エンティティ出現は tick=N で記録</li>
 *   <li>tick N の終了時に、プレイヤーとエンティティのスナップショットを tick=N で記録</li>
 *   <li>ダメージ発生時はダメージを打ち消して「予約」だけし、tick 終了時にまとめて巻き戻す
 *       （爆発処理の途中などでワールドを書き換えないため）</li>
 * </ul>
 */
public final class RewindManager {
    private static final RewindManager INSTANCE = new RewindManager();

    public static RewindManager get() {
        return INSTANCE;
    }

    private final Map<UUID, ArrayDeque<PlayerSnapshot>> players = new HashMap<>();
    private final Map<ResourceKey<Level>, WorldHistory> worlds = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Set<UUID> pending = new LinkedHashSet<>();
    private final Set<UUID> disabledPlayers = new HashSet<>();

    private long tick;
    private boolean restoring;
    private boolean respawning;

    // tick 開始時に読み込むコンフィグのキャッシュ（Mixin から毎回 ForgeConfigSpec を読まないため）
    private boolean configReady;
    private boolean enabled;
    private int rewindTicks = 60;
    private int cooldownTicks = 60;
    private boolean rewindInventory;
    private boolean rewindBlocks;
    private boolean rewindEntities;
    private int radius = 16;
    private int maxBlockChanges = 200000;

    private RewindManager() {}

    // ------------------------------------------------------------------ 状態

    public boolean isRestoring() {
        return restoring;
    }

    public boolean isRespawning() {
        return respawning;
    }

    public boolean isPending(ServerPlayer p) {
        return pending.contains(p.getUUID());
    }

    public int rewindTicks() {
        return rewindTicks;
    }

    public long cooldownRemaining(ServerPlayer p) {
        return Math.max(0, cooldownUntil.getOrDefault(p.getUUID(), 0L) - tick);
    }

    public int historySize(ServerPlayer p) {
        ArrayDeque<PlayerSnapshot> d = players.get(p.getUUID());
        return d == null ? 0 : d.size();
    }

    public boolean toggle(ServerPlayer p) {
        UUID id = p.getUUID();
        if (disabledPlayers.remove(id)) return true;
        disabledPlayers.add(id);
        return false;
    }

    public boolean isEnabledFor(ServerPlayer p) {
        return !disabledPlayers.contains(p.getUUID());
    }

    private WorldHistory history(ServerLevel level) {
        return worlds.computeIfAbsent(level.dimension(), k -> new WorldHistory());
    }

    // ------------------------------------------------------------------ tick

    public void onTickStart() {
        tick++;
        refreshConfig();
    }

    private void refreshConfig() {
        if (!Config.SPEC.isLoaded()) return;
        configReady = true;
        enabled = Config.ENABLED.get();
        rewindTicks = Math.max(1, (int) Math.round(Config.REWIND_SECONDS.get() * 20.0));
        cooldownTicks = Math.max(rewindTicks, (int) Math.round(Config.COOLDOWN_SECONDS.get() * 20.0));
        rewindInventory = Config.REWIND_INVENTORY.get();
        rewindBlocks = Config.REWIND_BLOCKS.get();
        rewindEntities = Config.REWIND_ENTITIES.get();
        radius = Config.WORLD_RADIUS.get();
        maxBlockChanges = Config.MAX_BLOCK_CHANGES.get();
    }

    public void onTickEnd(MinecraftServer server) {
        if (!configReady) return;

        // 1. 予約された巻き戻しを実行
        if (!pending.isEmpty()) {
            List<UUID> todo = new ArrayList<>(pending);
            pending.clear();
            for (UUID id : todo) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null && p.isAlive()) {
                    try {
                        performRewind(p, server);
                    } catch (Exception ex) {
                        TimeRewindMod.LOGGER.error("Time rewind failed for {}", p.getGameProfile().getName(), ex);
                    }
                }
            }
        }

        long minTick = tick - rewindTicks - 2;

        // 2. ワールド側の記録（ブロック記録エリアの更新・エンティティのフレーム）
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> ps = level.players();
            if (ps.isEmpty()) {
                WorldHistory h = worlds.get(level.dimension());
                if (h != null) h.clear();
                continue;
            }
            WorldHistory h = history(level);
            double[] w = new double[ps.size() * 3];
            for (int i = 0; i < ps.size(); i++) {
                ServerPlayer p = ps.get(i);
                w[i * 3] = p.getX();
                w[i * 3 + 1] = p.getY();
                w[i * 3 + 2] = p.getZ();
            }
            h.watchers = enabled && (rewindBlocks || rewindEntities) ? w : new double[0];

            if (enabled && rewindEntities) recordEntityFrame(level, h, ps);
            h.prune(minTick);
        }

        // 3. プレイヤーのスナップショット
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!enabled || !p.isAlive() || p.isSpectator()) {
                players.remove(p.getUUID());
                continue;
            }
            ArrayDeque<PlayerSnapshot> d = players.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
            d.addLast(PlayerSnapshot.capture(p, tick, rewindInventory));
            while (d.size() > rewindTicks + 1) d.pollFirst();
        }
    }

    private void recordEntityFrame(ServerLevel level, WorldHistory h, List<ServerPlayer> ps) {
        Map<UUID, EntitySnapshot> map = new HashMap<>();
        for (ServerPlayer p : ps) {
            AABB box = p.getBoundingBox().inflate(radius);
            for (Entity e : level.getEntities((Entity) null, box, RewindManager::isTrackable)) {
                UUID id = e.getUUID();
                if (!map.containsKey(id)) {
                    map.put(id, EntitySnapshot.capture(e));
                    h.lastSeen.put(id, tick);
                }
            }
        }
        h.frames.addLast(new WorldHistory.EntityFrame(tick, map));
    }

    private static boolean isTrackable(Entity e) {
        return !(e instanceof Player) && e.isAlive() && e.getType().canSerialize();
    }

    // ------------------------------------------------------------------ トリガー

    /** ダメージイベントから呼ばれる。true なら巻き戻しを予約したのでダメージを打ち消すこと。 */
    public boolean tryTrigger(ServerPlayer p, DamageSource source, float amount) {
        if (!configReady || !enabled || !isEnabledFor(p)) return false;
        if (p.isCreative() || p.isSpectator() || !p.isAlive()) return false;
        if (amount <= 0 || amount < Config.MIN_DAMAGE.get()) return false;
        if (Config.LETHAL_ONLY.get() && amount < p.getHealth()) return false;

        Optional<String> typeId = source.typeHolder().unwrapKey().map(k -> k.location().toString());
        if (typeId.isPresent() && Config.EXCLUDED_DAMAGE_TYPES.get().contains(typeId.get())) return false;

        if (cooldownRemaining(p) > 0) return false;
        ArrayDeque<PlayerSnapshot> d = players.get(p.getUUID());
        if (d == null || d.isEmpty()) return false;

        pending.add(p.getUUID());
        return true;
    }

    /** コマンドから強制的に予約する（クールダウン無視）。 */
    public boolean forceTrigger(ServerPlayer p) {
        ArrayDeque<PlayerSnapshot> d = players.get(p.getUUID());
        if (d == null || d.isEmpty()) return false;
        pending.add(p.getUUID());
        return true;
    }

    // ------------------------------------------------------------------ 巻き戻し本体

    private void performRewind(ServerPlayer p, MinecraftServer server) {
        ArrayDeque<PlayerSnapshot> d = players.get(p.getUUID());
        if (d == null || d.isEmpty()) return;

        long targetTick = tick - rewindTicks;
        PlayerSnapshot snap = null;
        for (PlayerSnapshot s : d) {
            if (s.tick() >= targetTick) {
                snap = s;
                break;
            }
        }
        if (snap == null) snap = d.peekLast();

        ServerLevel fromLevel = p.serverLevel();
        Vec3 from = p.position();

        // 演出用の軌跡（現在いるディメンション内のものだけ）
        List<Vec3> path = new ArrayList<>();
        boolean after = false;
        for (PlayerSnapshot s : d) {
            if (s == snap) after = true;
            if (after && s.dimension() == fromLevel.dimension()) path.add(s.position());
        }

        restoring = true;
        try {
            if (rewindBlocks || rewindEntities) {
                List<Vec3> centers = new ArrayList<>();
                centers.add(from);
                if (snap.dimension() == fromLevel.dimension()) centers.add(snap.position());
                rewindWorld(fromLevel, snap.tick(), centers);
            }
            snap.restore(p, server);
        } finally {
            restoring = false;
            respawning = false;
        }

        d.clear();
        cooldownUntil.put(p.getUUID(), tick + cooldownTicks);

        playEffects(fromLevel, from, p, path);
    }

    private void rewindWorld(ServerLevel level, long targetTick, List<Vec3> centers) {
        WorldHistory h = worlds.get(level.dimension());
        if (h == null) return;
        if (rewindBlocks) rewindBlocks(level, h, targetTick, centers);
        if (rewindEntities) rewindEntities(level, h, targetTick, centers);
    }

    private boolean inArea(double x, double y, double z, List<Vec3> centers) {
        for (Vec3 c : centers) {
            if (Math.abs(c.x - x) <= radius && Math.abs(c.y - y) <= radius && Math.abs(c.z - z) <= radius) {
                return true;
            }
        }
        return false;
    }

    private void rewindBlocks(ServerLevel level, WorldHistory h, long targetTick, List<Vec3> centers) {
        // targetTick より後に起きた変更のうち、各座標で「最初」の変更の旧状態 = targetTick 時点の状態
        Map<BlockPos, WorldHistory.BlockChange> earliest = new LinkedHashMap<>();
        Iterator<WorldHistory.BlockChange> it = h.blockChanges.iterator();
        while (it.hasNext()) {
            WorldHistory.BlockChange c = it.next();
            if (c.tick() <= targetTick) continue;
            BlockPos pos = c.pos();
            if (!inArea(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, centers)) continue;
            earliest.putIfAbsent(pos, c);
            it.remove();
        }
        if (earliest.isEmpty()) return;

        // 1周目: 形状更新なしで一気に置く（ドアやベッドの片側が崩れないように）
        for (WorldHistory.BlockChange c : earliest.values()) {
            BlockState current = level.getBlockState(c.pos());
            if (current != c.oldState()) {
                // 置き換えで中身がドロップしないよう、先に空にする
                Clearable.tryClear(level.getBlockEntity(c.pos()));
                level.setBlock(c.pos(), c.oldState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        // 2周目: ブロックエンティティ（チェストの中身など）を戻す
        for (WorldHistory.BlockChange c : earliest.values()) {
            CompoundTag tag = c.oldBlockEntity();
            if (tag == null) continue;
            BlockEntity be = level.getBlockEntity(c.pos());
            if (be != null) {
                be.load(tag);
                be.setChanged();
                level.sendBlockUpdated(c.pos(), c.oldState(), c.oldState(), Block.UPDATE_CLIENTS);
            }
        }
        // 3周目: 周囲に変化を通知（水の流れ・レッドストーン等が続きから動く）
        for (WorldHistory.BlockChange c : earliest.values()) {
            level.blockUpdated(c.pos(), c.oldState().getBlock());
        }
    }

    private void rewindEntities(ServerLevel level, WorldHistory h, long targetTick, List<Vec3> centers) {
        WorldHistory.EntityFrame frame = null;
        for (WorldHistory.EntityFrame f : h.frames) {
            if (f.tick() <= targetTick) frame = f;
            else break;
        }
        if (frame == null) frame = h.frames.peekFirst();
        Map<UUID, EntitySnapshot> atTarget = frame != null ? frame.entities() : Map.of();

        // 1. targetTick より後に出現したもの（ドロップ品・矢・湧いたモブ等）を消す
        Set<UUID> seen = new HashSet<>();
        List<Entity> toDiscard = new ArrayList<>();
        for (Vec3 c : centers) {
            AABB box = AABB.ofSize(c, radius * 2.0, radius * 2.0, radius * 2.0);
            for (Entity e : level.getEntities((Entity) null, box, x -> !(x instanceof Player))) {
                if (!seen.add(e.getUUID())) continue;
                Long spawned = h.spawnTicks.get(e.getUUID());
                if (spawned != null && spawned > targetTick && !atTarget.containsKey(e.getUUID())) {
                    toDiscard.add(e);
                }
            }
        }
        for (Entity e : toDiscard) e.discard();

        // 2. targetTick 時点にいたものを戻す（生きていれば状態だけ、消えていれば復活）
        for (Map.Entry<UUID, EntitySnapshot> en : atTarget.entrySet()) {
            EntitySnapshot s = en.getValue();
            if (!inArea(s.x(), s.y(), s.z(), centers)) continue;
            UUID id = en.getKey();

            Entity cur = level.getEntity(id);
            if (cur != null && cur.isAlive()) {
                s.apply(cur, false);
                continue;
            }

            CompoundTag tag = null;
            if (cur != null && !cur.isRemoved()) {
                // 死亡アニメーション中
                tag = saveWithId(cur);
                cur.discard();
            } else {
                WorldHistory.RemovedEntity rem = h.removed.remove(id);
                if (rem != null) tag = rem.nbt().copy();
            }
            if (tag == null) continue;
            s.patchTag(tag);

            respawning = true;
            try {
                Optional<Entity> created = EntityType.create(tag, level);
                if (created.isPresent()) {
                    Entity e = created.get();
                    s.apply(e, true);
                    if (!level.addFreshEntity(e)) {
                        e.setUUID(UUID.randomUUID());
                        level.addFreshEntity(e);
                    }
                }
            } finally {
                respawning = false;
            }
        }
    }

    private static CompoundTag saveWithId(Entity e) {
        CompoundTag tag = new CompoundTag();
        e.saveWithoutId(tag);
        tag.putString("id", EntityType.getKey(e.getType()).toString());
        return tag;
    }

    private void playEffects(ServerLevel fromLevel, Vec3 from, ServerPlayer p, List<Vec3> path) {
        for (int i = 0; i < path.size(); i += 2) {
            Vec3 v = path.get(i);
            fromLevel.sendParticles(ParticleTypes.REVERSE_PORTAL, v.x, v.y + 1.0, v.z, 3, 0.2, 0.4, 0.2, 0.01);
        }
        fromLevel.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 0.5f);
        ServerLevel now = p.serverLevel();
        now.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0f, 1.6f);
        now.sendParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1.0, p.getZ(), 40, 0.4, 0.8, 0.4, 0.05);

        double seconds = rewindTicks / 20.0;
        String sec = seconds == Math.floor(seconds) ? String.valueOf((int) seconds) : String.format("%.1f", seconds);
        p.displayClientMessage(Component.translatable("timerewind.message.rewound", sec), true);
    }

    // ------------------------------------------------------------------ 記録フック

    /** Mixin（Level#setBlock の先頭）から呼ばれる。変更前の状態を記録する。 */
    public void beforeSetBlock(ServerLevel level, BlockPos pos, BlockState newState) {
        if (!configReady || !enabled || !rewindBlocks || restoring) return;
        if (!level.getServer().isSameThread()) return;
        if (level.isOutsideBuildHeight(pos)) return;
        WorldHistory h = worlds.get(level.dimension());
        if (h == null || !h.isWatched(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, radius)) return;

        BlockState old = level.getBlockState(pos);
        if (old == newState) return;
        CompoundTag beTag = null;
        if (old.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) beTag = be.saveWithFullMetadata();
        }
        h.addBlockChange(new WorldHistory.BlockChange(tick, pos.immutable(), old, beTag), maxBlockChanges);
    }

    /** ブロックを右クリックする直前（チェストの中身を取る前など）にブロックエンティティを記録する。 */
    public void recordBlockEntity(ServerLevel level, BlockPos pos) {
        if (!configReady || !enabled || !rewindBlocks || restoring) return;
        WorldHistory h = worlds.get(level.dimension());
        if (h == null || !h.isWatched(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, radius)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        h.addBlockChange(new WorldHistory.BlockChange(tick, pos.immutable(), level.getBlockState(pos), be.saveWithFullMetadata()), maxBlockChanges);
    }

    public void onEntitySpawn(ServerLevel level, Entity e) {
        if (!configReady || !enabled || !rewindEntities || restoring) return;
        WorldHistory h = worlds.get(level.dimension());
        if (h == null || !h.isWatched(e.getX(), e.getY(), e.getZ(), radius + 4)) return;
        h.spawnTicks.put(e.getUUID(), tick);
    }

    public void onEntityRemoved(ServerLevel level, Entity e) {
        if (!configReady || !enabled || !rewindEntities || restoring) return;
        Entity.RemovalReason reason = e.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) return;
        if (!e.getType().canSerialize()) return;
        WorldHistory h = worlds.get(level.dimension());
        if (h == null || !h.lastSeen.containsKey(e.getUUID())) return;
        h.removed.put(e.getUUID(), new WorldHistory.RemovedEntity(tick, saveWithId(e)));
    }

    // ------------------------------------------------------------------ 後片付け

    public void forgetPlayer(UUID id) {
        players.remove(id);
        pending.remove(id);
    }

    public void clearPlayerHistory(UUID id) {
        ArrayDeque<PlayerSnapshot> d = players.get(id);
        if (d != null) d.clear();
    }

    public void clearAll() {
        players.clear();
        worlds.clear();
        cooldownUntil.clear();
        pending.clear();
        tick = 0;
        configReady = false;
    }
}
