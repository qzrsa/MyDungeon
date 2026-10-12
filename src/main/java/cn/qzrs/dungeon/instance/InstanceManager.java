package cn.qzrs.dungeon.instance;

import cn.qzrs.dungeon.mob.DungeonMob;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 槽位管理 + 索引。
 *
 * <p>mobIndex 是一张「实体 UUID → 所属副本」的全局索引，
 * 让 CombatEngine 在每次伤害事件里 O(1) 判断"这只怪是不是副本怪"，
 * 不必遍历所有槽位。
 */
public class InstanceManager {

    private final List<DungeonInstance> slots = new ArrayList<>();
    private final Map<UUID, DungeonInstance> mobIndex = new HashMap<>();

    public void init(String worldName, int count) {
        slots.clear();
        mobIndex.clear();
        for (int i = 1; i <= Math.max(1, count); i++) {
            slots.add(new DungeonInstance(i, worldName));
        }
    }

    public List<DungeonInstance> all() {
        return slots;
    }

    public DungeonInstance findFree() {
        for (DungeonInstance inst : slots) {
            if (inst.isIdle()) {
                return inst;
            }
        }
        return null;
    }

    /**
     * 找「第 1 关（stageIndex == 0）进行中」的副本 —— 用于组队中途加入。
     * <p>规则：倒计时结束开始打第 1 关时，其他人还能进同一个槽位；
     * 一旦推进到第 2 关（stageIndex >= 1），就禁止再进人，直到副本结束。
     */
    public DungeonInstance findJoinable() {
        for (DungeonInstance inst : slots) {
            if (inst.state == DungeonInstance.State.RUNNING && inst.stageIndex == 0) {
                return inst;
            }
        }
        return null;
    }

    public DungeonInstance byPlayer(UUID uuid) {
        for (DungeonInstance inst : slots) {
            if (inst.members.contains(uuid)) {
                return inst;
            }
        }
        return null;
    }

    /** 该实体是否是副本怪；是则返回所属副本，否则 null */
    public DungeonInstance byMob(UUID uuid) {
        return mobIndex.get(uuid);
    }

    public DungeonMob mob(UUID uuid) {
        DungeonInstance inst = mobIndex.get(uuid);
        return inst == null ? null : inst.liveMobs.get(uuid);
    }

    public void registerMob(DungeonInstance inst, DungeonMob mob) {
        UUID id = mob.entity.getUniqueId();
        inst.liveMobs.put(id, mob);
        mobIndex.put(id, inst);
    }

    public void unregisterMob(UUID uuid) {
        DungeonInstance inst = mobIndex.remove(uuid);
        if (inst != null) {
            inst.liveMobs.remove(uuid);
        }
    }

    // ------------------------------------------------------------------
    // 伤害统计（全部只在主线程调用）
    // ------------------------------------------------------------------

    /**
     * 记录一次「玩家 → 副本怪」的有效伤害。
     *
     * @param amount 实际扣掉的血量（已被怪的剩余血量截断，超额部分不计），
     *               这样统计出的 DPS 不会因为「一击秒小怪」而虚高。
     */
    public void recordDamage(DungeonInstance inst, UUID uuid, double amount) {
        if (inst == null || uuid == null || amount <= 0.0) {
            return;
        }
        inst.damage.merge(uuid, amount, Double::sum);
        long now = System.currentTimeMillis();
        inst.firstHitMs.putIfAbsent(uuid, now);
        inst.damageWindow.computeIfAbsent(uuid, k -> new ArrayDeque<>())
                .addLast(new double[]{now, amount});
    }

    /**
     * 实时秒伤 = 最近 {@code windowSeconds} 秒造成的伤害 ÷ 实际统计时长。
     *
     * <p>分母取「首次命中至今」与窗口的较小值：否则刚进本 1 秒打出 1 万伤，
     * 会被整个 5 秒窗口摊平成 2000/s，看着像没输出。
     *
     * <p>顺带把窗口外的旧记录丢掉，所以 deque 不会无限增长。
     */
    public double dps(DungeonInstance inst, UUID uuid, int windowSeconds) {
        if (inst == null || uuid == null) {
            return 0.0;
        }
        Deque<double[]> q = inst.damageWindow.get(uuid);
        if (q == null || q.isEmpty()) {
            return 0.0;
        }
        long now = System.currentTimeMillis();
        long windowMs = Math.max(1, windowSeconds) * 1000L;
        while (!q.isEmpty() && now - (long) q.peekFirst()[0] > windowMs) {
            q.pollFirst();
        }
        double sum = 0.0;
        for (double[] e : q) {
            sum += e[1];
        }
        Long first = inst.firstHitMs.get(uuid);
        double secs = first == null
                ? Math.max(1, windowSeconds)
                : Math.max(1.0, Math.min(windowMs, now - first) / 1000.0);
        return sum / secs;
    }

    /** 本场累计伤害，按降序（第 1 名在最前） */
    public List<Map.Entry<UUID, Double>> ranking(DungeonInstance inst) {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(inst.damage.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list;
    }

    public int runningCount() {
        int n = 0;
        for (DungeonInstance inst : slots) {
            if (inst.state == DungeonInstance.State.RUNNING) {
                n++;
            }
        }
        return n;
    }
}
