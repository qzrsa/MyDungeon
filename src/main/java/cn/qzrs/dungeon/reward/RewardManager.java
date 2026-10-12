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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通关奖励 + 首通记录 + 每层清空奖励。
 * <p>三层奖励：
 * <ol>
 *   <li>{@code normal} —— 每次通关给队里每人；</li>
 *   <li>{@code first-clear} —— 首次通关额外给每人；</li>
 *   <li>{@code per-stage} —— 每层清空即发，<b>队伍共享一份</b>（随机给一个在线成员，背包满则掉地上）。</li>
 * </ol>
 */
public class RewardManager {

    private final List<ItemStack> normal = new ArrayList<>();
    private final List<ItemStack> firstClear = new ArrayList<>();
    private final Set<String> cleared = new HashSet<>();
    /** 层数（1-based）→ 该层清空时发放的奖励（队伍共享一份） */
    private final Map<Integer, List<ItemStack>> perStage = new HashMap<>();

    private File dataFile;
    private String dungeonId = "dungeon1";

    public void load(YamlConfiguration yml, File dataFile) {
        normal.clear();
        firstClear.clear();
        perStage.clear();
        this.dataFile = dataFile;
        this.dungeonId = yml.getString("id", "dungeon1");

        readPool(yml.getMapList("normal"), normal);
        readPool(yml.getMapList("first-clear"), firstClear);

        // per-stage：key = 层数（1 起），value = 物品列表
        if (yml.getConfigurationSection("per-stage") != null) {
            for (String key : yml.getConfigurationSection("per-stage").getKeys(false)) {
                int stage;
                try {
                    stage = Integer.parseInt(key.trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (stage <= 0) {
                    continue;
                }
                List<ItemStack> items = new ArrayList<>();
                readPool(yml.getMapList("per-stage." + key), items);
                if (!items.isEmpty()) {
                    perStage.put(stage, items);
                }
            }
        }

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

    /**
     * 某层清空后发放该层奖励（队伍共享一份）。
     *
     * @param stageIndex 层下标（0-based，第 1 层 = 0）
     * @return 是否真的发了（该层没配奖励则返回 false）
     */
    public boolean giveStage(DungeonInstance inst, int stageIndex) {
        List<ItemStack> items = perStage.get(stageIndex + 1);
        if (items == null || items.isEmpty()) {
            return false;
        }
        // 队伍共享一份：随机挑一个在线成员，塞满就掉地上
        Player target = null;
        for (UUID u : inst.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                target = p;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        for (ItemStack is : items) {
            giveOrDrop(target, is.clone());
        }
        return true;
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

    /**
     * 保存首通记录。
     *
     * <p>这里只做「快照 + 派发」两步，真正的磁盘写在 IO 线程上：
     * <ol>
     *   <li>在主线程把 {@code cleared} 拷成独立的 List —— HashSet 不是线程安全的，
     *       不能让 IO 线程边写边读；</li>
     *   <li>把「文件 + 快照」交给 {@link MyDungeon#runAsync}，主线程立刻返回。</li>
     * </ol>
     *
     * <p>之所以能安全异步：{@code YamlConfiguration} 的 set/save 是纯 JDK +
     * SnakeYAML 操作，不碰任何 Bukkit 世界 / 实体 API。奖励发放那边
     * （{@link #give}）已经在主线程做完了，这里剩下的只有落盘。
     */
    public void save() {
        if (dataFile == null) {
            return;
        }
        final File file = dataFile;
        final List<String> snapshot = new ArrayList<>(cleared);
        MyDungeon.get().runAsync(() -> writeTo(file, snapshot));
    }

    /** 在 IO 线程上执行的实际落盘动作 */
    private void writeTo(File file, List<String> snapshot) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                MyDungeon.get().getLogger().warning("无法创建数据目录：" + parent);
            }
            YamlConfiguration data = new YamlConfiguration();
            data.set("first-clear", snapshot);
            data.save(file);
        } catch (IOException ex) {
            MyDungeon.get().getLogger().warning("保存首通记录失败：" + ex.getMessage());
        }
    }
}
