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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    /**
     * 纯文件 IO 的专用线程 —— 本插件唯一脱离主线程的工作。
     * 见 {@link #runAsync(Runnable)}：只允许放进「读写文件 / 纯计算」，
     * 任何 Bukkit 世界 / 实体 API 都必须留在主线程。
     */
    private ExecutorService ioExecutor;

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
        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MyDungeon-IO");
            t.setDaemon(true);
            return t;
        });

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
        checkHealthLimit();

        getLogger().info("MyDungeon 已启用 | 副本世界=" + config.worldName
                + " | 槽位=" + config.slots
                + " | 怪模板=" + mobs.size()
                + " | 阶段=" + stages.stageCount()
                + " | 公式K=" + config.armorK
                + " | 血量倍率=" + config.mobHealthScale
                + " | 伤害倍率=" + config.mobDamageScale
                + " | 层数血量每层+" + (long) config.layerHealthStep
                + "（BOSS×" + config.bossHealthBonus + "）");
    }

    @Override
    public void onDisable() {
        for (DungeonInstance inst : instances.all()) {
            endInstance(inst, "插件卸载");
        }
        rewards.save();
        shutdownIoExecutor();
        instance = null;
    }

    /**
     * 关闭 IO 线程并等它写完 —— 避免关服瞬间丢掉奖励数据。
     * 最多等 5 秒，超时放弃（不至于把关服流程卡死）。
     */
    private void shutdownIoExecutor() {
        if (ioExecutor == null) {
            return;
        }
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                getLogger().warning("IO 线程未能在 5 秒内写完，可能有少量数据未落盘。");
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        ioExecutor = null;
    }

    /**
     * 把「纯文件 IO / 纯计算」丢到专用线程执行 —— 主线程不必等磁盘。
     *
     * <p><b>可以放进来</b>：读/写文件、拼字符串、纯数学运算。
     * <br><b>绝对不准放进来</b>：任何 World / Entity / Player / Block 操作 ——
     * Bukkit 的这些 API 只允许主线程调用，异步调用会抛
     * {@code IllegalStateException}（例如 "Asynchronous entity add!"）
     * 或造成静默的数据竞争。调用方必须先在主线程把要用的数据
     * 「快照」成独立对象（如 {@code new ArrayList<>(集合)}），再交给这里。
     *
     * <p>执行器是<b>单线程</b>的 —— 同一个文件不会被两处同时写。
     * 若执行器已关闭（正在关服），退化为同步执行，保证任务不被丢弃。
     */
    public void runAsync(Runnable task) {
        ExecutorService pool = ioExecutor;
        if (pool == null || pool.isShutdown()) {
            task.run();
            return;
        }
        pool.execute(task);
    }

    /** 没有任何「非空闲」副本时，常驻任务可整段跳过 —— 省掉每 tick 的遍历 */
    private boolean noActiveInstance() {
        for (DungeonInstance inst : instances.all()) {
            if (inst.state != DungeonInstance.State.IDLE) {
                return false;
            }
        }
        return true;
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
            if (noActiveInstance()) {
                return;
            }
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
            if (noActiveInstance()) {
                return;
            }
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
        }, 10L, 2L);
    }

    /**
     * 进度提示：挑战进行中，副本成员屏幕下方持续显示「第 X/总 层 · 剩余 Y 只」；
     * 层清空进入修整期时显示「修整中...」。非 RUNNING 槽位不显示。
     */
    private void startProgressTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (noActiveInstance()) {
                return;
            }
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
     * 副本怪维护：周期检查每只存活怪，处理异常情况，保证阶段一定能推进。
     * <ol>
     *   <li><b>幽灵怪</b>：实体已被移除 / 无效 / 已死，但仍残留在存活列表 ——
     *       直接清理并推进阶段（否则会「剩 1 只永远找不到、卡住不推进」）。</li>
     *   <li><b>越界 / 卡方块</b>：VEX 穿墙飞出、刷进台座或墙体 —— 拉回场地安全点。</li>
     *   <li><b>燃烧</b>：常昼世界亡灵怪被阳光点燃 —— 灭火（护盾拦不住 Arclight 燃烧）。</li>
     * </ol>
     * 另外：剩余怪很少时给它们加发光，方便玩家找到最后几只。
     */
    private void startMobLeashTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (noActiveInstance()) {
                return;
            }
            PluginConfig cfg = config;
            int half = cfg.arenaSize / 2 + 2;   // 场地半宽 + 2 格余量
            int floorY = cfg.arenaFloorY;
            int wallTop = floorY + cfg.arenaWallHeight;
            for (DungeonInstance inst : instances.all()) {
                if (inst.state != DungeonInstance.State.RUNNING) {
                    continue;
                }
                // 剩余怪少时发光，方便玩家找到最后几只
                boolean glow = inst.liveMobs.size() <= cfg.mobGlowWhenLeft;

                for (Map.Entry<UUID, DungeonMob> entry
                        : new ArrayList<>(inst.liveMobs.entrySet())) {
                    UUID id = entry.getKey();
                    DungeonMob mob = entry.getValue();

                    // ① 幽灵怪：实体已不存在 / 无效 / 已死 → 清理并推进阶段
                    if (mob.entity == null || !mob.entity.isValid() || mob.entity.isDead()) {
                        getLogger().info("清理幽灵副本怪：" + plain(mob.template.name)
                                + "（实体已不存在，阶段照常推进）");
                        instances.unregisterMob(id);
                        stages().onMobDeath(inst, id);
                        continue;
                    }

                    // ② 清火：常昼世界亡灵怪会被阳光点燃；onMobShield 拦不住
                    // Arclight 的燃烧伤害，这里直接灭火，防止怪被晒死（scorched to death）。
                    if (mob.entity.getFireTicks() > 0) {
                        mob.entity.setFireTicks(0);
                    }

                    // ③ 发光：剩余怪少时让玩家能透视找到
                    if (glow) {
                        mob.entity.addPotionEffect(new PotionEffect(
                                PotionEffectType.GLOWING, 60, 0, false, false, false));
                    }

                    Player anchor = nearestPlayerInArena(inst, mob.entity);

                    // ④ VEX（恼鬼）专项：原版恼鬼在「非冲锋」状态会执行随机游荡，
                    //    而它的导航是「穿墙直线飞行」，不避让方块 —— 这是「卡怪」的头号来源
                    //    （实测越界记录 100% 来自精英·噬魂鬼）。这里强制它保持冲锋，
                    //    并锁定场地内最近的玩家：冲锋状态下导航是直线冲向目标，
                    //    目标在场地内 → 它就不会往场外飞。
                    if (mob.entity instanceof Vex vex && anchor != null) {
                        if (!vex.isCharging()) {
                            vex.setCharging(true);
                        }
                        LivingEntity cur = vex.getTarget();
                        if (!(cur instanceof Player tp) || !tp.isOnline()
                                || !inst.members.contains(tp.getUniqueId())
                                || !isDungeonWorld(tp.getWorld())) {
                            vex.setTarget(anchor);
                        }
                    }

                    // ⑤ 越界 / 卡方块 → 拉回场地
                    Location loc = mob.entity.getLocation();
                    boolean oob = Math.abs(loc.getX()) > half
                            || Math.abs(loc.getZ()) > half
                            || loc.getY() < floorY - 5
                            || loc.getY() > wallTop + 5;
                    boolean stuck = loc.getBlock().getType().isSolid()
                            && loc.getBlockY() > floorY;
                    if (oob || stuck) {
                        String reason = oob ? "越界飞出" : "卡进方块";
                        getLogger().info("拉回卡住的副本怪：" + plain(mob.template.name) + "（" + reason
                                + " @ " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                                + " 方块=" + loc.getBlock().getType().name() + "）");
                        if (mob.entity instanceof Vex && anchor != null) {
                            // 飞行怪不能丢回地面：那是「放回地面 → 立刻起飞 → 再次越界」的死循环。
                            // 直接安置到目标玩家身边（坐标钳制在场内），它马上就能继续攻击玩家。
                            relocateNearPlayer(mob.entity, anchor,
                                    cfg.arenaSize / 2, floorY, wallTop);
                        } else {
                            stages().relocateToArena(inst, mob);
                        }
                    }
                }
            }
        }, 10L, 10L);
    }

    /** 找副本实例里、仍身处副本世界的最近玩家（用于给恼鬼锁定目标 / 安置回场） */
    private Player nearestPlayerInArena(DungeonInstance inst, Entity from) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (UUID u : inst.members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline() || !isDungeonWorld(p.getWorld())) {
                continue;
            }
            double d = p.getLocation().distanceSquared(from.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * 把越界的飞行怪安置到目标玩家身边（坐标钳制在场地内），并复位速度 + 重新冲锋。
     * 对穿墙飞行的恼鬼，只有「贴到玩家身上」才能让它稳定留在场地内。
     */
    private void relocateNearPlayer(Entity mob, Player anchor,
                                    int arenaHalf, int floorY, int wallTop) {
        Location a = anchor.getLocation();
        double lim = Math.max(2.0, arenaHalf - 2.0);
        double x = Math.max(-lim, Math.min(lim, a.getX() + 2.0));
        double z = Math.max(-lim, Math.min(lim, a.getZ() + 2.0));
        double y = Math.max(floorY + 1, Math.min(wallTop - 2, a.getY() + 1.5));
        mob.teleport(new Location(a.getWorld(), x, y, z));
        mob.setVelocity(new Vector(0, 0, 0));
        if (mob instanceof Vex vex) {
            vex.setCharging(true);
            vex.setTarget(anchor);
        }
    }

    /** 去掉颜色代码，用于日志输出（怪模板名形如 &amp;c精英·噬魂鬼） */
    private static String plain(String s) {
        return s == null ? ""
                : ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s));
    }

    /**
     * 启动自检：spigot.yml 的 settings.attribute.maxHealth.max 会限制所有实体的血量上限
     * （Spigot 默认 2048，远低于本插件怪模板的千级 / 万级血量）。一旦它小于怪模板最大血量，
     * 怪会被削血，这里主动告警，避免「怪血量被限制」这种隐蔽问题。
     */
    private void checkHealthLimit() {
        double maxTemplate = 0.0;
        // 血量最高的怪出现在最后一层，且 BOSS 还要再乘 boss-health-bonus
        int maxLayer = Math.max(1, stages().stageCount());
        for (MobTemplate t : mobs.values()) {
            double layerBonus = maxLayer * config.layerHealthStep
                    * (t.boss ? config.bossHealthBonus : 1.0);
            maxTemplate = Math.max(maxTemplate, t.health * config.mobHealthScale + layerBonus);
        }
        if (maxTemplate <= 0) {
            return;
        }
        File serverRoot = getDataFolder().getParentFile().getParentFile();
        File spigotFile = new File(serverRoot, "spigot.yml");
        if (!spigotFile.isFile()) {
            return;
        }
        double limit = YamlConfiguration.loadConfiguration(spigotFile)
                .getDouble("settings.attribute.maxHealth.max", -1.0);
        if (limit > 0 && limit < maxTemplate) {
            getLogger().warning("★ 怪的血量会被 spigot.yml 限制！怪模板最大血量 = " + maxTemplate
                    + "，但 settings.attribute.maxHealth.max = " + limit
                    + "。请把它调大（推荐 1.0E7）后重启服务端。");
        } else {
            getLogger().info("血量上限自检通过：spigot.yml maxHealth.max = " + limit
                    + "（怪模板最大血量 " + maxTemplate + "）。");
        }
    }
}
