package fakemanhunt;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class FakeManhunt extends JavaPlugin implements Listener {

    private Path serverDir;
    private Path snapshotDir;   // last /save lives here
    private Path restartFlag;   // tells start.sh to restore + restart instead of exiting

    @Override
    public void onEnable() {
        serverDir = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        snapshotDir = serverDir.resolve("snapshot");
        restartFlag = serverDir.resolve("restart.flag");
        try { Files.deleteIfExists(restartFlag); } catch (IOException ignored) { }

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getWorlds().forEach(w -> w.setAutoSave(false));

        // First ever start: take the starting state as the initial restore point.
        if (!Files.isDirectory(snapshotDir)) {
            Bukkit.getScheduler().runTask(this, () -> takeSnapshot(
                    () -> getLogger().info("Created initial snapshot."),
                    e -> getLogger().severe("Initial snapshot failed: " + e)));
        }
        getLogger().info("Autosave disabled. Use /save and /load.");
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        e.getWorld().setAutoSave(false);
    }

    /** Players keep typing vanilla /save-all, which never updates the snapshot; treat it as /save. */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        String name = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
        if (!name.equals("save-all") && !name.equals("minecraft:save-all")) return;
        if (!e.getPlayer().hasPermission("fakemanhunt.use")) return;
        e.setCancelled(true);
        save(e.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "save" -> save(sender);
            case "load" -> {
                if (saving) {
                    sender.sendMessage("§c[Manhunt] A save is in progress, try again in a second.");
                    return true;
                }
                if (!Files.isDirectory(snapshotDir)) {
                    sender.sendMessage("§c[Manhunt] No save found. Use /save first.");
                    return true;
                }
                try {
                    Files.writeString(restartFlag, "load");
                } catch (IOException e) {
                    sender.sendMessage("§c[Manhunt] Load failed: " + e.getMessage());
                    return true;
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.kickPlayer("§e[Manhunt] Loading last save...\n§7Rejoin in ~30 seconds.");
                }
                Bukkit.shutdown(); // start.sh restores the snapshot and starts the server again
            }
        }
        return true;
    }

    private void save(CommandSender sender) {
        if (saving) {
            sender.sendMessage("§c[Manhunt] Already saving, wait a second.");
            return;
        }
        Bukkit.broadcastMessage("§e[Manhunt] Saving...");
        takeSnapshot(() -> {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            Bukkit.broadcastMessage("§a[Manhunt] Saved at " + time + ". /load will return here.");
        }, e -> {
            sender.sendMessage("§c[Manhunt] Save failed: " + e.getMessage());
            getLogger().severe("Save failed: " + e);
        });
    }

    private volatile boolean saving;

    /**
     * Flush everything to disk on the main thread, then copy the world folder(s)
     * into snapshot/ on a background thread so the server doesn't freeze.
     * Loaded chunks get a ticket so they can't unload (and get written) mid-copy;
     * any file that still changes while being copied is copied again.
     */
    private void takeSnapshot(Runnable onDone, java.util.function.Consumer<IOException> onError) {
        saving = true;
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) c.addPluginChunkTicket(this);
        }
        Bukkit.savePlayers();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        List<Path> worldDirs = Bukkit.getWorlds().stream()
                .map(w -> levelRoot(w.getWorldFolder().toPath().toAbsolutePath().normalize()))
                .distinct().toList();

        // Some data files are written on background threads even after "save-all flush",
        // so the copy starts a second later.
        Bukkit.getScheduler().runTaskLater(this, () ->
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    IOException error = null;
                    try {
                        copySnapshot(worldDirs);
                    } catch (IOException e) {
                        error = e;
                    }
                    IOException err = error;
                    Bukkit.getScheduler().runTask(this, () -> {
                        Bukkit.getWorlds().forEach(w -> w.removePluginChunkTickets(this));
                        saving = false;
                        if (err == null) onDone.run(); else onError.accept(err);
                    });
                }), 20L);
    }

    /**
     * Newer versions keep each dimension in world/dimensions/<ns>/<name>, and getWorldFolder()
     * points there; level.dat, players/ and data/ live in the level folder above it.
     * Walk up to the folder that holds level.dat so the whole save gets snapshotted.
     */
    private Path levelRoot(Path worldDir) {
        for (Path p = worldDir; p != null && p.startsWith(serverDir) && !p.equals(serverDir); p = p.getParent()) {
            if (Files.exists(p.resolve("level.dat"))) return p;
        }
        return serverDir.resolve(serverDir.relativize(worldDir).getName(0));
    }

    private void copySnapshot(List<Path> worldDirs) throws IOException {
        Path tmp = serverDir.resolve("snapshot.tmp");
        deleteTree(tmp);
        Files.createDirectories(tmp);
        Map<Path, Path> changed = new HashMap<>();
        for (Path worldDir : worldDirs) {
            Path target = tmp.resolve(serverDir.relativize(worldDir));
            if (!Files.exists(target)) copyTree(worldDir, target, changed);
        }
        for (int attempt = 0; !changed.isEmpty(); attempt++) {
            if (attempt == 10) throw new IOException("world files kept changing during the copy, try again");
            Map<Path, Path> retry = new HashMap<>();
            for (var e : changed.entrySet()) copyFile(e.getKey(), e.getValue(), retry);
            changed = retry;
        }
        Path old = serverDir.resolve("snapshot.old");
        deleteTree(old);
        if (Files.exists(snapshotDir)) Files.move(snapshotDir, old, StandardCopyOption.ATOMIC_MOVE);
        Files.move(tmp, snapshotDir, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(old);
    }

    /** Copies one file; if it was modified while copying, records it in {@code changed} to retry. */
    private static void copyFile(Path src, Path dst, Map<Path, Path> changed) throws IOException {
        if (!Files.exists(src)) return;
        FileTime before = Files.getLastModifiedTime(src);
        long size = Files.size(src);
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        if (!before.equals(Files.getLastModifiedTime(src)) || size != Files.size(src)) changed.put(src, dst);
    }

    private static void copyTree(Path src, Path dst, Map<Path, Path> changed) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                if (!file.getFileName().toString().equals("session.lock")) {
                    copyFile(file, dst.resolve(src.relativize(file)), changed);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
