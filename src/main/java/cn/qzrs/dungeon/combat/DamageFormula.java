package cn.qzrs.dungeon.combat;

/**
 * RPG 伤害公式 —— 副本内战斗的唯一计算入口。
 *
 * <p>设计目的：绕开原版「每点护甲 4% 减伤、20 点封顶 80%」的硬上限，
 * 以及 Apotheosis 伤害减免类词缀的乘法叠加。改为：
 *
 * <pre>
 *     减伤率   = armor / (armor + K)
 *     最终伤害 = 攻击力 × (1 − 减伤率) × 全局倍率
 * </pre>
 *
 * <p>K 越大，同样护甲值换来的减伤越少 → 怪越好打。
 * 这条公式永不"封顶"，但随 armor 增大而平滑趋近 100%，因此不存在
 * 原版那种"堆到某个值之后再加完全无效"的断崖。
 */
public final class DamageFormula {

    private DamageFormula() {
    }

    /**
     * 减伤率，取值区间 [0, 1)。
     *
     * @param armor 防御点数（玩家取 Attribute.ARMOR，怪取模板 armor）
     * @param k     公式常数，来自 config.yml 的 combat.armor-k
     */
    public static double reduction(double armor, double k) {
        if (armor <= 0.0 || k <= 0.0) {
            return 0.0;
        }
        return armor / (armor + k);
    }

    /**
     * 最终伤害，带最小值保底（避免减伤后变成 0，失去手感）。
     */
    public static double compute(double attack, double armor, double k, double multiplier, double minDamage) {
        double raw = attack * (1.0 - reduction(armor, k)) * multiplier;
        return Math.max(minDamage, raw);
    }
}
