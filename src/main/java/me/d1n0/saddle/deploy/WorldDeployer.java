package me.d1n0.saddle.deploy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

/**
 * File-level work behind {@code /deploy}: turns a world folder into a
 * distributable map by dropping everything tied to the players who played it,
 * while keeping terrain and builds (region), entities, points of interest,
 * saved data (scoreboards, command storage, maps, ...), datapacks and the
 * world settings in level.dat.
 *
 * Pure file operations; callers are responsible for making sure the game is
 * not writing to the folders involved (see {@link DeployCommand}).
 */
public final class WorldDeployer {
    private static final Logger LOGGER = LoggerFactory.getLogger("saddle");

    /**
     * Top-level world entries that only hold per-player state: "players" is the
     * 26.x layout (data/advancements/stats); the others are the legacy layout
     * still found in worlds that were never re-saved by a newer version.
     */
    private static final Set<String> PLAYER_ENTRIES = Set.of("players", "playerdata", "advancements", "stats");
    /** Top-level files that never belong in a copy. */
    private static final Set<String> SKIPPED_FILES = Set.of("session.lock");
    /** Level data files (current and backup) that may reference the last player. */
    private static final List<String> LEVEL_DATA_FILES = List.of("level.dat", "level.dat_old");
    /**
     * Keys of the level.dat "Data" compound tied to whoever last played the
     * world: the singleplayer owner (current and legacy form) and the last
     * played timestamp. The game reads a missing LastPlayed as 0 and writes
     * it again on the first save.
     */
    private static final List<String> PLAYER_LEVEL_KEYS = List.of("singleplayer_uuid", "Player", "LastPlayed");
    private static final String DEFAULT_COPY_SUFFIX = "-deploy";

    private static volatile Path pendingInPlace;

    private WorldDeployer() {}

    public record CopyResult(Path target, long files) {}

    // ------------------------------------------------------------------
    // Copy
    // ------------------------------------------------------------------

