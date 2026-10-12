package cn.qzrs.dungeon.mob;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 怪模板 —— 一个模板描述一种副本怪的静态数值。
 * 血量/攻击在生成时会再乘一次 config.yml 的 mobs.*-scale。
 */
public class MobTemplate {

    public final String id;
    public final String name;
    public final EntityType entityType;
    public final double health;
    public final double damage;
    public final double armor;
    public final double speed;
    public final boolean boss;
    /** 永久携带的药水效果：效果名（小写，如 speed / resistance）→ 等级（0 = I 级） */
    public final Map<String, Integer> buffs;

    public MobTemplate(String id, String name, EntityType entityType,
                       double health, double damage, double armor, double speed, boolean boss,
                       Map<String, Integer> buffs) {
        this.id = id;
        this.name = name;
        this.entityType = entityType;
        this.health = health;
        this.damage = damage;
        this.armor = armor;
        this.speed = speed;
        this.boss = boss;
        this.buffs = buffs;
    }

    /** 从 mobs.yml 的 templates 段读出全部模板；解析失败的条目会被跳过并打印原因。 */
    public static Map<String, MobTemplate> loadAll(FileConfiguration cfg) {
        Map<String, MobTemplate> map = new LinkedHashMap<>();
        ConfigurationSection root = cfg.getConfigurationSection("templates");
        if (root == null) {
            return map;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            String rawType = s.getString("entity", "ZOMBIE");
            EntityType type;
            try {
                type = EntityType.valueOf(rawType.toUpperCase());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            map.put(id.toLowerCase(), new MobTemplate(
                    id.toLowerCase(),
                    s.getString("name", id),
                    type,
                    s.getDouble("health", 100.0),
                    s.getDouble("damage", 10.0),
                    s.getDouble("armor", 0.0),
                    s.getDouble("speed", 0.23),
                    s.getBoolean("boss", false),
                    readBuffs(s)
            ));
        }
        return map;
    }

    /**
     * 读 mobs.yml 里的 buffs 段。写成 YAML 映射最自然：
     * <pre>
     *   buffs:
     *     speed: 1              # 速度 II
     *     resistance: 0         # 抗性提升 I
     *     fire_resistance: 0
     * </pre>
     * 等级从 0 起（和原版的 amplifier 一致），0 就是 I 级。
     */
    private static Map<String, Integer> readBuffs(ConfigurationSection s) {
        Map<String, Integer> buffs = new LinkedHashMap<>();
        ConfigurationSection bs = s.getConfigurationSection("buffs");
        if (bs == null) {
            return buffs;
        }
        for (String effect : bs.getKeys(false)) {
            buffs.put(effect.toLowerCase(Locale.ROOT), Math.max(0, bs.getInt(effect)));
        }
        return buffs;
    }
}
