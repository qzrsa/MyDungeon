package cn.qzrs.dungeon.listener;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.arena.ArenaBuilder;
import cn.qzrs.dungeon.instance.DungeonInstance;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

/**
 * 副本边界守卫 + 生命周期钩子。
 */
public class DungeonListener implements Listener {

    private final MyDungeon plugin;

    public DungeonListener(MyDungeon plugin) {
        this.plugin = plugin;
    }

    /** 副本怪死亡 → 清掉落、推进阶段 */
    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        DungeonInstance inst = plugin.instances().byMob(id);
        if (inst == null) {
            return;
        }
        // 副本怪不掉原版掉落物，奖励统一由 RewardManager 在通关时发放
        e.getDrops().clear();
        e.setDroppedExp(0);
        plugin.instances().unregisterMob(id);
        plugin.stages().onMobDeath(inst, id);
    }

    /** 出界 / 掉出场地 → 拉回落点 */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (plugin.instances().byPlayer(p.getUniqueId()) == null) {
            return;
        }
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        double r = plugin.config().boundaryRadius;
        boolean outside = Math.abs(to.getX()) > r
                || Math.abs(to.getZ()) > r
                || to.getY() < plugin.config().arenaFloorY - 20;
        if (outside) {
            World w = plugin.dungeonWorld();
            if (w != null) {
                p.teleport(ArenaBuilder.spawnLocation(w));
            }
        }
    }

    /** 掉线 → 从副本成员里摘掉；全员离线则结束本场 */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        DungeonInstance inst = plugin.instances().byPlayer(p.getUniqueId());
        if (inst == null) {
            return;
        }
        inst.members.remove(p.getUniqueId());
        inst.returnPoints.remove(p.getUniqueId());
        // 还原重生点
        Location bed = inst.bedSpawns.remove(p.getUniqueId());
        if (bed != null) {
            p.setBedSpawnLocation(bed, true);
        } else {
            p.setBedSpawnLocation(null, true);
        }
        if (inst.bossBar != null) {
            inst.bossBar.removePlayer(p);
        }
        if (inst.board != null) {
            inst.board.hide(p);
        }
        inst.forgetPlayer(p.getUniqueId());
        if (inst.members.isEmpty()) {
            plugin.endInstance(inst, "全员离线");
        }
    }

    /** 复活点修正：副本内死亡后直接退出副本，复活交给其他插件/原版处理 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        DungeonInstance inst = plugin.instances().byPlayer(p.getUniqueId());
        if (inst == null) {
            return;
        }
        // 死了就摘出副本：还原重生点、送回进本前位置，之后复活点完全交给其他插件接管
        Location back = inst.returnPoints.remove(p.getUniqueId());
        inst.members.remove(p.getUniqueId());
        Location bed = inst.bedSpawns.remove(p.getUniqueId());
        if (bed != null) {
            p.setBedSpawnLocation(bed, true);
        } else {
            p.setBedSpawnLocation(null, true);
        }
        if (inst.bossBar != null) {
            inst.bossBar.removePlayer(p);
        }
        if (inst.board != null) {
            inst.board.hide(p);
        }
        inst.forgetPlayer(p.getUniqueId());
        if (back != null) {
            e.setRespawnLocation(back);
        }
        p.sendMessage(org.bukkit.ChatColor.RED + "你在副本中阵亡，已被送出。");
        if (inst.members.isEmpty()) {
            plugin.endInstance(inst, "全员阵亡");
        }
    }

    // ==================================================================
    // 副本世界保护：禁止破坏 / 放置 / 倒液体 / 爆炸毁方块
    // 持有 dungeon.build 权限者（默认 op）可绕过，用于建地图。
    // ==================================================================

    private boolean canEdit(Player p) {
        if (!plugin.isDungeonWorld(p.getWorld())) {
            return true;
        }
        return p.hasPermission("dungeon.build");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (!canEdit(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!canEdit(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!canEdit(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    /** 怪物/TNT 等实体爆炸不破坏方块 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!plugin.isDungeonWorld(e.getLocation().getWorld())) {
            return;
        }
        e.blockList().clear();
    }

    /** 方块爆炸（如床/重生锚/自设 TNT）不破坏方块 */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!plugin.isDungeonWorld(e.getBlock().getWorld())) {
            return;
        }
        e.blockList().clear();
    }

    /** 阻止外部传送（/back、/tp、/home 等）进入副本世界 */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location to = e.getTo();
        if (to == null || !plugin.isDungeonWorld(to.getWorld())) {
            return;
        }
        if (plugin.instances().byPlayer(e.getPlayer().getUniqueId()) != null) {
            return;   // 副本成员（进入 / 复活 / 边界拉回）放行
        }
        e.setCancelled(true);
        e.getPlayer().sendMessage(ChatColor.RED + "你无法传送到副本世界，请使用 /dungeon enter。");
    }
}
