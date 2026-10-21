package fakemanhunt;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Piglins barter ender pearls, fire resistance potions and obsidian much more often. */
final class BoostedBarter implements Listener {

    private final FakeManhunt plugin;

    BoostedBarter(FakeManhunt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBarter(PiglinBarterEvent e) {
        if (!plugin.getConfig().getBoolean("barter.enabled")) return;
        ItemStack drop = rollDrop();
        if (drop == null) return; // keep the vanilla drop
        List<ItemStack> outcome = e.getOutcome();
        outcome.clear();
        outcome.add(drop);
    }

    private ItemStack rollDrop() {
        FileConfiguration cfg = plugin.getConfig();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double roll = rng.nextDouble(100);

        double pearl = cfg.getDouble("barter.ender-pearl.chance");
        if (roll < pearl) {
            return new ItemStack(Material.ENDER_PEARL,
                    between(cfg.getInt("barter.ender-pearl.min"), cfg.getInt("barter.ender-pearl.max")));
        }
        double fireRes = pearl + cfg.getDouble("barter.fire-resistance.chance");
        if (roll < fireRes) {
            boolean splash = rng.nextDouble(100) < cfg.getDouble("barter.fire-resistance.splash-chance");
            ItemStack potion = new ItemStack(splash ? Material.SPLASH_POTION : Material.POTION);
            PotionMeta meta = (PotionMeta) potion.getItemMeta();
            meta.setBasePotionType(PotionType.FIRE_RESISTANCE);
            potion.setItemMeta(meta);
            return potion;
        }
        if (roll < fireRes + cfg.getDouble("barter.obsidian.chance")) {
            return new ItemStack(Material.OBSIDIAN,
                    between(cfg.getInt("barter.obsidian.min"), cfg.getInt("barter.obsidian.max")));
        }
        return null;
    }

    private static int between(int min, int max) {
        min = Math.max(1, min);
        return ThreadLocalRandom.current().nextInt(min, Math.max(min, max) + 1);
    }
}
