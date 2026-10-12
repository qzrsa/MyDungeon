package cn.qzrs.dungeon.mob;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.PluginConfig;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 按模板生成副本怪。
 *
 * <p>关键点：把 GENERIC_MAX_HEALTH 直接设为模板血量（千级），
 * 从根上脱离原版 20 血框架；护甲/攻击同时写入 Attribute，
 * 但副本内实际生效的是 CombatEngine 读模板值算出的 RPG 伤害。
 */
public final class MobSpawner {

    private MobSpawner() {
    }

    /**
     * 生成副本怪。
     *
     * @param layer 所在层数（从 1 开始）—— 用于按层叠加保底血量
     */
    public static DungeonMob spawn(Location loc, MobTemplate t, int layer) {
        PluginConfig cfg = MyDungeon.get().config();
        // 按层递增的保底血量：第 N 层每只怪额外加 N × layer-health-step，
        // 所以第 1 层保底 1W、第 2 层 2W …… 第 10 层 10W。
        // BOSS 的这部分加成再乘 boss-health-bonus（默认 3 倍），让首领明显更肉。
        int n = Math.max(1, layer);
        double layerBonus = n * cfg.layerHealthStep
                * (t.boss ? cfg.bossHealthBonus : 1.0);
        double hp = Math.max(1.0, t.health * cfg.mobHealthScale + layerBonus);
        double dmg = Math.max(0.0, t.damage * cfg.mobDamageScale);

        World w = loc.getWorld();
        if (w == null) {
            return null;
        }

        Entity raw = w.spawnEntity(loc, t.entityType);
        if (!(raw instanceof LivingEntity le)) {
            raw.remove();
            return null;
        }

        le.setCustomName(ChatColor.translateAlternateColorCodes('&', t.name));
        le.setCustomNameVisible(true);
        le.setRemoveWhenFarAway(false);
        le.setPersistent(true);
        le.setCanPickupItems(false);

        setAttr(le, Attribute.GENERIC_MAX_HEALTH, hp);
        le.setHealth(Math.min(hp, maxHealth(le)));

        if (t.armor > 0) {
            setAttr(le, Attribute.GENERIC_ARMOR, t.armor);
        }
        if (dmg > 0) {
            setAttr(le, Attribute.GENERIC_ATTACK_DAMAGE, dmg);
        }
        if (t.speed > 0) {
            setAttr(le, Attribute.GENERIC_MOVEMENT_SPEED, t.speed);
        }

        applyBuffs(le, t, n);

        return new DungeonMob(le, t, hp, dmg);
    }

    /**
     * 给怪挂永久药水效果：模板自带 buff + 当前层数档位的 buff，同一种取更高级别。
     *
     * <p>持续时间用 {@code Integer.MAX_VALUE}（约 3.4 年），等于无限；
     * 瞬时类效果（如 instant_health）没有意义，直接跳过。
     * 等级 0 = I 级，与原版的 amplifier 一致。
     */
    private static void applyBuffs(LivingEntity le, MobTemplate t, int layer) {
        PluginConfig cfg = MyDungeon.get().config();
        Map<String, Integer> merged = new LinkedHashMap<>(tierBuffsFor(cfg, layer));
        for (Map.Entry<String, Integer> e : t.buffs.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), Math::max);
        }
        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            PotionEffectType type = resolveEffect(e.getKey());
            if (type == null || type.isInstant()) {
                continue;
            }
            le.addPotionEffect(new PotionEffect(
                    type, Integer.MAX_VALUE, e.getValue(), false, false, false));
        }
    }

    /** 取「起始层 <= layer」里起始层最大的那一档 buff 表 */
    private static Map<String, Integer> tierBuffsFor(PluginConfig cfg, int layer) {
        Map<String, Integer> best = Collections.emptyMap();
        int bestFrom = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Map<String, Integer>> e : cfg.tierBuffs.entrySet()) {
            if (e.getKey() <= layer && e.getKey() > bestFrom) {
                bestFrom = e.getKey();
                best = e.getValue();
            }
        }
        return best;
    }

    /** 把 "speed" / "fire_resistance" 这类名字解析成药水效果类型 */
    private static PotionEffectType resolveEffect(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(key));
        if (type != null) {
            return type;
        }
        return PotionEffectType.getByName(key.toUpperCase(Locale.ROOT));
    }

    private static void setAttr(LivingEntity le, Attribute attr, double value) {
        AttributeInstance inst = le.getAttribute(attr);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    private static double maxHealth(LivingEntity le) {
        AttributeInstance inst = le.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return inst == null ? le.getHealth() : inst.getValue();
    }
}
