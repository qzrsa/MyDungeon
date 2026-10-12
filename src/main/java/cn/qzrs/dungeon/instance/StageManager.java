package cn.qzrs.dungeon.instance;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.PluginConfig;
import cn.qzrs.dungeon.mob.DungeonMob;
import cn.qzrs.dungeon.mob.MobSpawner;
import cn.qzrs.dungeon.mob.MobTemplate;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 阶段编排：把 stages/*.yml 里的线性阶段跑起来。
 *
 * <p>规则很简单 —— 当前阶段的怪全部清空后，自动进入下一阶段；
 * 最后一个阶段清空即视为通关。
 */
public class StageManager {

    /** 单条刷怪指令 */
    public static class SpawnDef {
        public final String mobId;
        public final int count;
        /** {@code > 0} = 该条目指定绝对血量（覆盖模板血量与层数加成）；0 = 走常规公式 */
        public final double hp;

        public SpawnDef(String mobId, int count, double hp) {
            this.mobId = mobId;
            this.count = count;
            this.hp = hp;
        }
    }

    /** 一个阶段 */
    public static class StageDef {
        public final String type;
        public final String name;
        public final List<SpawnDef> spawns = new ArrayList<>();

        public StageDef(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public boolean isBoss() {
            return "BOSS".equalsIgnoreCase(type);
        }
    }

    private final List<StageDef> stages = new ArrayList<>();
    private String dungeonId = "dungeon1";
    private String dungeonName = "副本";

    public String dungeonId() {
        return dungeonId;
    }

    public String dungeonName() {
        return dungeonName;
    }

    public int stageCount() {
        return stages.size();
    }

    /** 所有 spawn 条目里指定的最大绝对血量（0 = 没有任何条目用 hp: 覆盖）。给启动自检用 */
    public double maxHpOverride() {
        double max = 0.0;
        for (StageDef s : stages) {
            for (SpawnDef sd : s.spawns) {
                if (sd.hp > max) {
                    max = sd.hp;
                }
            }
        }
        return max;
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    public void load(YamlConfiguration yml) {
        stages.clear();
        dungeonId = yml.getString("id", "dungeon1");
        dungeonName = yml.getString("name", "副本");

        for (Map<?, ?> raw : yml.getMapList("stages")) {
            String type = str(raw.get("type"), "WAVE");
            String name = str(raw.get("name"), "");
            StageDef def = new StageDef(type, name);

            Object spawnsObj = raw.get("spawns");
            if (spawnsObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> sm) {
                        String mobId = str(sm.get("mob"), "");
                        int count = toInt(sm.get("count"), 1);
                        // hp: 可选 —— 指定该怪在本层的绝对血量（如 BOOS 单独钉死 200W）
                        double hp = toDouble(sm.get("hp"), 0.0);
                        if (!mobId.isBlank() && count > 0) {
                            def.spawns.add(new SpawnDef(mobId, count, hp));
                        }
                    }
                }
            }
            stages.add(def);
        }
    }

    // ------------------------------------------------------------------
    // 流程
    // ------------------------------------------------------------------

    /** 开始整场副本 */
    public void start(DungeonInstance inst) {
        inst.state = DungeonInstance.State.RUNNING;
        inst.completed = false;
        inst.startTime = System.currentTimeMillis();
        broadcast(inst, ChatColor.GOLD + "▶ " + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', dungeonName))
                + " 开始！共 " + stages.size() + " 层。");
        startStage(inst, 0);
    }

