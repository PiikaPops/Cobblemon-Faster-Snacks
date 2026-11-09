package fr.coquelicot.fastersnacks.snack;

import com.cobblemon.mod.common.api.spawning.SpawningZone;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;

/**
 * Stand-in for the list returned by {@code SpawningZone.nearbyBlocks}: every spawnable position gets a copy
 * of the ~405 blocks around it, but only conditions that need nearby blocks ever read it. The real list is
 * built by Cobblemon's own method the first time anything reads this one, so content and order are the same.
 */
public final class LazyNearbyBlocks extends AbstractList<BlockState> implements RandomAccess {
    /** Set while the real list is built, so the mixin lets Cobblemon's method run normally. */
    public static boolean building;

    private final SpawningZone zone;
    private final int cx, cy, cz, h, v;
    private List<BlockState> list;

    public LazyNearbyBlocks(SpawningZone zone, int cx, int cy, int cz, int h, int v) {
        this.zone = zone; this.cx = cx; this.cy = cy; this.cz = cz; this.h = h; this.v = v;
    }

    public boolean materialized() { return list != null; }
    public SpawningZone zone() { return zone; }
    public int cx() { return cx; }
    public int cy() { return cy; }
    public int cz() { return cz; }
    public int h() { return h; }
    public int v() { return v; }

    private List<BlockState> list() {
        if (list == null) {
            building = true;
            try {
                list = zone.nearbyBlocks(cx, cy, cz, h, v);
            } finally {
                building = false;
            }
        }
        return list;
    }

    @Override public BlockState get(int index) { return list().get(index); }
    @Override public int size() { return list().size(); }
    @Override public BlockState set(int index, BlockState element) { return list().set(index, element); }
    @Override public void add(int index, BlockState element) { list().add(index, element); modCount++; }
    @Override public BlockState remove(int index) { modCount++; return list().remove(index); }

    /**
     * Distinct blocks of the same box, without building the list.
     * Same bounds and same out-of-zone default as SpawningZone.nearbyBlocks.
     */
    public Block[] distinctBlocks() {
        if (list != null) return SpawnMatchMemo.distinctBlocks(list);
        BlockState stone = Blocks.STONE.defaultBlockState();
        int minX = Math.max(cx - h, zone.getBaseX()), maxX = Math.min(cx + h, zone.getBaseX() + zone.getLength());
        int minY = Math.max(cy - v, zone.getBaseY()), maxY = Math.min(cy + v, zone.getBaseY() + zone.getHeight());
        int minZ = Math.max(cz - h, zone.getBaseZ()), maxZ = Math.min(cz + h, zone.getBaseZ() + zone.getWidth());
        Block[] distinct = new Block[8];
        int n = 0;
        BlockState last = null;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                outer:
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState state = zone.getBlockState(x, y, z, stone);
                    if (state == last) continue;
                    last = state;
                    Block b = state.getBlock();
                    for (int i = 0; i < n; i++) if (distinct[i] == b) continue outer;
                    if (n == distinct.length) distinct = Arrays.copyOf(distinct, n * 2);
                    distinct[n++] = b;
                }
            }
        }
        return Arrays.copyOf(distinct, n);
    }
}
