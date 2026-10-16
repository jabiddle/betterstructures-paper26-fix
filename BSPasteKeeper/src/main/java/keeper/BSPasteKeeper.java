package keeper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Workaround for BetterStructures 2.7.7 on Paper 26.3.
 *
 * BetterStructures pastes through one serial queue. Each step asks PasteChunkReadiness whether the target
 * chunk is loaded; if not, it requests it with getChunkAtAsync and checks isLoaded() on a later tick. Paper
 * 26.3 unloads that chunk before the check, so the request repeats forever and every later paste waits.
 *
 * Fix: while a paste operation is active, keep plugin tickets on every chunk of its footprint (corner plus
 * schematic size). Tickets load chunks asynchronously and are never added in response to chunk loads, so
 * there is no cascade. They are released a few seconds after the operation finishes. As a fallback for
 * operations whose footprint can't be read, the pending chunk request is ticketed when it completes.
 */
public final class BSPasteKeeper extends JavaPlugin {
    private static final long RELEASE_DELAY_TICKS = 100L;
    private static final int MAX_FOOTPRINT_CHUNKS = 64;

    private record Key(World world, int x, int z) { }

    private final Set<CompletableFuture<?>> hooked = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Key, Long> releaseAt = new HashMap<>();   // ticketed chunks -> tick to release (MAX = in use)
    private Field activeOperation;
    private Field pasteQueue;
    private Field pending;
    private Field schematicClipboard;
    private Object lastOperation;
    private Set<Key> footprint = Set.of();
    private long tick;
    private long operations, footprintOps, fallbackKeeps, stalledTicks;

    @Override
    public void onEnable() {
        Plugin bs = Bukkit.getPluginManager().getPlugin("BetterStructures");
        if (bs == null) {
            getLogger().warning("BetterStructures not found; nothing to do.");
            return;
        }
        try {
            ClassLoader loader = bs.getClass().getClassLoader();
            Class<?> schematic = Class.forName("com.magmaguy.betterstructures.worldedit.Schematic", false, loader);
            activeOperation = accessible(schematic.getDeclaredField("activePasteOperation"));
            pasteQueue = accessible(schematic.getDeclaredField("pasteQueue"));
            pending = accessible(Class.forName("com.magmaguy.betterstructures.worldedit.PasteChunkReadiness", false, loader)
                    .getDeclaredField("pending"));
            schematicClipboard = accessible(Class.forName("com.magmaguy.betterstructures.buildingfitter.FitAnything", false, loader)
                    .getDeclaredField("schematicClipboard"));
        } catch (ReflectiveOperationException e) {
            getLogger().warning("BetterStructures internals changed (" + e + "); workaround disabled.");
            return;
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::report, 1200L, 1200L);
        getLogger().info("Pinning BetterStructures paste footprints.");
    }

    private void tick() {
        tick++;
        try {
            Object operation = activeOperation.get(null);
            if (operation != lastOperation) {
                lastOperation = operation;
                stalledTicks = 0;
                setFootprint(operation == null ? Set.of() : footprintOf(operation));
                if (operation != null) operations++;
            } else if (operation != null) {
                stalledTicks++;
            }
            if (operation != null) hookPending(operation);
        } catch (Throwable t) {
            // Never let the workaround break the server tick.
        }
        Iterator<Map.Entry<Key, Long>> it = releaseAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Long> e = it.next();
            if (e.getValue() <= tick) {
                e.getKey().world().removePluginChunkTicket(e.getKey().x(), e.getKey().z(), this);
                it.remove();
            }
        }
    }

    private void setFootprint(Set<Key> next) {
        for (Key k : footprint) if (!next.contains(k)) releaseAt.put(k, tick + RELEASE_DELAY_TICKS);
        for (Key k : next) {
            if (releaseAt.put(k, Long.MAX_VALUE) == null) k.world().addPluginChunkTicket(k.x(), k.z(), this);
        }
        footprint = next;
        if (!next.isEmpty()) footprintOps++;
    }

    /** Chunks covered by the operation's corner + clipboard dimensions, or empty if unknown. */
    private Set<Key> footprintOf(Object operation) throws Exception {
        Location corner = null;
        Object clipboard = null;
        Field adjusted = findField(operation.getClass(), "adjustedLocation");
        Field clip = findField(operation.getClass(), "clipboard");
        if (adjusted != null && clip != null) {
            corner = (Location) adjusted.get(operation);
            clipboard = clip.get(operation);
        } else {
            Field c = findField(operation.getClass(), "corner");
            Field outer = findField(operation.getClass(), "this$0");
            if (c != null && outer != null) {
                corner = (Location) c.get(operation);
                clipboard = schematicClipboard.get(outer.get(operation));
            }
        }
        if (corner == null || clipboard == null || corner.getWorld() == null) return Set.of();
        Object dims = clipboard.getClass().getMethod("getDimensions").invoke(clipboard);
        Method mx = dims.getClass().getMethod("x"), mz = dims.getClass().getMethod("z");
        int width = (int) mx.invoke(dims), depth = (int) mz.invoke(dims);
        int minX = corner.getBlockX() >> 4, maxX = (corner.getBlockX() + Math.max(width, 1) - 1) >> 4;
        int minZ = corner.getBlockZ() >> 4, maxZ = (corner.getBlockZ() + Math.max(depth, 1) - 1) >> 4;
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > MAX_FOOTPRINT_CHUNKS) return Set.of();
        Set<Key> keys = new HashSet<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) keys.add(new Key(corner.getWorld(), x, z));
        return keys;
    }

    /** Fallback: ticket the chunk BetterStructures is waiting for as soon as it loads. */
    private void hookPending(Object operation) throws IllegalAccessException {
        Field chunksField = findField(operation.getClass(), "chunks");
        if (chunksField == null) return;
        Object readiness = chunksField.get(operation);
        if (readiness == null) return;
        Object request = pending.get(readiness);
        if (!(request instanceof CompletableFuture<?> future) || !hooked.add(future)) return;
        future.thenAccept(result -> {
            if (result instanceof Chunk chunk) {
                Runnable keep = () -> {
                    if (!isEnabled() || !chunk.isLoaded()) return;
                    Key k = new Key(chunk.getWorld(), chunk.getX(), chunk.getZ());
                    Long prev = releaseAt.get(k);
                    if (prev == null) {
                        chunk.addPluginChunkTicket(this);
                        releaseAt.put(k, tick + RELEASE_DELAY_TICKS);
                        fallbackKeeps++;
                    } else if (prev != Long.MAX_VALUE) {
                        releaseAt.put(k, tick + RELEASE_DELAY_TICKS);
                    }
                };
                if (Bukkit.isPrimaryThread()) keep.run();
                else Bukkit.getScheduler().runTask(this, keep);
            }
        });
    }

    private void report() {
        int queued = -1;
        try {
            queued = ((Collection<?>) pasteQueue.get(null)).size();
        } catch (Throwable ignored) {
        }
        if (operations == 0 && queued <= 0) return;
        getLogger().info("pastes started=" + operations + " pinned=" + footprintOps + " fallbackKeeps=" + fallbackKeeps
                + " queued=" + queued + " activeFor=" + stalledTicks / 20 + "s tickets=" + releaseAt.size());
    }

    private static Field accessible(Field f) {
        f.setAccessible(true);
        return f;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return accessible(c.getDeclaredField(name));
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    @Override
    public void onDisable() {
        for (Key k : releaseAt.keySet()) k.world().removePluginChunkTicket(k.x(), k.z(), this);
        releaseAt.clear();
    }
}
