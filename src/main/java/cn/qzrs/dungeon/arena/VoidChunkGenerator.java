package cn.qzrs.dungeon.arena;

import org.bukkit.HeightMap;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * 真·虚空世界生成器。
 *
 * <p>与「超平坦 + 全空气层」的区别：
 * <ul>
 *   <li>超平坦仍会走 Minecraft 自己的 flat 生成管线，并写入基岩层/高度图；</li>
 *   <li>本生成器直接让区块生成阶段全部返回空 —— 不产生任何方块、结构、生物、洞穴，
 *       连噪声计算都跳过，因此区块生成开销接近 0，也不会"刷新周围的区块"。</li>
 * </ul>
 *
 * <p>地形完全由 {@link ArenaBuilder} 手工铺设（地板 + 围墙）。
 */
public class VoidChunkGenerator extends ChunkGenerator {

    // ---- 区块生成阶段：全部留空 ----

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // 虚空：不生成任何噪声地形
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // 虚空：不生成表层
    }

    @Override
    public void generateBedrock(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // 虚空：不生成基岩层
    }

    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // 虚空：不生成洞穴
    }

    // ---- 各生成开关：全部关闭 ----

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        return 0;
    }

    /** 单一生物群系，避免群系查找带来的额外开销 */
    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo info, int x, int y, int z) {
                return Biome.PLAINS;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                return List.of(Biome.PLAINS);
            }
        };
    }
}
