package cn.qzrs.dungeon.mob;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.LinkedHashMap;
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

    public MobTemplate(String id, String name, EntityType entityType,
                       double health, double damage, double armor, double speed, boolean boss) {
        this.id = id;
        this.name = name;
        this.entityType = entityType;
        this.health = health;
        this.damage = damage;
        this.armor = armor;
        this.speed = speed;
        this.boss = boss;
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
                    s.getBoolean("boss", false)
            ));
        }
        return map;
    }
}
