package cn.qzrs.dungeon;

import cn.qzrs.dungeon.arena.ArenaBuilder;
import cn.qzrs.dungeon.combat.CombatEngine;
import cn.qzrs.dungeon.command.DungeonCommand;
import cn.qzrs.dungeon.instance.DungeonInstance;
import cn.qzrs.dungeon.instance.InstanceManager;
import cn.qzrs.dungeon.instance.StageManager;
import cn.qzrs.dungeon.listener.DungeonListener;
import cn.qzrs.dungeon.mob.DungeonMob;
import cn.qzrs.dungeon.mob.MobTemplate;
import cn.qzrs.dungeon.reward.RewardManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MyDungeon —— 副本挑战插件（MVP）
 *
 * <p>核心思路：副本内战斗由 {@link CombatEngine} 接管，
 * 玩家的神化词缀 / 拔刀剑属性全部保留，但换算规则改走
 * {@link cn.qzrs.dungeon.combat.DamageFormula}，从而让怪追得上玩家。
 */
public class MyDungeon extends JavaPlugin {

    private static MyDungeon instance;

    private final PluginConfig config = new PluginConfig();
    private final InstanceManager instances = new InstanceManager();
    private StageManager stages = new StageManager();
    private RewardManager rewards = new RewardManager();
    private Map<String, MobTemplate> mobs = new LinkedHashMap<>();
    private World dungeonWorld;

    public static MyDungeon get() {
        return instance;
    }

    public PluginConfig config() {
        return config;
    }

    public InstanceManager instances() {
        return instances;
    }

    public StageManager stages() {
        return stages;
    }

    public RewardManager rewards() {
        return rewards;
    }

    public Map<String, MobTemplate> mobs() {
        return mobs;
    }

    public MobTemplate mob(String id) {
        return id == null ? null : mobs.get(id.toLowerCase());
    }

    public boolean isDungeonWorld(World w) {
        return w != null && w.getName().equalsIgnoreCase(config.worldName);
    }

    public World dungeonWorld() {
        return dungeonWorld;
    }

    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        reloadAll();

        dungeonWorld = ArenaBuilder.ensureWorld(config.worldName);
        if (dungeonWorld == null) {
            getLogger().severe("无法创建/加载副本世界：" + config.worldName + " —— 副本功能不可用。");
        } else if (config.arenaAutoBuild) {
            ArenaBuilder.build(dungeonWorld);
            ArenaBuilder.applyGameRules(dungeonWorld);
            getLogger().info("测试场已生成：" + config.arenaSize + "×" + config.arenaSize
                    + " @ y=" + config.arenaFloorY);
        }

        getServer().getPluginManager().registerEvents(new CombatEngine(), this);
        getServer().getPluginManager().registerEvents(new DungeonListener(this), this);

        DungeonCommand cmd = new DungeonCommand(this);
        if (getCommand("dungeon") != null) {
            getCommand("dungeon").setExecutor(cmd);
            getCommand("dungeon").setTabCompleter(cmd);
        }

        startBossBarTask();
        startAntiFlyTask();
        startProgressTask();
        startWorldWatchdog();
        startMobLeashTask();