    /** Default copy destination: a free "&lt;world&gt;-deploy" folder next to the world. */
    public static Path defaultCopyTarget(Path worldDir) {
        Path container = container(worldDir);
        String base = worldDir.getFileName() + DEFAULT_COPY_SUFFIX;
        Path candidate = container.resolve(base);
        for (int i = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); i++) {
            candidate = container.resolve(base + "-" + i);
        }
        return candidate;
    }

    /**
     * Resolves a user-supplied copy destination. Blank input selects the
     * default; relative paths resolve against the folder containing the world
     * (the saves folder in singleplayer, the server folder on a dedicated
     * server). The destination must be outside the world and either missing
     * or an empty directory. With {@code withinContainer} it must also stay
     * inside the containing folder, so operators who do not control the host
     * cannot write elsewhere on its file system.
     */
    public static Path resolveCopyTarget(Path worldDir, String rawPath, boolean withinContainer) {
        String text = rawPath == null ? "" : rawPath.strip();
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"")
                || text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isEmpty()) return defaultCopyTarget(worldDir);

        Path target;
        try {
            target = Path.of(text);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path: " + e.getMessage());
        }
        if (!target.isAbsolute()) target = container(worldDir).resolve(target);
        target = target.toAbsolutePath().normalize();

        Path world = worldDir.toAbsolutePath().normalize();
        Path realWorld = realPathOfExisting(world);
        Path realTarget = realPathOfExisting(target);
        if (target.startsWith(world) || world.startsWith(target)
                || realTarget.startsWith(realWorld) || realWorld.startsWith(realTarget)) {
            throw new IllegalArgumentException("The destination must be outside the world folder: " + target);
        }
        if (withinContainer) {
            Path container = container(worldDir);
            if (!target.startsWith(container) || !realTarget.startsWith(realPathOfExisting(container))) {
                throw new IllegalArgumentException("Only the server console or the singleplayer owner can deploy "
                        + "outside " + container + "; choose a destination inside it");
            }
        }
        if (Files.exists(target)) {
            if (!Files.isDirectory(target) || !isEmptyDirectory(target)) {
                throw new IllegalArgumentException("The destination already exists and is not an empty folder: " + target);
            }
        }
        return target;
    }

    /**
     * Copies the world to {@code target} without player data or the session
     * lock, then strips the player references from the copied level data. On
     * failure everything written for the copy is removed again, including
     * parent folders created for it.
     */
    public static CopyResult copyWithoutPlayerData(Path worldDir, Path target) throws IOException {
        Path source = worldDir.toAbsolutePath().normalize();
        // Topmost folder this copy creates; null when the target already exists (empty).
        Path createdTop = null;
        for (Path p = target; p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS); p = p.getParent()) {
            createdTop = p;
        }
        long[] files = {0};
        boolean complete = false;
        try {
            Files.createDirectories(target);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(dir);
                    if (isTopLevel(relative, PLAYER_ENTRIES)) return FileVisitResult.SKIP_SUBTREE;
                    Files.createDirectories(target.resolve(relative.toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(file);
                    if (isTopLevel(relative, PLAYER_ENTRIES) || isTopLevel(relative, SKIPPED_FILES)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Files.copy(file, target.resolve(relative.toString()),
                            StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                    files[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
            stripLevelData(target);
            complete = true;
        } finally {
            if (!complete) {
                try {
                    if (createdTop != null) deleteTree(createdTop, false);
                    else deleteTree(target, true);
                } catch (IOException cleanup) {
                    LOGGER.warn("Failed to remove the incomplete deploy copy at {}", target, cleanup);
                }
            }
        }
        return new CopyResult(target, files[0]);
    }

    // ------------------------------------------------------------------
    // In place
    // ------------------------------------------------------------------

    /**
     * Arms an in-place deployment of {@code worldDir}. It runs from
     * {@link #onServerStopped} — only after the final save, because the game
     * writes every online player's data again while shutting down.
     */
    public static void scheduleInPlace(Path worldDir) {
        pendingInPlace = worldDir.toAbsolutePath().normalize();
    }

    /** A new server start cancels any in-place deployment left over from a failed stop. */
    public static void onServerStarting() {
        pendingInPlace = null;
    }

    /** Runs the armed in-place deployment if it targets the world that just stopped. */
    public static void onServerStopped(Path worldDir) {
        Path pending = pendingInPlace;
        pendingInPlace = null;
        if (pending == null || !pending.equals(worldDir.toAbsolutePath().normalize())) return;
        try {
            removePlayerData(pending);
            LOGGER.info("Deployed world '{}' in place: player data removed", pending);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to deploy world '{}' in place", pending, e);
        }
    }

    /** Deletes the player folders and strips player references from the level data. */
    static void removePlayerData(Path worldDir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(worldDir)) {
            for (Path entry : entries) {
                if (PLAYER_ENTRIES.contains(entry.getFileName().toString())) {
                    deleteTree(entry, false);
                }
            }
        }
        stripLevelData(worldDir);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void stripLevelData(Path worldDir) throws IOException {
        for (String name : LEVEL_DATA_FILES) {
            Path file = worldDir.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            CompoundTag data = root.getCompound("Data").orElse(null);
            if (data == null) continue;
            boolean changed = false;
            for (String key : PLAYER_LEVEL_KEYS) {
                changed |= data.remove(key) != null;
            }
            if (!changed) continue;
            // Write-then-move so an I/O error never leaves a truncated level.dat.
            Path temp = Files.createTempFile(worldDir, "level", ".dat");
            try {
                NbtIo.writeCompressed(root, temp);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static boolean isTopLevel(Path relative, Set<String> names) {
        return relative.getNameCount() == 1 && names.contains(relative.toString());
    }

    private static Path container(Path worldDir) {
        Path world = worldDir.toAbsolutePath().normalize();
        Path parent = world.getParent();
        return parent != null ? parent : world;
    }

    private static boolean isEmptyDirectory(Path dir) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Real path of the deepest existing ancestor with the remainder appended,
     * so symlinked destinations cannot point back into the world folder.
     */
    private static Path realPathOfExisting(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) return path;
        try {
            return existing.toRealPath().resolve(existing.relativize(path)).normalize();
        } catch (IOException e) {
            return path;
        }
    }

    /** Deletes a tree without following symlinks; {@code keepRoot} empties it instead. */
    private static void deleteTree(Path root, boolean keepRoot) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(root);
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                if (!keepRoot || !dir.equals(root)) Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
