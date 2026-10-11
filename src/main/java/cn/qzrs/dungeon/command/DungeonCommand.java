package cn.qzrs.dungeon.command;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.arena.ArenaBuilder;
import cn.qzrs.dungeon.instance.DungeonInstance;
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

import java.util.ArrayList;
import java.util.List;

/**
 * /dungeon enter|leave|info|reload
 */
public class DungeonCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("enter", "leave", "info", "reload");

    private final MyDungeon plugin;

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
        World w = plugin.dungeonWorld();
        if (w == null) {
            p.sendMessage(ChatColor.RED + "副本世界未就绪，请查看服务端控制台报错。");
            return true;
        }
        DungeonInstance slot = plugin.instances().findFree();
        if (slot == null) {
            p.sendMessage(ChatColor.RED + "副本槽位已满（" + plugin.config().slots + " 个），请稍后再试。");
            return true;
        }
        if (plugin.config().requireTicket && !consumeTicket(p)) {
            p.sendMessage(ChatColor.RED + "进入副本需要门票：" + plugin.config().ticketMaterial.name());
            return true;
        }

        slot.returnPoints.put(p.getUniqueId(), p.getLocation().clone());
        slot.members.add(p.getUniqueId());

        p.teleport(ArenaBuilder.spawnLocation(w));
        AttributeInstance hp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (hp != null) {
            p.setHealth(hp.getValue());
        }
        p.setFireTicks(0);

        plugin.stages().start(slot);
        return true;
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
        Location back = inst.returnPoints.remove(p.getUniqueId());
        if (back != null) {
            p.teleport(back);
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
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " enter" + ChatColor.GRAY + "  — 进入副本");
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
