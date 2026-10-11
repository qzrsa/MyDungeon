package cn.qzrs.dungeon.reward;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.instance.DungeonInstance;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通关奖励 + 首通记录。
 * MVP 为固定掉落池（items），后续可替换为权重随机池。
 */
public class RewardManager {

    private final List<ItemStack> normal = new ArrayList<>();
    private final List<ItemStack> firstClear = new ArrayList<>();
    private final Set<String> cleared = new HashSet<>();

    private File dataFile;
    private String dungeonId = "dungeon1";

    public void load(YamlConfiguration yml, File dataFile) {
        normal.clear();
        firstClear.clear();
        this.dataFile = dataFile;
        this.dungeonId = yml.getString("id", "dungeon1");

        readPool(yml.getMapList("normal"), normal);
        readPool(yml.getMapList("first-clear"), firstClear);

        cleared.clear();
        if (dataFile != null && dataFile.exists()) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            cleared.addAll(data.getStringList("first-clear"));
        }
    }

    private void readPool(List<Map<?, ?>> raw, List<ItemStack> out) {
        for (Map<?, ?> m : raw) {
            String matName = m.get("material") == null ? null : String.valueOf(m.get("material"));
            if (matName == null) {
                continue;
            }
            Material mat = Material.matchMaterial(matName);
            if (mat == null) {
                continue;
            }
            int amount = 1;
            Object a = m.get("amount");
            if (a instanceof Number n) {
                amount = n.intValue();
            }
            out.add(new ItemStack(mat, Math.max(1, amount)));
        }
    }

    /** 给副本内所有在线成员发奖；首通玩家额外拿一份。 */
    public void give(DungeonInstance inst) {
        for (UUID u : inst.members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) {
                continue;
            }

            for (ItemStack is : normal) {
                giveOrDrop(p, is.clone());
            }

            String key = dungeonId + ":" + u;
            if (!cleared.contains(key)) {
                cleared.add(key);
                for (ItemStack is : firstClear) {
                    giveOrDrop(p, is.clone());
                }
                p.sendMessage(ChatColor.LIGHT_PURPLE + "★ 首次通关，获得额外奖励！");
            }
        }
        save();
    }

    private void giveOrDrop(Player p, ItemStack is) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(is);
        for (ItemStack rest : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    public void save() {
        if (dataFile == null) {
            return;
        }
        try {
            File parent = dataFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                MyDungeon.get().getLogger().warning("无法创建数据目录：" + parent);
            }
            YamlConfiguration data = new YamlConfiguration();
            data.set("first-clear", new ArrayList<>(cleared));
            data.save(dataFile);
        } catch (IOException ex) {
            MyDungeon.get().getLogger().warning("保存首通记录失败：" + ex.getMessage());
        }
    }
}
