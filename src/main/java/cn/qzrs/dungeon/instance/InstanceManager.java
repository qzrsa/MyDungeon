package cn.qzrs.dungeon.instance;

import cn.qzrs.dungeon.mob.DungeonMob;

import java.util.ArrayList;
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
