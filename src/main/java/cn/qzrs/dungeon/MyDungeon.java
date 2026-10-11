package cn.qzrs.dungeon;

import cn.qzrs.dungeon.arena.ArenaBuilder;
import cn.qzrs.dungeon.combat.CombatEngine;
import cn.qzrs.dungeon.command.DungeonCommand;
import cn.qzrs.dungeon.instance.DungeonInstance;
import cn.qzrs.dungeon.instance.InstanceManager;
import cn.qzrs.dungeon.instance.StageManager;
import cn.qzrs.dungeon.listener.DungeonListener;
import cn.qzrs.dungeon.mob.DungeonMob;
import cn.qzrs.dungeon.mob.MobTemplate;
import cn.qzrs.dungeon.reward.RewardManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MyDungeon —— 副本挑战插件（MVP）
 *
 * <p>核心思路：副本内战斗由 {@link CombatEngine} 接管，
 * 玩家的神化词缀 / 拔刀剑属性全部保留，但换算规则改走
 * {@link cn.qzrs.dungeon.combat.DamageFormula}，从而让怪追得上玩家。
 */
public class MyDungeon extends JavaPlugin {

    private static MyDungeon instance;

    private final PluginConfig config = new PluginConfig();
    private final InstanceManager instances = new InstanceManager();
    private StageManager stages = new StageManager();
    private RewardManager rewards = new RewardManager();
    private Map<String, MobTemplate> mobs = new LinkedHashMap<>();
    private World dungeonWorld;

    public static MyDungeon get() {
        return instance;
    }

    public PluginConfig config() {
        return config;
    }

    public InstanceManager instances() {
        return instances;
    }

    public StageManager stages() {
        return stages;
    }

    public RewardManager rewards() {
        return rewards;
    }

    public Map<String, MobTemplate> mobs() {
        return mobs;
    }

    public MobTemplate mob(String id) {
        return id == null ? null : mobs.get(id.toLowerCase());
    }

    public boolean isDungeonWorld(World w) {
        return w != null && w.getName().equalsIgnoreCase(config.worldName);
    }

    public World dungeonWorld() {
        return dungeonWorld;
    }

    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("mobs.yml", false);
        reloadAll();

        dungeonWorld = ArenaBuilder.ensureWorld(config.worldName);
        if (dungeonWorld == null) {
            getLogger().severe("无法创建/加载副本世界：" + config.worldName + " —— 副本功能不可用。");
        } else if (config.arenaAutoBuild) {
            ArenaBuilder.build(dungeonWorld);
            ArenaBuilder.applyGameRules(dungeonWorld);
            getLogger().info("测试场已生成：" + config.arenaSize + "×" + config.arenaSize
                    + " @ y=" + config.arenaFloorY);
        }

        getServer().getPluginManager().registerEvents(new CombatEngine(), this);
        getServer().getPluginManager().registerEvents(new DungeonListener(this), this);

        DungeonCommand cmd = new DungeonCommand(this);
        if (getCommand("dungeon") != null) {
            getCommand("dungeon").setExecutor(cmd);
            getCommand("dungeon").setTabCompleter(cmd);
        }

        startBossBarTask();

        getLogger().info("MyDungeon 已启用 | 副本世界=" + config.worldName
                + " | 槽位=" + config.slots
                + " | 怪模板=" + mobs.size()
                + " | 阶段=" + stages.stageCount()
                + " | 公式K=" + config.armorK
                + " | 血量倍率=" + config.mobHealthScale
                + " | 伤害倍率=" + config.mobDamageScale);
    }

    @Override
    public void onDisable() {
        for (DungeonInstance inst : instances.all()) {
            endInstance(inst, "插件卸载");
        }
        rewards.save();
        instance = null;
    }

    /** 重载全部配置（config.yml / mobs.yml / stages / rewards） */
    public void reloadAll() {
        reloadConfig();
        config.load(getConfig());

        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("无法创建插件数据目录：" + dataFolder);
        }

        saveResource("mobs.yml", false);
        mobs = MobTemplate.loadAll(YamlConfiguration.loadConfiguration(new File(dataFolder, "mobs.yml")));

        String stagePath = "stages/" + config.stageFile;
        String rewardPath = "rewards/" + config.rewardFile;
        saveResource(stagePath, false);
        saveResource(rewardPath, false);

        stages = new StageManager();
        stages.load(YamlConfiguration.loadConfiguration(new File(dataFolder, stagePath)));

        rewards = new RewardManager();
        rewards.load(YamlConfiguration.loadConfiguration(new File(dataFolder, rewardPath)),
                new File(dataFolder, "clears.yml"));

        instances.init(config.worldName, config.slots);
    }

    /**
     * 结束一场副本：清怪、把成员送回原位置、槽位归位。
     */
    public void endInstance(DungeonInstance inst, String reason) {
        if (inst == null) {
            return;
        }

        if (inst.bossBar != null) {
            inst.bossBar.removeAll();
            inst.bossBar = null;
        }

        for (DungeonMob mob : new ArrayList<>(inst.liveMobs.values())) {
            if (mob.entity != null) {
                if (!mob.entity.isDead()) {
                    mob.entity.remove();
                }
                instances.unregisterMob(mob.entity.getUniqueId());
            }
        }
        inst.liveMobs.clear();

        for (UUID u : new ArrayList<>(inst.members)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                Location back = inst.returnPoints.get(u);
                if (back != null) {
                    p.teleport(back);
                }
                p.setFireTicks(0);
            }
        }
        inst.members.clear();
        inst.returnPoints.clear();
        inst.clearRuntime();

        if (inst.bossBar == null) {
            getLogger().fine("副本槽位#" + inst.slotId + " 已结束：" + reason);
        }
    }

    /** 首领血条刷新：每 0.5 秒对齐一次真实血量 */
    private void startBossBarTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (DungeonInstance inst : instances.all()) {
                if (inst.bossBar == null) {
                    continue;
                }
                DungeonMob boss = null;
                for (DungeonMob m : inst.liveMobs.values()) {
                    if (m.template.boss) {
                        boss = m;
                        break;
                    }
                }
                if (boss == null || boss.entity == null || boss.entity.isDead()) {
                    inst.bossBar.setProgress(0.0);
                    continue;
                }
                double max = Math.max(1.0, boss.scaledHealth);
                double ratio = boss.entity.getHealth() / max;
                inst.bossBar.setProgress(Math.max(0.0, Math.min(1.0, ratio)));
            }
        }, 20L, 10L);
    }
}
