package cn.qzrs.dungeon.board;

import cn.qzrs.dungeon.MyDungeon;
import cn.qzrs.dungeon.instance.DungeonInstance;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 副本内的伤害统计计分板（右侧 sidebar）。
 *
 * <p>只对本副本的成员生效：每人一块**独立的** {@link Scoreboard}，进本时挂上、
 * 离开或副本结束时还原成进本前那块，所以对副本外面的游戏体验零影响。
 *
 * <p>每块板子长这样：
 * <pre>
 *   遗忘地窟 · 第 5/20 层        ← objective 的 displayName（不带分数）
 *   §a① §f玩家A          1234567  ← entry 不可见，文本走 team 前缀，右侧数字 = 总伤害
 *   §e② §f玩家B           987654
 *   §b我的秒伤 §f12.3k/s          0
 * </pre>
 *
 * <p>★ 为什么把行文本塞进 team 的 prefix/suffix，而不是直接当 entry：
 * <ol>
 *   <li>把玩家名当 entry 会让 <b>头顶名牌</b>也带上「① 」这种前缀（名牌同样走计分板）；</li>
 *   <li>分数排序由 entry 的 score 决定，entry 用不可见颜色码可以自由复用到固定槽位。</li>
 * </ol>
 */
public class DamageBoard {

    /** objective 名（同一块计分板内唯一即可） */
    private static final String OBJECTIVE = "mydungeon_dmg";

    /**
     * 不可见 entry：纯颜色码，客户端渲染时不显示任何字符，但字符串本身各不相同，
     * 于是可以拿来做「固定行槽位」——第 i 行永远用第 i 个 entry，刷新时不需要重建。
     */
    private static final String[] SLOT = new String[16];

    /** 名次前缀（第 1 行最亮，后面递减） */
    private static final ChatColor[] RANK_COLOR = {
            ChatColor.GREEN, ChatColor.YELLOW, ChatColor.GOLD, ChatColor.RED,
            ChatColor.LIGHT_PURPLE, ChatColor.AQUA, ChatColor.BLUE, ChatColor.GRAY
    };

    static {
        for (int i = 0; i < SLOT.length; i++) {
            SLOT[i] = "\u00a7" + Integer.toHexString(i) + "\u00a7r";
        }
    }

    /**
     * 「能否隐藏右侧分数」的全局探测结果：null = 还没试过。
     * 1.20.3+ 的原版支持 number format（blank = 不显示数字），但 <b>Spigot API 没有暴露</b>，
     * 只能反射。探测失败就诚实降级为「显示数字」，不影响功能。
     */
    private static Boolean hideNumbers = null;

    private final MyDungeon plugin;
    private final DungeonInstance inst;

    /** 成员 → 我们发出去的那块计分板 */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    /** 成员 → 进本前的计分板，离开时原样还回去 */
    private final Map<UUID, Scoreboard> previous = new HashMap<>();

    public DamageBoard(MyDungeon plugin, DungeonInstance inst) {
        this.plugin = plugin;
        this.inst = inst;
    }

    // ------------------------------------------------------------------
    // 显示 / 隐藏
    // ------------------------------------------------------------------

    /** 给单个玩家挂上副本计分板（已在板上则只刷新一次） */
    public void show(Player p) {
        if (!plugin.config().boardEnabled || p == null || !p.isOnline()) {
            return;
        }
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) {
            return;
        }
        UUID id = p.getUniqueId();
        if (boards.containsKey(id)) {
            updateOne(p);
            return;
        }

        Scoreboard sb = mgr.getNewScoreboard();
        Objective obj = sb.registerNewObjective(OBJECTIVE, Criteria.DUMMY, title());
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        trySetHideNumbers(obj);

