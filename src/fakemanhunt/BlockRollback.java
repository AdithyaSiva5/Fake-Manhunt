package fakemanhunt;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Remembers what every block looked like at the last /qsave, the first time it changes
 * afterwards, so /qload can put it back. Chests and other containers keep their contents.
 * Items, XP, TNT, pearls, boats etc. that appeared after the save are removed on /qload,
 * otherwise players could keep both the restored inventory and the dropped loot.
 *
 * Only kept in memory: after a restart only changes made since the restart are rolled back.
 */
final class BlockRollback implements Listener {

    private static final BlockFace[] AROUND = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    // Events fire before the block changes, so getState() here is still the old block.
    private final Map<Block, BlockState> original = new HashMap<>();
    private final Set<UUID> spawned = new HashSet<>();
    private boolean tracking;

    /** Called on /qsave: the world as it is now becomes the restore point. */
    void reset() {
        original.clear();
        spawned.clear();
        tracking = true;
    }

    void start() {
        tracking = true;
    }

    /** Puts every changed block back and removes entities spawned since the save. Returns blocks restored. */
    int restore() {
        for (UUID id : spawned) {
            Entity e = org.bukkit.Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        spawned.clear();
        int count = 0;
        for (BlockState s : original.values()) {
            // Empty whatever container is there now, or replacing it would spill its items.
            if (s.getBlock().getState(false) instanceof Container c) c.getInventory().clear();
            // No physics, so torches, sand and water stay exactly where they were.
            if (s.update(true, false)) count++;
        }
        // Keep the originals: blocks changed again before the next /qsave still go back to them.
        return count;
    }

    private void record(Block b) {
        if (tracking && b != null) original.putIfAbsent(b, b.getState());
    }

    /** The block and its neighbours, for things that also pop torches, doors, plants... */
    private void recordAround(Block b) {
        record(b);
        for (BlockFace f : AROUND) record(b.getRelative(f));
        record(b.getRelative(BlockFace.UP, 2));
    }

    private void record(List<Block> blocks) {
        for (Block b : blocks) recordAround(b);
    }

    private void recordStates(List<BlockState> states) {
        for (BlockState s : states) record(s.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { recordAround(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!tracking) return;
        original.putIfAbsent(e.getBlock(), e.getBlockReplacedState());
        if (e instanceof BlockMultiPlaceEvent m) recordStates(m.getReplacedBlockStates());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        recordAround(e.getBlock());
        record(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { record(e.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { recordAround(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) { record(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { recordAround(e.getBlock()); }

    // Also covers BlockSpreadEvent and EntityBlockFormEvent (frost walker, snow golems).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { record(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) { record(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) { record(e.getToBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) { record(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTree(StructureGrowEvent e) { recordStates(e.getBlocks()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoneMeal(BlockFertilizeEvent e) {
        record(e.getBlock());
        recordStates(e.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) {
        recordAround(e.getBlock());
        for (Block b : e.getBlocks()) {
            record(b);
            record(b.getRelative(e.getDirection()));
        }
        record(e.getBlock().getRelative(e.getDirection()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) {
        recordAround(e.getBlock());
        for (Block b : e.getBlocks()) {
            record(b);
            record(b.getRelative(e.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        record(e.getBlock());
        record(e.getBlockClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        record(e.getBlock());
        record(e.getBlockClicked());
    }

    // Falling sand, endermen, trampled farmland, ravagers, silverfish...
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) { recordAround(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        record(e.getBlock());
        if (e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d) {
            record(e.getBlock().getRelative(d.getFacing()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSponge(SpongeAbsorbEvent e) {
        record(e.getBlock());
        recordStates(e.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent e) { record(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) { record(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PortalCreateEvent e) { recordStates(e.getBlocks()); }

    // Doors, trapdoors, levers, cake, tilling, stripping logs, stepping on farmland...
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        record(b);
        record(b.getRelative(BlockFace.UP));
        record(b.getRelative(BlockFace.DOWN));
    }

    // So chest, furnace, barrel... contents go back too.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        InventoryHolder h = e.getInventory().getHolder(false);
        if (h instanceof BlockInventoryHolder bh) {
            record(bh.getBlock());
        } else if (h instanceof DoubleChest dc) {
            if (dc.getLeftSide(false) instanceof BlockInventoryHolder l) record(l.getBlock());
            if (dc.getRightSide(false) instanceof BlockInventoryHolder r) record(r.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent e) {
        if (!tracking) return;
        Entity en = e.getEntity();
        if (en instanceof Item || en instanceof ExperienceOrb || en instanceof TNTPrimed
                || en instanceof FallingBlock || en instanceof Projectile || en instanceof Vehicle
                || en instanceof ArmorStand || en instanceof EnderCrystal) {
            spawned.add(en.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (tracking) spawned.add(e.getItemDrop().getUniqueId());
    }
}
