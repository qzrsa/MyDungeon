package cn.qzrs.dungeon;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * config.yml 的强类型映射。所有调参入口集中在这里，
 * 改配置 + /dungeon reload 即可生效，无需重新编译。
 */
public class PluginConfig {

    // ---------- combat ----------
    public double armorK = 100.0;
    public double damageMultiplier = 1.0;
    public double minDamage = 1.0;
    public boolean blockApothReduction = false;

    // ---------- mobs ----------
    public double mobHealthScale = 1.0;
    public double mobDamageScale = 1.0;

    // ---------- dungeon ----------
    public String worldName = "dungeon_01";
    public int slots = 1;
    public double spawnX = 0.5;
    public double spawnY = 66.0;
    public double spawnZ = 0.5;
    public double boundaryRadius = 60.0;
    public boolean requireTicket = false;
    public Material ticketMaterial = Material.NETHER_STAR;
    public boolean kickOnDeath = false;
    /** 进入副本倒计时秒数 */
    public int enterCountdown = 5;
    /** 每层结束后的修整秒数 */
    public int restTime = 30;

    // ---------- arena ----------
    public boolean arenaAutoBuild = true;
    public int arenaSize = 48;
    public int arenaFloorY = 64;
    public int arenaWallHeight = 44;
    public int arenaClearAbove = 60;

    // ---------- 数据文件 ----------
    public String stageFile = "dungeon1.yml";
    public String rewardFile = "dungeon1.yml";

    public void load(FileConfiguration c) {
        armorK = c.getDouble("combat.armor-k", 100.0);
        damageMultiplier = c.getDouble("combat.damage-multiplier", 1.0);
        minDamage = c.getDouble("combat.min-damage", 1.0);
        blockApothReduction = c.getBoolean("combat.block-apoth-damage-reduction", false);

        mobHealthScale = c.getDouble("mobs.health-scale", 1.0);
        mobDamageScale = c.getDouble("mobs.damage-scale", 1.0);

        worldName = c.getString("dungeon.world", "dungeon_01");
        slots = Math.max(1, c.getInt("dungeon.slots", 1));
        spawnX = c.getDouble("dungeon.spawn.x", 0.5);
        spawnY = c.getDouble("dungeon.spawn.y", 66.0);
        spawnZ = c.getDouble("dungeon.spawn.z", 0.5);
        boundaryRadius = c.getDouble("dungeon.boundary-radius", 60.0);
        requireTicket = c.getBoolean("dungeon.require-ticket", false);
        kickOnDeath = c.getBoolean("dungeon.kick-on-death", false);
        enterCountdown = Math.max(1, c.getInt("dungeon.enter-countdown", 5));
        restTime = Math.max(0, c.getInt("dungeon.rest-time", 30));

        String mat = c.getString("dungeon.ticket-material", "NETHER_STAR");
        ticketMaterial = parseMaterial(mat, Material.NETHER_STAR);

        arenaAutoBuild = c.getBoolean("arena.auto-build", true);
        arenaSize = Math.max(16, c.getInt("arena.size", 48));
        arenaFloorY = c.getInt("arena.floor-y", 64);
        arenaWallHeight = Math.max(2, c.getInt("arena.wall-height", 44));
        arenaClearAbove = Math.max(2, c.getInt("arena.clear-above", 60));

        stageFile = firstValue(c.getConfigurationSection("stages"), "dungeon1.yml");
        rewardFile = firstValue(c.getConfigurationSection("rewards"), "dungeon1.yml");
    }

    /** 取 stages / rewards 段下的第一个文件名（MVP 只有一个副本，够用） */
    private static String firstValue(org.bukkit.configuration.ConfigurationSection sec, String def) {
        if (sec == null) {
            return def;
        }
        for (String key : sec.getKeys(false)) {
            String v = sec.getString(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return def;
    }

    private static Material parseMaterial(String name, Material def) {
        if (name == null) {
            return def;
        }
        Material m = Material.matchMaterial(name);
        return m == null ? def : m;
    }
}