        previous.put(id, p.getScoreboard());
        boards.put(id, sb);
        p.setScoreboard(sb);
        updateOne(p);
    }

    /** 还原玩家的原计分板 */
    public void hide(Player p) {
        if (p == null) {
            return;
        }
        UUID id = p.getUniqueId();
        if (boards.remove(id) == null) {
            return;
        }
        Scoreboard prev = previous.remove(id);
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (prev != null) {
            p.setScoreboard(prev);
        } else if (mgr != null) {
            p.setScoreboard(mgr.getMainScoreboard());
        }
    }

    /** 刷新本副本所有成员的板子（主线程定时调用） */
    public void update() {
        for (UUID u : new ArrayList<>(inst.members)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                updateOne(p);
            }
        }
    }

    /** 本场结束：所有人还原，并断开引用 */
    public void clear() {
        for (UUID u : new ArrayList<>(boards.keySet())) {
            hide(Bukkit.getPlayer(u));
        }
        boards.clear();
        previous.clear();
    }

    // ------------------------------------------------------------------
    // 单块板子的刷新
    // ------------------------------------------------------------------

    private void updateOne(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) {
            return;
        }
        Objective obj = sb.getObjective(OBJECTIVE);
        if (obj == null) {
            return;
        }
        obj.setDisplayName(title());

        int top = plugin.config().boardTop;
        List<Map.Entry<UUID, Double>> rank = plugin.instances().ranking(inst);

        int used = 0;
        for (Map.Entry<UUID, Double> e : rank) {
            if (used >= top) {
                break;
            }
            UUID id = e.getKey();
            if (!inst.members.contains(id)) {
                continue;   // 已经退场的人不再占榜位
            }
            Player mate = Bukkit.getPlayer(id);
            if (mate == null || !mate.isOnline()) {
                continue;
            }
            double dmg = e.getValue();
            double dps = plugin.instances().dps(inst, id, plugin.config().dpsWindowSeconds);

            ChatColor rc = RANK_COLOR[Math.min(used, RANK_COLOR.length - 1)];
            String prefix = rc + "" + ChatColor.BOLD + (used + 1) + " " + ChatColor.RESET + ChatColor.WHITE + mate.getName();
            // 隐藏分数时把伤害写进行文本，否则右侧数字本身就是伤害值
            String suffix = Boolean.TRUE.equals(hideNumbers)
                    ? ChatColor.DARK_GRAY + " | " + ChatColor.GOLD + fmt(dmg) + ChatColor.AQUA + " " + fmt(dps) + "/s"
                    : ChatColor.AQUA + " " + fmt(dps) + "/s";

            setRow(sb, obj, used, prefix, suffix, Math.max(1, (int) dmg));
            used++;
        }
        // 榜位减少时清掉多余的行
        for (int i = used; i < top; i++) {
            clearRow(sb, obj, i);
        }

        // 最后一行：自己的秒伤（score = 0，永远排在所有伤害值下面）
        double myDps = plugin.instances().dps(inst, p.getUniqueId(), plugin.config().dpsWindowSeconds);
        setRow(sb, obj, top,
                ChatColor.AQUA + "" + ChatColor.BOLD + "我的秒伤",
                ChatColor.WHITE + " " + fmt(myDps) + "/s",
                0);
    }

    private void setRow(Scoreboard sb, Objective obj, int slot, String prefix, String suffix, int score) {
        String entry = SLOT[slot];
        Team team = sb.getTeam("md" + slot);
        if (team == null) {
            team = sb.registerNewTeam("md" + slot);
        }
        team.setPrefix(prefix);
        team.setSuffix(suffix);
        team.addEntry(entry);
        obj.getScore(entry).setScore(score);
    }

    private void clearRow(Scoreboard sb, Objective obj, int slot) {
        String entry = SLOT[slot];
        sb.resetScores(entry);
        Team team = sb.getTeam("md" + slot);
        if (team != null) {
            team.removeEntry(entry);
            team.setPrefix("");
            team.setSuffix("");
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String title() {
        int total = Math.max(1, plugin.stages().stageCount());
        int layer = Math.max(1, Math.min(total, inst.stageIndex + 1));
        String t = plugin.config().boardTitle
                .replace("{layer}", String.valueOf(layer))
                .replace("{total}", String.valueOf(total));
        return ChatColor.translateAlternateColorCodes('&', t);
    }

    /** 1234567 → 123.5万；12000 → 12.0k；把右侧数字压短，方便一眼比较 */
    static String fmt(double v) {
        if (v >= 100_000_000.0) {
            return String.format("%.2f亿", v / 100_000_000.0);
        }
        if (v >= 10_000.0) {
            return String.format("%.2f万", v / 10_000.0);
        }
        if (v >= 1_000.0) {
            return String.format("%.1fk", v / 1_000.0);
        }
        return String.valueOf(Math.round(v));
    }

    /**
     * 反射隐藏右侧分数（失败就静默降级，右侧照常显示数值）。
     *
     * <p>路径：CraftObjective#getHandle() → NMS Objective#setNumberFormat(BlankFormat.INSTANCE)。
     * 只在 1.20.3+ 的原版里有 number format，Spigot API 未暴露，所以这里必须反射；
     * 类名走 Mojang 官方映射（Forge/Arclight 1.20.2+ 用的就是这套）。
     */
    private static void trySetHideNumbers(Objective obj) {
        if (Boolean.FALSE.equals(hideNumbers)) {
            return;
        }
        try {
            Object handle = obj.getClass().getMethod("getHandle").invoke(obj);
            Class<?> nfClass = Class.forName("net.minecraft.network.chat.numbers.NumberFormat");
            Class<?> blankClass = Class.forName("net.minecraft.network.chat.numbers.BlankFormat");
            Object blank = blankClass.getField("INSTANCE").get(null);
            handle.getClass().getMethod("setNumberFormat", nfClass).invoke(handle, blank);
            if (hideNumbers == null) {
                MyDungeon.get().getLogger().info("计分板：已隐藏右侧分数（number format 可用）");
            }
            hideNumbers = Boolean.TRUE;
        } catch (Throwable t) {
            if (hideNumbers == null) {
                MyDungeon.get().getLogger().info(
                        "计分板：当前服务端不支持隐藏右侧分数，将直接显示数值（不影响功能）");
            }
            hideNumbers = Boolean.FALSE;
        }
    }
}
