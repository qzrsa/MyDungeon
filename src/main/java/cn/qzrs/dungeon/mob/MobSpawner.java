package cn.qzrs.dungeon.mob;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.PluginConfig;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

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

    public static DungeonMob spawn(Location loc, MobTemplate t) {
        PluginConfig cfg = MyDungeon.get().config();
        double hp = Math.max(1.0, t.health * cfg.mobHealthScale);
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

        return new DungeonMob(le, t, hp, dmg);
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
