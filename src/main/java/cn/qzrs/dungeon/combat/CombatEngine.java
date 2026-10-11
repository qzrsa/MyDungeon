package cn.qzrs.dungeon.combat;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.PluginConfig;
import cn.qzrs.dungeon.mob.DungeonMob;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 战斗接管 —— 副本内所有伤害都从这里走。
 *
 * <p>原理：Bukkit 的 damage 事件触发时，原版护甲/附魔减伤已经算完，
 * 因此我们直接用 {@code setDamage()} 覆盖成自己公式算出的值，
 * 就等价于"绕过原版那套 80% 封顶 + 神化乘法叠加"。
 *
 * <p>方向一（玩家 → 怪）：攻击力取玩家的 GENERIC_ATTACK_DAMAGE 属性
 * （已含神化/拔刀剑加成），防御取怪模板 armor。
 * <br>方向二（怪 → 玩家）：攻击力取怪模板 damage（含全局缩放），
 * 防御取玩家的 GENERIC_ARMOR 属性。
 *
 * <p>副作用为零：只有副本世界内的副本怪参与计算，世界上其他地方照旧。
 */
public class CombatEngine implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        MyDungeon plugin = MyDungeon.get();
        if (!plugin.isDungeonWorld(e.getEntity().getWorld())) {
            return;
        }

        PluginConfig cfg = plugin.config();
        Entity damager = resolveDamager(e.getDamager());
        Entity victim = e.getEntity();
        if (damager == null) {
            return;
        }

        // ① 玩家 → 副本怪
        if (victim instanceof LivingEntity mobEntity && damager instanceof Player attacker) {
            DungeonMob mob = plugin.instances().mob(mobEntity.getUniqueId());
            if (mob == null) {
                return;
            }
            double atk = attributeOf(attacker, Attribute.GENERIC_ATTACK_DAMAGE, 1.0);
            double dmg = DamageFormula.compute(atk, mob.template.armor, cfg.armorK,
                    cfg.damageMultiplier, cfg.minDamage);
            e.setDamage(dmg);
            return;
        }

        // ② 副本怪 → 玩家
        if (victim instanceof Player defender && damager instanceof LivingEntity le) {
            DungeonMob mob = plugin.instances().mob(le.getUniqueId());
            if (mob == null) {
                return;
            }
            double armor = attributeOf(defender, Attribute.GENERIC_ARMOR, 0.0);
            double dmg = DamageFormula.compute(mob.scaledDamage, armor, cfg.armorK,
                    cfg.damageMultiplier, cfg.minDamage);
            e.setDamage(dmg);
        }
    }

    /**
     * 副本怪护盾：只接受来自玩家的伤害，其余一切伤害源全部免除。
     *
     * <p>覆盖范围：阳光燃烧、岩浆、摔落、窒息、溺水、VEX 的自然衰减、
     * 苦力怕互炸，以及任何非玩家来源的伤害。这样怪不会"自己消失"，
     * 阶段推进只可能由玩家击杀触发。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobShield(EntityDamageEvent e) {
        MyDungeon plugin = MyDungeon.get();
        if (!plugin.isDungeonWorld(e.getEntity().getWorld())) {
            return;
        }
        if (plugin.instances().byMob(e.getEntity().getUniqueId()) == null) {
            return;
        }
        if (e instanceof EntityDamageByEntityEvent byEntity
                && resolveDamager(byEntity.getDamager()) instanceof Player) {
            // 玩家造成的伤害交给 onEntityDamageByEntity 走 RPG 公式
            return;
        }
        e.setCancelled(true);
    }

    /** 箭矢/投掷物伤害追到发射者 */
    private static Entity resolveDamager(Entity damager) {
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    private static double attributeOf(LivingEntity le, Attribute attr, double fallback) {
        AttributeInstance inst = le.getAttribute(attr);
        return inst == null ? fallback : inst.getValue();
    }
}
