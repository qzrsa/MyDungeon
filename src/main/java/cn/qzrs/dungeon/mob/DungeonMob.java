package cn.qzrs.dungeon.mob;

import org.bukkit.entity.LivingEntity;

/**
 * 运行时的副本怪实例：把 Bukkit 实体和它对应的模板/实际数值绑在一起。
 * 所有实例都登记在所属 DungeonInstance 的 liveMobs 里，用于推进阶段和找怪。
 */
public class DungeonMob {

    public final LivingEntity entity;
    public final MobTemplate template;
    /** 实际血量（模板 health × config mobs.health-scale） */
    public final double scaledHealth;
    /** 实际攻击力（模板 damage × config mobs.damage-scale） */
    public final double scaledDamage;

    public DungeonMob(LivingEntity entity, MobTemplate template, double scaledHealth, double scaledDamage) {
        this.entity = entity;
        this.template = template;
        this.scaledHealth = scaledHealth;
        this.scaledDamage = scaledDamage;
    }
}
