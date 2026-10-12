package cn.qzrs.dungeon.command;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.arena.ArenaBuilder;
import cn.qzrs.dungeon.board.DamageBoard;
import cn.qzrs.dungeon.instance.DungeonInstance;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /dungeon enter|cancel|leave|info|reload
 */
public class DungeonCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("enter", "cancel", "leave", "info", "reload");

    private final MyDungeon plugin;
    /** 正在等待进入副本倒计时的玩家 */
    private final Map<UUID, BukkitTask> pendingEnter = new HashMap<>();

    public DungeonCommand(MyDungeon plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "enter" -> {
                return enter(sender);
            }
            case "cancel" -> {
                return cancel(sender);
            }
            case "leave" -> {
                return leave(sender);
            }
            case "info" -> {
                info(sender);
                return true;
            }
            case "reload" -> {
                return reload(sender);
            }
            default -> {
                help(sender, label);
                return true;
            }
        }
    }

    private boolean enter(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("该命令只能由玩家执行。");
            return true;
        }
        if (!p.hasPermission("dungeon.use")) {
            p.sendMessage(ChatColor.RED + "你没有权限进入副本。");
            return true;
        }
        if (plugin.instances().byPlayer(p.getUniqueId()) != null) {
            p.sendMessage(ChatColor.RED + "你已经在副本里了，先用 /dungeon leave 出来。");
            return true;
        }
        if (pendingEnter.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "正在等待进入副本，可用 /dungeon cancel 取消。");
            return true;
        }
        World w = plugin.dungeonWorld();
        if (w == null) {
            p.sendMessage(ChatColor.RED + "副本世界未就绪，请查看服务端控制台报错。");
            return true;
        }

        int countdown = plugin.config().enterCountdown;
        // ★ 公屏公告：这个玩家是「当前没有任何人倒计时」的第一个开启者时，广播全场
        boolean first = pendingEnter.isEmpty();
        if (first) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "⚔ " + ChatColor.AQUA + p.getName()
                    + ChatColor.GOLD + " 开启了试炼挑战！"
                    + ChatColor.GREEN + countdown + " 秒内"
                    + ChatColor.YELLOW + "可输入 /dungeon enter 加入。");
        }
        pendingEnter.put(p.getUniqueId(), null);
        p.sendMessage(ChatColor.GREEN + "副本将在 " + countdown + " 秒后开始，"
                + ChatColor.GRAY + "输入 /dungeon cancel 或点击下方按钮可取消。");

        final int[] left = {countdown};
        final java.util.concurrent.atomic.AtomicReference<BukkitTask> holder = new java.util.concurrent.atomic.AtomicReference<>();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            left[0]--;
            if (left[0] <= 0) {
                BukkitTask self = holder.get();
                if (self != null) {
                    self.cancel();
                }
                pendingEnter.remove(p.getUniqueId());
                if (p.isOnline()) {
                    doEnter(p);
                }
            } else {
                p.sendTitle(ChatColor.GOLD.toString() + left[0], ChatColor.GRAY + "即将进入副本", 0, 25, 5);
                // 倒计时提示 + 可点击的 [取消进入]
                TextComponent line = new TextComponent(ChatColor.YELLOW + "进入副本倒计时：" + left[0] + " 秒  ");
                TextComponent btn = new TextComponent(ChatColor.RED + "[取消进入]");
                btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dungeon cancel"));
                btn.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new TextComponent[]{new TextComponent(ChatColor.GRAY + "点击取消进入副本")}));
                line.addExtra(btn);
                p.spigot().sendMessage(line);
            }
        }, 20L, 20L);
        holder.set(task);
        pendingEnter.put(p.getUniqueId(), task);
        return true;
    }

    /** 取消进入倒计时（指令 + 点击消息共用） */
    private boolean cancel(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("该命令只能由玩家执行。");
            return true;
        }
        BukkitTask task = pendingEnter.remove(p.getUniqueId());
        if (task == null) {
            p.sendMessage(ChatColor.RED + "你当前没有待进入的副本倒计时。");
            return true;
        }
        task.cancel();
        p.sendMessage(ChatColor.YELLOW + "已取消进入副本。");
        return true;
    }

    /** 玩家掉线 / 插件卸载时，静默取消他的倒计时任务（供监听器调用） */
    public void cancelPending(UUID uuid) {
        BukkitTask task = pendingEnter.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /** 倒计时结束后的真正进本流程 */
    private void doEnter(Player p) {
        if (plugin.instances().byPlayer(p.getUniqueId()) != null) {
            return;
        }
        World w = plugin.dungeonWorld();
        if (w == null) {
            p.sendMessage(ChatColor.RED + "副本世界未就绪。");
            return;
        }

        // 优先找空闲槽位开新副本；没有空闲但「前五关进行中」的副本可中途加入（组队）
        DungeonInstance slot = plugin.instances().findFree();
        boolean joining = false;
        if (slot == null) {
            slot = plugin.instances().findJoinable();
            if (slot != null) {
                joining = true;
            }
        }
        if (slot == null) {
            p.sendMessage(ChatColor.RED + "副本已进行到第 6 关及以后，无法中途加入，请等本场结束。");
            return;
        }
        if (plugin.config().requireTicket && !consumeTicket(p)) {
            p.sendMessage(ChatColor.RED + "进入副本需要门票：" + plugin.config().ticketMaterial.name());
            return;
        }

        slot.returnPoints.put(p.getUniqueId(), p.getLocation().clone());
        // 记录并重设重生点：副本世界是虚空生成器造的，若玩家在这里死亡，
        // 原版 respawn 的 copyRespawnPosition 会因"重生世界为 null"抛 NPE。
        // 把重生点 force 钉到副本场地，死亡时 newWorld 就是副本世界（非 null）。
        slot.bedSpawns.put(p.getUniqueId(), p.getBedSpawnLocation());
        p.setBedSpawnLocation(ArenaBuilder.spawnLocation(w), true);
        slot.members.add(p.getUniqueId());
        // 副本禁飞：记录原飞行权限，离开时还原
        slot.allowFlightBefore.put(p.getUniqueId(), p.getAllowFlight());
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setGliding(false);

        p.teleport(ArenaBuilder.spawnLocation(w));
        AttributeInstance hp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (hp != null) {
            p.setHealth(hp.getValue());
        }
        p.setFireTicks(0);

        if (joining) {
            // 中途加入：不重置副本，只把人拉进场、挂上计分板
            p.sendMessage(ChatColor.GREEN + "你已加入正在进行的副本。");
            showBoard(slot, p);
        } else {
            plugin.stages().start(slot);
            showBoard(slot, p);
        }
    }

    /** 副本开始后给成员挂伤害统计计分板（懒创建，复用同一块） */
    private void showBoard(DungeonInstance slot, Player p) {
        if (!plugin.config().boardEnabled) {
            return;
        }
        if (slot.board == null) {
            slot.board = new DamageBoard(plugin, slot);
        }
        slot.board.show(p);
    }

    private boolean leave(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("该命令只能由玩家执行。");
            return true;
        }
        DungeonInstance inst = plugin.instances().byPlayer(p.getUniqueId());
        if (inst == null) {
            p.sendMessage(ChatColor.RED + "你当前不在任何副本中。");
            return true;
        }

        inst.members.remove(p.getUniqueId());
        if (inst.bossBar != null) {
            inst.bossBar.removePlayer(p);
        }
        if (inst.board != null) {
            inst.board.hide(p);
        }
        inst.forgetPlayer(p.getUniqueId());
        Boolean flight = inst.allowFlightBefore.remove(p.getUniqueId());
        if (flight != null) {
            p.setAllowFlight(flight);
        }
        Location back = inst.returnPoints.remove(p.getUniqueId());
        if (back != null) {
            p.teleport(back);
        }
        // 还原进本前的重生点
        Location bed = inst.bedSpawns.remove(p.getUniqueId());
        if (bed != null) {
            p.setBedSpawnLocation(bed, true);
        } else {
            p.setBedSpawnLocation(null, true);
        }
        p.setFireTicks(0);
        p.sendMessage(ChatColor.YELLOW + "你已离开副本。");

        if (inst.members.isEmpty()) {
            plugin.endInstance(inst, "全员离开");
        }
        return true;
    }

    private void info(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== MyDungeon =====");
        sender.sendMessage(ChatColor.GRAY + "副本世界：" + ChatColor.WHITE + plugin.config().worldName
                + ChatColor.GRAY + "  槽位：" + ChatColor.WHITE + plugin.config().slots);
        sender.sendMessage(ChatColor.GRAY + "战损公式：" + ChatColor.WHITE + "减伤 = armor/(armor+" + plugin.config().armorK + ")");
        sender.sendMessage(ChatColor.GRAY + "怪血量倍率：" + ChatColor.WHITE + plugin.config().mobHealthScale
                + ChatColor.GRAY + "  怪伤害倍率：" + ChatColor.WHITE + plugin.config().mobDamageScale);
        sender.sendMessage(ChatColor.GRAY + "层数血量：第 N 层保底 +" + ChatColor.WHITE
                + (long) plugin.config().layerHealthStep + "×N"
                + ChatColor.GRAY + "  BOSS 再×" + ChatColor.WHITE + plugin.config().bossHealthBonus);
        double override = plugin.stages().maxHpOverride();
        if (override > 0) {
            sender.sendMessage(ChatColor.GRAY + "★ 关卡指定血量：最大 " + ChatColor.WHITE
                    + (long) override + ChatColor.GRAY + "（stages 里的 hp: 覆盖，不受上面公式影响）");
        }
        sender.sendMessage(ChatColor.GRAY + "怪模板数：" + ChatColor.WHITE + plugin.mobs().size()
                + ChatColor.GRAY + "  阶段数：" + ChatColor.WHITE + plugin.stages().stageCount());
        sender.sendMessage(ChatColor.GRAY + "进行中的副本：" + ChatColor.WHITE + plugin.instances().runningCount());
        for (DungeonInstance inst : plugin.instances().all()) {
            sender.sendMessage(ChatColor.DARK_GRAY + "  - " + inst.describe());
        }
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("dungeon.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行该命令。");
            return true;
        }
        plugin.reloadAll();
        sender.sendMessage(ChatColor.GREEN + "MyDungeon 配置已重载。");
        return true;
    }

    private boolean consumeTicket(Player p) {
        Material mat = plugin.config().ticketMaterial;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack is = contents[i];
            if (is != null && is.getType() == mat) {
                int left = is.getAmount() - 1;
                if (left <= 0) {
                    p.getInventory().setItem(i, null);
                } else {
                    is.setAmount(left);
                }
                return true;
            }
        }
        return false;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "===== MyDungeon 命令 =====");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " enter" + ChatColor.GRAY + "  — 进入副本（倒计时内可组队）");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " cancel" + ChatColor.GRAY + " — 取消进入倒计时");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " leave" + ChatColor.GRAY + "  — 离开副本");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " info" + ChatColor.GRAY + "   — 查看副本状态");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload" + ChatColor.GRAY + " — 重载配置（管理员）");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String s : SUBS) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }
}
