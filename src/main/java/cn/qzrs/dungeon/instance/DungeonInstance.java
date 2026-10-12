package cn.qzrs.dungeon.instance;

import cn.qzrs.dungeon.mob.DungeonMob;
import org.bukkit.Location;
import org.bukkit.boss.BossBar;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单个副本槽位的运行态。MVP 只有 1 个，但结构完全按多槽位设计，
 * 以后把 config.yml 的 dungeon.slots 调大即可并行开多场。
 */
public class DungeonInstance {

    public enum State {
        /** 空闲，可被进入 */
        IDLE,
        /** 进行中 */
        RUNNING,
        /** 已结束，等待清理 */
        FINISHED
    }

    /** 槽位编号，从 1 开始 */
    public final int slotId;
    public final String worldName;

    /** 当前在副本内的玩家 */
    public final Set<UUID> members = new LinkedHashSet<>();
    /** 本场存活的怪：实体 UUID → 怪实例 */
    public final Map<UUID, DungeonMob> liveMobs = new LinkedHashMap<>();
    /** 玩家进本前的位置，离开/结束时还原 */
    public final Map<UUID, Location> returnPoints = new HashMap<>();
    /** 玩家进本前的飞行权限，离开时还原（副本内禁飞） */
    public final Map<UUID, Boolean> allowFlightBefore = new HashMap<>();
    /** 玩家连续滞空 tick 数，用于检测拔刀剑等技能浮空 */
    public final Map<UUID, Integer> airTicks = new HashMap<>();

    public State state = State.IDLE;
    /** 当前阶段下标，-1 表示尚未开始 */
    public int stageIndex = -1;
    public long startTime = 0L;
    /** 首领血条（仅 BOSS 阶段存在） */
    public BossBar bossBar;
    /** 本场是否已完成首通判定 */
    public boolean completed = false;

    public DungeonInstance(int slotId, String worldName) {
        this.slotId = slotId;
        this.worldName = worldName;
    }

    public boolean isIdle() {
        return state == State.IDLE;
    }

    public void clearRuntime() {
        liveMobs.clear();
        airTicks.clear();
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        stageIndex = -1;
        startTime = 0L;
        completed = false;
        state = State.IDLE;
    }

    public String describe() {
        return "槽位#" + slotId + " 状态=" + state + " 人数=" + members.size() + " 存活怪=" + liveMobs.size();
    }
}