    private void startStage(DungeonInstance inst, int index) {
        if (index >= stages.size()) {
            onCleared(inst);
            return;
        }
        inst.stageIndex = index;
        StageDef def = stages.get(index);
        String title = ChatColor.translateAlternateColorCodes('&', def.name);

        broadcast(inst, ChatColor.YELLOW + "◆ 第 " + (index + 1) + " 层 / 共 " + stages.size() + " 层：" + title);

        World w = Bukkit.getWorld(inst.worldName);
        if (w == null) {
            Bukkit.getLogger().warning("[MyDungeon] 副本世界不存在：" + inst.worldName);
            return;
        }

        if (def.isBoss()) {
            inst.bossBar = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SEGMENTED_20);
            for (UUID u : inst.members) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) {
                    inst.bossBar.addPlayer(p);
                }
            }
        }

        for (SpawnDef sd : def.spawns) {
            MobTemplate template = MyDungeon.get().mob(sd.mobId);
            if (template == null) {
                Bukkit.getLogger().warning("[MyDungeon] 未找到怪模板：" + sd.mobId);
                continue;
            }
            Player anchor = firstOnlinePlayer(inst);
            for (int i = 0; i < sd.count; i++) {
                Location loc = randomSpawnPoint(w, anchor);
                DungeonMob mob = MobSpawner.spawn(loc, template, index + 1, sd.hp);
                if (mob != null) {
                    MyDungeon.get().instances().registerMob(inst, mob);
                }
            }
        }
    }

    /** 怪死亡回调：由 DungeonListener 调用 */
    public void onMobDeath(DungeonInstance inst, UUID mobUuid) {
        inst.liveMobs.remove(mobUuid);
        if (inst.state != DungeonInstance.State.RUNNING) {
            return;
        }
        if (!inst.liveMobs.isEmpty()) {
            return;
        }
        int next = inst.stageIndex + 1;
        if (inst.bossBar != null) {
            inst.bossBar.removeAll();
            inst.bossBar = null;
        }

        boolean lastStage = next >= stages.size();
        int rest = lastStage ? 0 : MyDungeon.get().config().restTime;

        if (rest <= 0) {
            Bukkit.getScheduler().runTaskLater(MyDungeon.get(), () -> {
                if (inst.state == DungeonInstance.State.RUNNING) {
                    startStage(inst, next);
                }
            }, 20L);
            return;
        }

        broadcast(inst, ChatColor.GREEN + "✔ 第 " + (inst.stageIndex + 1) + " 层已清空！" + rest + " 秒修整时间...");
        final int[] left = {rest};
        Bukkit.getScheduler().runTaskTimer(MyDungeon.get(), task -> {
            left[0]--;
            if (left[0] <= 0) {
                task.cancel();
                if (inst.state == DungeonInstance.State.RUNNING) {
                    startStage(inst, next);
                }
            } else if (left[0] <= 5 || left[0] % 10 == 0) {
                broadcast(inst, ChatColor.GRAY + "修整时间还剩 " + left[0] + " 秒...");
            }
        }, 20L, 20L);
    }

    private void onCleared(DungeonInstance inst) {
        inst.completed = true;
        inst.state = DungeonInstance.State.FINISHED;
        long seconds = (System.currentTimeMillis() - inst.startTime) / 1000L;
        broadcast(inst, ChatColor.GREEN + "✔ 全部 " + stages.size() + " 层通关！用时 " + seconds + " 秒。");
        MyDungeon.get().rewards().give(inst);
        MyDungeon.get().endInstance(inst, "通关");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 把卡住的怪拉回场地内的随机安全点（避开台座与玩家），而不是清除掉 */
    public void relocateToArena(DungeonInstance inst, DungeonMob mob) {
        if (mob == null || mob.entity == null || mob.entity.isDead()) {
            return;
        }
        Player anchor = firstOnlinePlayer(inst);
        Location safe = randomSpawnPoint(mob.entity.getWorld(), anchor);
        mob.entity.teleport(safe);
    }

    private Location randomSpawnPoint(World w, Player avoid) {
        PluginConfig cfg = MyDungeon.get().config();
        int half = Math.max(4, cfg.arenaSize / 2 - 3);
        for (int attempt = 0; attempt < 32; attempt++) {
            int x = ThreadLocalRandom.current().nextInt(-half, half + 1);
            int z = ThreadLocalRandom.current().nextInt(-half, half + 1);
            // 避开中心台座（-1..1 × -1..1，y=floorY+1 是实心方块，刷进去会窒息卡怪、
            // 玩家找不到）。这是「总有一只找不到」的根因。
            if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                continue;
            }
            Location loc = new Location(w, x + 0.5, cfg.arenaFloorY + 1, z + 0.5);
            if (avoid == null || loc.distanceSquared(avoid.getLocation()) > 64.0) {
                return loc;
            }
        }
        // 兜底落点：台座外的固定安全点（原 0.5,0.5 正好在台座正中心，同样是坑）
        return new Location(w, 2.5, cfg.arenaFloorY + 1, 2.5);
    }

    private Player firstOnlinePlayer(DungeonInstance inst) {
        for (UUID u : inst.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                return p;
            }
        }
        return null;
    }

    private void broadcast(DungeonInstance inst, String msg) {
        for (UUID u : inst.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                p.sendMessage(ChatColor.DARK_GRAY + "[副本] " + ChatColor.RESET + msg);
            }
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
