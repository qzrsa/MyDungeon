package cn.qzrs.dungeon;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
    /**
     * 怪身上「抗性提升（resistance）」每级额外减伤的比例。
     * <p>★ 副本内伤害由本插件的引擎 setDamage() 覆盖，原版的抗性提升不会自动生效，
     * 必须在这里手动折算 —— 否则给怪配了 resistance 也白配。
     */
    public double buffResistancePerLevel = 0.15;
    /** 怪身上「力量（strength）」每级额外增伤的比例，同上需手动折算 */
    public double buffStrengthPerLevel = 0.15;

    // ---------- mobs ----------
    public double mobHealthScale = 1.0;
    public double mobDamageScale = 1.0;
    /**
     * 按层递增的保底血量：第 N 层（从 1 起）每只怪在模板血量之外再加 N × 本值。
     * 默认 5000 是按 20 层调的（第 20 层 10W/只）；层数少时可以调大。
     */
    public double layerHealthStep = 5000.0;
    /** BOSS 的层数加成额外乘这个倍率 —— 让首领明显比同层杂兵更肉 */
    public double bossHealthBonus = 3.0;
    /**
     * 按层数档位给怪叠加的药水效果。key = 起始层数，取「不超过当前层的最大档」。
     * <pre>
     *   tier-buffs:
     *     6:  { fire_resistance: 0 }
     *     11: { fire_resistance: 0, speed: 0 }
     * </pre>
     */
    public final Map<Integer, Map<String, Integer>> tierBuffs = new LinkedHashMap<>();

    // ---------- dungeon ----------
    public String worldName = "dungeon_01";
    public int slots = 1;
    public double spawnX = 0.5;
    public double spawnY = 66.0;
    public double spawnZ = 0.5;
    public double boundaryRadius = 60.0;
    public boolean requireTicket = false;
    public Material ticketMaterial = Material.NETHER_STAR;
    /** 进入副本倒计时秒数 */
    public int enterCountdown = 5;
    /** 每层结束后的修整秒数 */
    public int restTime = 30;
    /** 是否禁止副本内的技能浮空（拔刀剑 Trick Up / 空中缓降等） */
    public boolean antiFloat = true;
    /**
     * 是否在副本内强制关闭飞行（含 mod 喷气背包）。
     * <p>Mekanism 等喷气背包的推力逻辑依赖 Abilities.flying == true 才触发，
     * 每 tick 把飞行能力硬关掉即可让喷气背包失效。
     */
    public boolean antiFly = true;
    /** 连续滞空超过该 tick 数即判定为浮空并强制压回地面（20 tick = 1 秒） */
    public int antiFloatAirTicks = 15;
    /**
     * 反浮空「强禁」模式：只要脚底离地超过这个高度（格）就持续向下压，
     * 不再依赖「Y 速度不够负」—— 拔刀剑剑技会每 tick 用 setDeltaMovement 刷新速度，
     * 靠速度判断会漏掉。0 = 关闭高度强压，退回旧逻辑。
     */
    public double antiFloatMaxHeight = 2.5;
    /** 强禁模式下每 tick 施加的向下速度（负值，越大压得越狠） */
    public double antiFloatPullSpeed = -1.2;
    /** 判断「脚底离地」用的地面高度（= 场地地板 Y + 1，站上去脚在 y+1） */
    public int antiFloatGroundY = 65;
    /** 剩余怪数 <= 该值时给怪加发光，方便玩家找到最后几只（0 = 关闭） */
    public int mobGlowWhenLeft = 5;

    // ---------- 伤害统计计分板 ----------
    /** 是否在副本内给成员开启右侧伤害统计计分板 */
    public boolean boardEnabled = true;
    /** 排行榜最多显示几名（含 BOSS 血条在内的 sidebar 总行数上限是 15） */
    public int boardTop = 8;
    /** 计分板刷新间隔（tick，20 = 1 秒） */
    public int boardRefreshTicks = 10;
    /** 秒伤统计的滑动窗口秒数 */
    public int dpsWindowSeconds = 5;
    /**
     * 计分板标题，支持占位符 {layer} / {total}（当前层 / 总层数）。
     * 用 {@code \n} 可换行（第二行起为副标题）。
     */
    public String boardTitle = "&6&l遗忘地窟 &7· &f第 {layer}/{total} 层";

    // ---------- arena ----------
    public boolean arenaAutoBuild = true;
    public int arenaSize = 48;
    public int arenaFloorY = 64;
    public int arenaWallHeight = 28;
    public int arenaClearAbove = 44;

    // ---------- 数据文件 ----------
    public String stageFile = "dungeon1.yml";
    public String rewardFile = "dungeon1.yml";

    public void load(FileConfiguration c) {
        armorK = c.getDouble("combat.armor-k", 100.0);
        damageMultiplier = c.getDouble("combat.damage-multiplier", 1.0);
        minDamage = c.getDouble("combat.min-damage", 1.0);
        blockApothReduction = c.getBoolean("combat.block-apoth-damage-reduction", false);
        buffResistancePerLevel = clamp01(c.getDouble("combat.buff-resistance-per-level", 0.15));
        buffStrengthPerLevel = Math.max(0.0, c.getDouble("combat.buff-strength-per-level", 0.15));

        mobHealthScale = c.getDouble("mobs.health-scale", 1.0);
        mobDamageScale = c.getDouble("mobs.damage-scale", 1.0);
        layerHealthStep = Math.max(0.0, c.getDouble("mobs.layer-health-step", 5000.0));
        bossHealthBonus = Math.max(1.0, c.getDouble("mobs.boss-health-bonus", 3.0));
        loadTierBuffs(c);

        worldName = c.getString("dungeon.world", "dungeon_01");
        slots = Math.max(1, c.getInt("dungeon.slots", 1));
        spawnX = c.getDouble("dungeon.spawn.x", 0.5);
        spawnY = c.getDouble("dungeon.spawn.y", 66.0);
        spawnZ = c.getDouble("dungeon.spawn.z", 0.5);
        boundaryRadius = c.getDouble("dungeon.boundary-radius", 60.0);
        requireTicket = c.getBoolean("dungeon.require-ticket", false);
        enterCountdown = Math.max(1, c.getInt("dungeon.enter-countdown", 5));
        restTime = Math.max(0, c.getInt("dungeon.rest-time", 30));
        antiFloat = c.getBoolean("dungeon.anti-float", true);
        antiFly = c.getBoolean("dungeon.anti-fly", true);
        antiFloatAirTicks = Math.max(5, c.getInt("dungeon.anti-float-air-ticks", 15));
        antiFloatMaxHeight = c.getDouble("dungeon.anti-float-max-height", 2.5);
        antiFloatPullSpeed = c.getDouble("dungeon.anti-float-pull-speed", -1.2);
        // 地面高度默认跟场地地板走（地板 y + 1），除非显式配了
        antiFloatGroundY = c.getInt("dungeon.anti-float-ground-y", arenaFloorY + 1);
        mobGlowWhenLeft = Math.max(0, c.getInt("dungeon.mob-glow-when-left", 5));

        String mat = c.getString("dungeon.ticket-material", "NETHER_STAR");
        ticketMaterial = parseMaterial(mat, Material.NETHER_STAR);

        boardEnabled = c.getBoolean("scoreboard.enabled", true);
        boardTop = Math.max(1, Math.min(14, c.getInt("scoreboard.top", 8)));
        boardRefreshTicks = Math.max(4, c.getInt("scoreboard.refresh-ticks", 10));
        dpsWindowSeconds = Math.max(1, Math.min(30, c.getInt("scoreboard.dps-window-seconds", 5)));
        boardTitle = c.getString("scoreboard.title", "&6&l遗忘地窟 &7· &f第 {layer}/{total} 层");

        arenaAutoBuild = c.getBoolean("arena.auto-build", true);
        arenaSize = Math.max(16, c.getInt("arena.size", 48));
        arenaFloorY = c.getInt("arena.floor-y", 64);
        arenaWallHeight = Math.max(2, c.getInt("arena.wall-height", 28));
        arenaClearAbove = Math.max(2, c.getInt("arena.clear-above", 44));

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

    /** 读 mobs.tier-buffs：起始层 → 药水效果表（效果名 → 等级） */
    private void loadTierBuffs(FileConfiguration c) {
        tierBuffs.clear();
        ConfigurationSection root = c.getConfigurationSection("mobs.tier-buffs");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            int from;
            try {
                from = Integer.parseInt(key.trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            Map<String, Integer> effects = new LinkedHashMap<>();
            for (String effect : sec.getKeys(false)) {
                effects.put(effect.toLowerCase(Locale.ROOT), Math.max(0, sec.getInt(effect)));
            }
            tierBuffs.put(from, effects);
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static Material parseMaterial(String name, Material def) {
        if (name == null) {
            return def;
        }
        Material m = Material.matchMaterial(name);
        return m == null ? def : m;
    }
}
