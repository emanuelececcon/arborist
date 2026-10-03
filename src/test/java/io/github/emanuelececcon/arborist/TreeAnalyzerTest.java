package io.github.emanuelececcon.arborist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class TreeAnalyzerTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final int BIG = 400;

    private static Map<Long, Integer> owned(FakeWorld world, int x, int y, int z) {
        TreeAnalyzer analyzer = new TreeAnalyzer(world, FakeWorld.TYPES);
        Set<Long> trunk = analyzer.trunk(x, y, z, BIG, Integer.MIN_VALUE);
        assertNotNull(trunk);
        return analyzer.ownedLeaves(FakeWorld.TYPES.species(world.at(x, y, z)), trunk, Set.of());
    }

    /** Removes the tree's logs one by one from the bottom, settling after each, and returns every leaf that fell. */
    private static Set<Long> cut(FakeWorld world, TreeTracker tracker, int x, int z, int height) {
        Set<Long> fell = new HashSet<>();
        for (int y = 1; y <= height; y++) {
            TreeRecord record = tracker.track(WORLD, world, x, y, z, BIG, 0);
            world.remove(Pos.pack(x, y, z));
            if (record != null) {
                for (long leaf : tracker.settle(record, world, 0).keySet()) {
                    world.remove(leaf);
                    fell.add(leaf);
                }
            }
        }
        return fell;
    }

    @Test
    void packsCoordinates() {
        int[][] samples = {{0, 0, 0}, {-1, -64, -1}, {29_999_999, 319, -29_999_999}, {-33_554_432, -2048, 33_554_431}, {5, 2047, -7}};
        for (int[] s : samples) {
            long pos = Pos.pack(s[0], s[1], s[2]);
            assertEquals(s[0], Pos.x(pos));
            assertEquals(s[1], Pos.y(pos));
            assertEquals(s[2], Pos.z(pos));
        }
        assertEquals(Pos.pack(4, -3, -9), Pos.offset(Pos.pack(5, -4, -8), -1, 1, -1));
    }

    @Test
    void leafOwnersFollowTheGeneratedTrees() {
        TreeTypes types = FakeWorld.TYPES;
        assertEquals("oak", types.species(Material.OAK_LOG));
        assertEquals("oak", types.species(Material.STRIPPED_OAK_LOG));
        assertNull(types.species(Material.OAK_WOOD));
        assertNull(types.species(Material.CRIMSON_STEM));
        assertTrue(types.keepsLeaves(Material.OAK_WOOD));
        assertTrue(types.canOwn("oak", Material.OAK_LEAVES));
        assertTrue(types.canOwn("jungle", Material.OAK_LEAVES)); // jungle bush
        assertTrue(types.canOwn("oak", Material.AZALEA_LEAVES));
        assertTrue(types.canOwn("oak", Material.FLOWERING_AZALEA_LEAVES));
        assertTrue(types.canOwn("poplar", Material.RED_POPLAR_LEAVES));
        assertFalse(types.canOwn("oak", Material.BIRCH_LEAVES));
        assertFalse(types.canOwn("birch", Material.OAK_LEAVES));
        assertFalse(types.canOwn("oak", Material.RED_POPLAR_LEAVES));
    }

    @Test
    void touchingCrownsAreSplitBetweenTheTrees() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        world.tree(4, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        Map<Long, Integer> a = owned(world, 0, 1, 0);
        Map<Long, Integer> b = owned(world, 4, 1, 0);
        assertFalse(a.isEmpty());
        assertFalse(b.isEmpty());
        for (long leaf : a.keySet()) {
            assertFalse(b.containsKey(leaf), "owned by both: " + Pos.format(leaf));
            assertTrue(Pos.x(leaf) < 2, "A owns a leaf on B's side: " + Pos.format(leaf));
        }
        for (long leaf : b.keySet()) {
            assertTrue(Pos.x(leaf) > 2, "B owns a leaf on A's side: " + Pos.format(leaf));
        }
        for (long leaf : world.leavesOf(Material.OAK_LEAVES)) {
            if (Pos.x(leaf) < 2) {
                assertTrue(a.containsKey(leaf), "A should own " + Pos.format(leaf));
            }
        }
    }

    @Test
    void cuttingATreeDropsItsCrownEvenWhereItTouchesAnother() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        world.tree(4, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        Set<Long> bLeaves = new HashSet<>(owned(world, 4, 1, 0).keySet());
        TreeTracker tracker = new TreeTracker(FakeWorld.TYPES);
        TreeAnalyzer analyzer = new TreeAnalyzer(world, FakeWorld.TYPES);

        // Before anything falls, vanilla's rule keeps the leaves next to the other crown alive: that's the old bug.
        Set<Long> nearB = new HashSet<>();
        for (long leaf : world.leavesOf(Material.OAK_LEAVES)) {
            if (Pos.x(leaf) == 1) {
                nearB.add(leaf);
            }
        }
        assertFalse(nearB.isEmpty());

        Set<Long> fell = cut(world, tracker, 0, 0, 5);
        assertTrue(fell.containsAll(nearB), "leaves next to the other crown must fall too");
        for (long leaf : world.leavesOf(Material.OAK_LEAVES)) {
            assertTrue(Pos.x(leaf) >= 2, "leaf of the cut tree left: " + Pos.format(leaf));
        }
        assertTrue(world.leavesOf(Material.OAK_LEAVES).containsAll(bLeaves), "the other tree lost leaves");
        assertEquals(0, tracker.size());
        // The shared column in the middle is still held by the standing tree, also by vanilla's rule.
        assertTrue(analyzer.orphansNear(fell, Set.of()).isEmpty());
    }

    @Test
    void nothingFallsWhileTheTreeHasLogsNearItsLeaves() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        int before = world.leavesOf(Material.OAK_LEAVES).size();
        TreeTracker tracker = new TreeTracker(FakeWorld.TYPES);
        TreeRecord record = tracker.track(WORLD, world, 0, 1, 0, BIG, 0);
        assertNotNull(record);
        assertEquals(before, record.leaves.size());
        world.remove(Pos.pack(0, 1, 0));
        assertTrue(tracker.settle(record, world, 0).isEmpty());
        assertEquals(1, tracker.size());
        // Another log of the same tree finds the same record.
        assertTrue(tracker.track(WORLD, world, 0, 2, 0, BIG, 0) == record);
    }

    @Test
    void leavesFarFromTheRemainingLogsFallFirst() {
        FakeWorld world = new FakeWorld();
        // A 14-log trunk with the crown at the top and one leaf next to the bottom log.
        world.tree(0, 0, 14, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES);
        world.set(1, 1, 0, Material.SPRUCE_LEAVES);
        TreeTracker tracker = new TreeTracker(FakeWorld.TYPES);
        TreeRecord record = tracker.track(WORLD, world, 0, 14, 0, BIG, 0);
        assertNotNull(record);
        for (int y = 3; y <= 14; y++) {
            world.remove(Pos.pack(0, y, 0));
        }
        Map<Long, Integer> fell = tracker.settle(record, world, 0);
        assertFalse(fell.containsKey(Pos.pack(1, 1, 0)), "the leaf next to the stump is still held");
        assertEquals(world.leavesOf(Material.SPRUCE_LEAVES).size() - 1, fell.size());
        assertEquals(1, tracker.size());
    }

    @Test
    void otherSpeciesLeavesAreNotOwned() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        world.set(0, 2, 1, Material.BIRCH_LEAVES);
        world.set(0, 2, 2, Material.BIRCH_LEAVES);
        Map<Long, Integer> a = owned(world, 0, 1, 0);
        assertFalse(a.containsKey(Pos.pack(0, 2, 1)));
        assertFalse(a.containsKey(Pos.pack(0, 2, 2)));
    }

    @Test
    void otherSpeciesTrunkDoesNotCompete() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        // A birch trunk right inside the oak crown: it can't own oak leaves, so it doesn't keep any.
        for (int y = 1; y <= 6; y++) {
            world.set(2, y, 1, Material.BIRCH_LOG);
        }
        Map<Long, Integer> a = owned(world, 0, 1, 0);
        assertTrue(a.containsKey(Pos.pack(2, 3, 0)));
        assertTrue(a.containsKey(Pos.pack(1, 3, 1)));
    }

    @Test
    void jungleBushOwnsItsOakLeaves() {
        FakeWorld world = new FakeWorld();
        world.set(0, 1, 0, Material.JUNGLE_LOG);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    world.set(dx, 1, dz, Material.OAK_LEAVES);
                }
            }
        }
        world.set(0, 2, 0, Material.OAK_LEAVES);
        assertEquals(9, owned(world, 0, 1, 0).size());
    }

    @Test
    void azaleaTreeOwnsBothLeafTypes() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 4, Material.OAK_LOG, Material.AZALEA_LEAVES);
        world.set(1, 3, 1, Material.FLOWERING_AZALEA_LEAVES);
        Map<Long, Integer> a = owned(world, 0, 1, 0);
        assertTrue(a.containsKey(Pos.pack(1, 3, 1)));
        assertEquals(world.leavesOf(Material.AZALEA_LEAVES).size() + 1, a.size());
    }

    @Test
    void persistentLeavesAreNeverOwned() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        long hedge = Pos.pack(1, 3, 0);
        world.persistent.add(hedge);
        Map<Long, Integer> a = owned(world, 0, 1, 0);
        assertFalse(a.containsKey(hedge));
        assertTrue(a.containsKey(Pos.pack(2, 3, 0)), "reached around the placed leaf");
    }

    @Test
    void placedLogsAreNotPartOfTheTree() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        for (int x = 1; x <= 6; x++) {
            world.set(x, 1, 0, Material.OAK_LOG);
            world.placed.add(Pos.pack(x, 1, 0));
        }
        TreeAnalyzer analyzer = new TreeAnalyzer(world, FakeWorld.TYPES);
        assertEquals(5, analyzer.trunk(0, 3, 0, BIG, Integer.MIN_VALUE).size());
        assertNull(analyzer.trunk(3, 1, 0, BIG, Integer.MIN_VALUE));
        // Wood blocks are building blocks, never part of a tree either.
        world.set(-1, 2, 0, Material.OAK_WOOD);
        assertEquals(5, analyzer.trunk(0, 3, 0, BIG, Integer.MIN_VALUE).size());
    }

    @Test
    void diagonalBranchesBelongToTheTree() {
        FakeWorld world = new FakeWorld();
        // Acacia-like: the trunk steps sideways while going up.
        world.set(0, 1, 0, Material.ACACIA_LOG);
        world.set(0, 2, 0, Material.ACACIA_LOG);
        world.set(1, 3, 0, Material.ACACIA_LOG);
        world.set(2, 4, 1, Material.ACACIA_LOG);
        world.set(2, 5, 1, Material.STRIPPED_OAK_LOG); // other species: not part of it
        TreeAnalyzer analyzer = new TreeAnalyzer(world, FakeWorld.TYPES);
        assertEquals(4, analyzer.trunk(0, 1, 0, BIG, Integer.MIN_VALUE).size());
    }

    @Test
    void fellingKeepsTheStumpAndRefusesHugeStructures() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 8, Material.OAK_LOG, Material.OAK_LEAVES);
        TreeAnalyzer analyzer = new TreeAnalyzer(world, FakeWorld.TYPES);
        Set<Long> above = analyzer.trunk(0, 3, 0, BIG, 3);
        assertEquals(6, above.size());
        assertEquals(Pos.pack(0, 3, 0), above.iterator().next(), "the cut log comes first");
        assertNull(analyzer.trunk(0, 3, 0, 5, 3));
        assertNull(analyzer.trunk(0, 3, 0, BIG, 4), "a log below minY is never the start");
    }

    @Test
    void unloadedChunksStopTheAnalysis() {
        FakeWorld world = new FakeWorld();
        world.tree(15, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        world.set(16, 2, 0, Material.OAK_LOG); // trunk continues into chunk x=1
        world.unloadedChunks.add(FakeWorld.chunk(1, 0));
        assertNull(new TreeAnalyzer(world, FakeWorld.TYPES).trunk(15, 1, 0, BIG, Integer.MIN_VALUE));
    }

    @Test
    void orphansFollowVanillaDistance() {
        FakeWorld world = new FakeWorld();
        world.set(0, 1, 0, Material.OAK_LOG);
        for (int x = 1; x <= 9; x++) {
            world.set(x, 1, 0, Material.OAK_LEAVES);
        }
        world.persistent.add(Pos.pack(3, 1, 0)); // placed leaves still carry the distance, like vanilla
        TreeAnalyzer analyzer = new TreeAnalyzer(world, FakeWorld.TYPES);
        Set<Long> orphans = analyzer.orphansNear(List.of(Pos.pack(5, 2, 0)), Set.of());
        assertEquals(Set.of(Pos.pack(7, 1, 0), Pos.pack(8, 1, 0), Pos.pack(9, 1, 0)), orphans);
        // Leaves already on their way down are not reported again.
        assertEquals(Set.of(Pos.pack(9, 1, 0)), analyzer.orphansNear(List.of(Pos.pack(9, 2, 0)), Set.of(Pos.pack(7, 1, 0), Pos.pack(8, 1, 0))));
        // A log of any kind keeps them alive, as in vanilla.
        world.set(10, 1, 0, Material.OAK_WOOD);
        assertTrue(analyzer.orphansNear(List.of(Pos.pack(5, 2, 0)), Set.of()).isEmpty());
        // An unloaded chunk might hold a log: nothing next to it is dropped.
        world.blocks.remove(Pos.pack(10, 1, 0));
        assertEquals(3, analyzer.orphansNear(List.of(Pos.pack(5, 2, 0)), Set.of()).size());
        world.unloadedChunks.add(FakeWorld.chunk(0, -1));
        assertTrue(analyzer.orphansNear(List.of(Pos.pack(5, 2, 0)), Set.of()).isEmpty());
    }

    @Test
    void recordsSurviveARestart() throws IOException {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        TreeTracker tracker = new TreeTracker(FakeWorld.TYPES);
        TreeRecord record = tracker.track(WORLD, world, 0, 1, 0, BIG, 1234);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        tracker.save(new DataOutputStream(bytes));
        TreeTracker loaded = new TreeTracker(FakeWorld.TYPES);
        assertEquals(1, loaded.load(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        TreeRecord copy = loaded.byLog(WORLD, Pos.pack(0, 3, 0));
        assertNotNull(copy);
        assertEquals(record.leaves, copy.leaves);
        assertEquals(record.logs, copy.logs);
        assertEquals("oak", copy.species);
        assertEquals(1, loaded.expire(1234 + 10, 5));
        assertEquals(0, loaded.size());
    }

    @Test
    void aTreeGrowingOverARecordMakesItStale() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        TreeTracker tracker = new TreeTracker(FakeWorld.TYPES);
        TreeRecord record = tracker.track(WORLD, world, 0, 1, 0, BIG, 0);
        assertEquals(0, tracker.forgetTouching(WORLD, List.of(Pos.pack(20, 1, 20))));
        assertEquals(1, tracker.forgetTouching(WORLD, List.of(Pos.pack(1, 3, 0))));
        assertNull(tracker.byLog(WORLD, Pos.pack(0, 3, 0)));
        assertFalse(tracker.track(WORLD, world, 0, 1, 0, BIG, 0) == record);
    }

    @Test
    void aStaleRecordIsReplacedWhenItsTreeChanged() {
        FakeWorld world = new FakeWorld();
        world.tree(0, 0, 5, Material.OAK_LOG, Material.OAK_LEAVES);
        TreeTracker tracker = new TreeTracker(FakeWorld.TYPES);
        TreeRecord old = tracker.track(WORLD, world, 0, 1, 0, BIG, 0);
        world.remove(Pos.pack(0, 1, 0));
        assertTrue(tracker.settle(old, world, 0).isEmpty());
        // Much later a new tree stands here (placed without a grow event): it has a log the record never had.
        world.set(0, 1, 0, Material.OAK_LOG);
        long later = TreeTracker.RECHECK_AFTER_MILLIS + 1;
        TreeRecord fresh = tracker.track(WORLD, world, 0, 2, 0, BIG, later);
        assertNotNull(fresh);
        assertFalse(fresh == old);
        assertEquals(5, fresh.logs.size());
        assertEquals(1, tracker.size());
        // A tree that only lost logs keeps its record, however long it waited.
        world.remove(Pos.pack(0, 1, 0));
        tracker.settle(fresh, world, later);
        assertTrue(tracker.track(WORLD, world, 0, 2, 0, BIG, later * 10) == fresh);
    }
}