        getLogger().info("MyDungeon 已启用 | 副本世界=" + config.worldName
                + " | 槽位=" + config.slots
                + " | 怪模板=" + mobs.size()
                + " | 阶段=" + stages.stageCount()
                + " | 公式K=" + config.armorK
                + " | 血量倍率=" + config.mobHealthScale
                + " | 伤害倍率=" + config.mobDamageScale);
    }

    @Override
    public void onDisable() {
        for (DungeonInstance inst : instances.all()) {
            endInstance(inst, "插件卸载");
        }
        rewards.save();
        instance = null;
    }

    /** 重载全部配置（config.yml / mobs.yml / stages / rewards） */
    public void reloadAll() {
        reloadConfig();
        config.load(getConfig());

        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("无法创建插件数据目录：" + dataFolder);
        }

        syncResource("mobs.yml");
        mobs = MobTemplate.loadAll(YamlConfiguration.loadConfiguration(new File(dataFolder, "mobs.yml")));

        String stagePath = "stages/" + config.stageFile;
        String rewardPath = "rewards/" + config.rewardFile;
        syncResource(stagePath);
        syncResource(rewardPath);

        stages = new StageManager();
        stages.load(YamlConfiguration.loadConfiguration(new File(dataFolder, stagePath)));

        rewards = new RewardManager();
        rewards.load(YamlConfiguration.loadConfiguration(new File(dataFolder, rewardPath)),
                new File(dataFolder, "clears.yml"));

        instances.init(config.worldName, config.slots);
    }

    /**
     * 同步内置内容文件：与 jar 内不一致时覆盖，并先备份旧文件。
     *
     * <p>适用于 mobs.yml / stages / rewards 这类"随插件版本走的内容文件"。
     * config.yml 不在此列 —— 那是用户调参用的，永不覆盖。
     * 这样升级插件后（例如新增怪种、扩充层数）无需手动删配置即可生效。
     */
    private void syncResource(String resourcePath) {
        File out = new File(getDataFolder(), resourcePath);
        if (!out.exists()) {
            saveResource(resourcePath, false);
            return;
        }
        try (InputStream in = getResource(resourcePath)) {
            if (in == null) {
                return;
            }
            String jarText = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            String diskText = Files.readString(out.toPath(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (jarText.equals(diskText)) {
                return;
            }
            File parent = out.getParentFile();
            File bak = new File(parent, out.getName() + ".bak-" + System.currentTimeMillis());
            Files.copy(out.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            saveResource(resourcePath, true);
            getLogger().info("内容文件已更新：" + resourcePath + "（旧版备份为 " + bak.getName() + "）");
        } catch (Exception ex) {
            getLogger().warning("同步内容文件失败 " + resourcePath + "：" + ex.getMessage());
        }
    }

    /**
     * 结束一场副本：清怪、把成员送回原位置、槽位归位。
     */
    public void endInstance(DungeonInstance inst, String reason) {
        if (inst == null) {
            return;
        }

        if (inst.bossBar != null) {
            inst.bossBar.removeAll();
            inst.bossBar = null;
        }

        for (DungeonMob mob : new ArrayList<>(inst.liveMobs.values())) {
            if (mob.entity != null) {
                if (!mob.entity.isDead()) {
                    mob.entity.remove();
                }
                instances.unregisterMob(mob.entity.getUniqueId());
            }
        }
        inst.liveMobs.clear();

        for (UUID u : new ArrayList<>(inst.members)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                boolean inDungeon = isDungeonWorld(p.getWorld());
                Location back = inst.returnPoints.get(u);
                if (back != null && inDungeon) {
                    p.teleport(back);
                }
                p.setFireTicks(0);
                Boolean flight = inst.allowFlightBefore.remove(u);
                if (flight != null) {
                    p.setAllowFlight(flight);
                }
            }
        }
        inst.members.clear();
        inst.returnPoints.clear();
        inst.clearRuntime();

        if (inst.bossBar == null) {
            getLogger().fine("副本槽位#" + inst.slotId + " 已结束：" + reason);
        }
    }

    /** 首领血条刷新：每 0.5 秒对齐一次真实血量 */
    private void startBossBarTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (DungeonInstance inst : instances.all()) {
                if (inst.bossBar == null) {
                    continue;
                }
                DungeonMob boss = null;
                for (DungeonMob m : inst.liveMobs.values()) {
                    if (m.template.boss) {
                        boss = m;
                        break;
                    }
                }
                if (boss == null || boss.entity == null || boss.entity.isDead()) {
                    inst.bossBar.setProgress(0.0);
                    continue;
                }
                double max = Math.max(1.0, boss.scaledHealth);
                double ratio = boss.entity.getHealth() / max;
                inst.bossBar.setProgress(Math.max(0.0, Math.min(1.0, ratio)));
            }
        }, 20L, 10L);
    }

    /**
     * 副本禁飞 + 禁浮空：
     * <ul>
     *   <li>取消原版飞行 / 鞘翅滑翔；</li>
     *   <li>拦截拔刀剑 {@code Trick Up}、空中缓降等「持续滞空」型技能浮空 ——
     *       连续滞空超过阈值且没有自然下落时，强制把玩家压回地面。</li>
     * </ul>
     */
    private void startAntiFlyTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            PluginConfig cfg = config;
            for (DungeonInstance inst : instances.all()) {
                for (UUID u : inst.members) {
                    Player p = Bukkit.getPlayer(u);
                    if (p == null || !p.isOnline()) {
                        continue;
                    }
                    if (p.isFlying()) {
                        p.setFlying(false);
                    }
                    if (p.isGliding()) {
                        p.setGliding(false);
                    }
                    if (!cfg.antiFloat) {
                        continue;
                    }
                    if (!p.isOnGround()) {
                        int air = inst.airTicks.merge(u, 1, Integer::sum);
                        Vector v = p.getVelocity();
                        // 滞空过久且未自然下落（Y 速度不够负）→ 判定技能浮空，强制向下压
                        if (air > cfg.antiFloatAirTicks && v.getY() > -0.15) {
                            p.setVelocity(new Vector(v.getX(), -0.7, v.getZ()));
                            p.setFallDistance(0.0f);
                        }
                    } else {
                        inst.airTicks.put(u, 0);
                    }
                }
            }
        }, 10L, 1L);
    }

    /**
     * 进度提示：挑战进行中，副本成员屏幕下方持续显示「第 X/总 层 · 剩余 Y 只」；
     * 层清空进入修整期时显示「修整中...」。非 RUNNING 槽位不显示。
     */
    private void startProgressTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int total = stages.stageCount();
            for (DungeonInstance inst : instances.all()) {
                if (inst.state != DungeonInstance.State.RUNNING) {
                    continue;
                }
                String bar;
                if (inst.liveMobs.isEmpty()) {
                    bar = ChatColor.YELLOW + "修整中...";
                } else {
                    bar = ChatColor.AQUA + "第 " + (inst.stageIndex + 1) + "/" + total + " 层"
                            + ChatColor.WHITE + " · "
                            + ChatColor.RED + "剩余 " + inst.liveMobs.size() + " 只";
                }
                for (UUID u : inst.members) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null && p.isOnline()) {
                        p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                                TextComponent.fromLegacyText(bar));
                    }
                }
            }
        }, 20L, 10L);
    }

    /**
     * 世界看门狗：副本世界内已无任何玩家时，把所有槽位重置回第一关。
     *
     * <p>这是「成员清空即结束」之外的兜底 —— 万一有人没用 /dungeon leave，
     * 而是通过其它插件传送走了，成员集合还残留着人，此时世界已空、进度照旧重置。
     */
    private void startWorldWatchdog() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            World w = dungeonWorld;
            if (w == null || !w.getPlayers().isEmpty()) {
                return;
            }
            boolean reset = false;
            for (DungeonInstance inst : instances.all()) {
                if (!inst.isIdle() || !inst.members.isEmpty()) {
                    endInstance(inst, "世界无人，重置进度");
                    reset = true;
                }
            }
            if (reset) {
                getLogger().info("副本世界已无人，进度已重置（下次进入将从第一关开始）。");
            }
        }, 40L, 40L);
    }

    /**
     * 卡怪拉回：周期检查副本怪是否「越界飞出」（VEX 穿墙飞出玻璃墙 / 掉进虚空）
     * 或「卡进方块」（刷进中心台座 / 墙体内，且窒息伤害被护盾 cancel 死不了）。
     * 命中即把怪拉回场地内随机安全点（不清除，保留继续打），
     * 同时顺手给燃烧的亡灵怪灭火（防常昼世界被阳光晒死）。
     */
    private void startMobLeashTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            PluginConfig cfg = config;
            int half = cfg.arenaSize / 2 + 2;   // 场地半宽 + 2 格余量
            int floorY = cfg.arenaFloorY;
            int wallTop = floorY + cfg.arenaWallHeight;
            for (DungeonInstance inst : instances.all()) {
                if (inst.state != DungeonInstance.State.RUNNING) {
                    continue;
                }
                for (DungeonMob mob : new ArrayList<>(inst.liveMobs.values())) {
                    if (mob.entity == null || mob.entity.isDead()) {
                        continue;
                    }
                    // 清火：副本世界常昼，亡灵怪会被阳光点燃；onMobShield 拦不住
                    // Arclight 的燃烧伤害，这里直接灭火，防止怪被晒死（scorched to death）。
                    if (mob.entity.getFireTicks() > 0) {
                        mob.entity.setFireTicks(0);
                    }
                    Location loc = mob.entity.getLocation();
                    boolean oob = Math.abs(loc.getX()) > half
                            || Math.abs(loc.getZ()) > half
                            || loc.getY() < floorY - 5
                            || loc.getY() > wallTop + 5;
                    boolean stuck = loc.getBlock().getType().isSolid()
                            && loc.getBlockY() > floorY;
                    if (oob || stuck) {
                        String reason = oob ? "越界飞出" : "卡进方块";
                        getLogger().info("拉回卡住的副本怪：" + mob.template.name + "（" + reason
                                + " @ " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                                + " 方块=" + loc.getBlock().getType().name() + "）");
                        stages().relocateToArena(inst, mob);
                    }
                }
            }
        }, 40L, 40L);
    }
}
