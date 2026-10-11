package cn.qzrs.dungeon.arena;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;

/**
 * 测试场生成器（MVP 用）。
 *
 * <p>副本世界运行在 {@link VoidChunkGenerator} 上 —— 真虚空、零地形开销，
 * 区块生成不会产生任何方块或实体。场地由本类手工铺设：
 * 正方形石砖地板 + 一圈围墙 + 照明，天然形成封闭场地，不需要任何地图文件。
 *
 * <p>等手感验收通过，把 config.yml 的 arena.auto-build 关掉，换成正式地图即可。
 */
public final class ArenaBuilder {

    private ArenaBuilder() {
    }

    /** 确保副本世界存在；不存在则用虚空生成器创建一个空世界。 */
    public static World ensureWorld(String name) {
        World w = Bukkit.getWorld(name);
        if (w != null) {
            if (!(w.getGenerator() instanceof VoidChunkGenerator)) {
                MyDungeon.get().getLogger().warning("副本世界 " + name
                        + " 不是虚空生成器创建的。想省资源的话：停服 → 删除服务端根目录下的 "
                        + name + " 文件夹 → 重启，插件会用虚空重新生成。");
            }
            return w;
        }
        PluginConfig cfg = MyDungeon.get().config();

        WorldCreator wc = new WorldCreator(name);
        wc.type(WorldType.NORMAL);
        wc.generateStructures(false);
        wc.environment(World.Environment.NORMAL);
        // 真·虚空生成器：不产生任何方块/结构/生物，区块生成开销接近 0
        wc.generator(new VoidChunkGenerator());

        w = wc.createWorld();
        if (w == null) {
            return null;
        }

        w.setDifficulty(Difficulty.NORMAL);
        w.setSpawnLocation((int) cfg.spawnX, (int) cfg.spawnY, (int) cfg.spawnZ);
        w.setTime(6000L);
        w.setStorm(false);
        // 出生点区块不常驻内存：虚空世界没人时几乎零开销
        w.setKeepSpawnInMemory(false);
        applyGameRules(w);
        return w;
    }

    public static void applyGameRules(World w) {
        // 常昼、不刷天气、不刷原版怪 —— 副本里的怪全部由本插件生成
        w.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
        w.setGameRule(org.bukkit.GameRule.MOB_GRIEFING, false);
        w.setGameRule(org.bukkit.GameRule.DO_FIRE_TICK, false);
    }

    /** 生成测试场地：地板 + 围墙 + 照明。幂等，可重复调用。 */
    public static void build(World w) {
        PluginConfig cfg = MyDungeon.get().config();
        int size = Math.max(16, cfg.arenaSize);
        int half = size / 2;
        int floorY = cfg.arenaFloorY;
        int wallTop = floorY + cfg.arenaWallHeight;
        int clearTop = floorY + cfg.arenaClearAbove;

        // 1) 清空场地并铺地板
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                for (int y = floorY + 1; y <= clearTop; y++) {
                    set(w, x, y, z, Material.AIR);
                }
                set(w, x, floorY, z, Material.STONE_BRICKS);
            }
        }

        // 2) 围墙
        for (int y = floorY + 1; y <= wallTop; y++) {
            for (int i = -half; i <= half; i++) {
                set(w, i, y, -half, Material.GLASS);
                set(w, i, y, half, Material.GLASS);
                set(w, -half, y, i, Material.GLASS);
                set(w, half, y, i, Material.GLASS);
            }
        }

        // 3) 照明：沿四边每 8 格放一盏海晶灯（藏在围墙顶下方，避免刺眼）
        int step = 8;
        Material lamp = Material.SEA_LANTERN;
        for (int i = -half + step; i <= half - step; i += step) {
            set(w, i, wallTop, -half, lamp);
            set(w, i, wallTop, half, lamp);
            set(w, -half, wallTop, i, lamp);
            set(w, half, wallTop, i, lamp);
        }

        // 4) 中心台座（视觉参照物，方便判断距离）
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                set(w, x, floorY + 1, z, Material.CHISELED_STONE_BRICKS);
            }
        }
    }

    private static void set(World w, int x, int y, int z, Material mat) {
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() != mat) {
            b.setType(mat, false);
        }
    }

    /** 副本世界内的传送落点 */
    public static Location spawnLocation(World w) {
        PluginConfig cfg = MyDungeon.get().config();
        return new Location(w, cfg.spawnX, cfg.spawnY, cfg.spawnZ);
    }
}
