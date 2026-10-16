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
 * Fix: while a paste operation is active, keep plugin tickets on every chunk of its footprint (single
 * schematic corner + size, or all module inputs of a ModularPaste dungeon). Tickets load chunks
 * asynchronously and are never added in response to chunk loads, so there is no cascade. They are released
 * a few seconds after the operation finishes. As a fallback for operations whose footprint can't be read,
 * the pending chunk request (and nextLocation if present) is ticketed via World#addPluginChunkTicket even
 * if the chunk already unloaded before the callback ran.
 */
public final class BSPasteKeeper extends JavaPlugin {
    private static final long RELEASE_DELAY_TICKS = 100L;
    private static final int MAX_FOOTPRINT_CHUNKS = 512;

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
                if (footprint.isEmpty()) setFootprint(footprintOf(operation));
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

    /** Chunks covered by the operation's corner + clipboard dimensions or modular inputs, or empty if unknown. */
    private Set<Key> footprintOf(Object operation) throws Exception {
        Set<Key> keys = new HashSet<>();
        Field adjusted = findField(operation.getClass(), "adjustedLocation");
        Field clip = findField(operation.getClass(), "clipboard");
        if (adjusted != null && clip != null) {
            addBox(keys, (Location) adjusted.get(operation), clip.get(operation), 0);
            return keys;
        }
        Field c = findField(operation.getClass(), "corner");
        Field outer = findField(operation.getClass(), "this$0");
        if (c != null && outer != null) {
            Object outerObj = outer.get(operation);
            if (outerObj != null && schematicClipboard.getDeclaringClass().isInstance(outerObj)) {
                addBox(keys, (Location) c.get(operation), schematicClipboard.get(outerObj), 0);
                Field baseF = findField(operation.getClass(), "base");
                if (baseF != null && baseF.get(operation) instanceof Location base && base.getWorld() != null) {
                    keys.add(new Key(base.getWorld(), base.getBlockX() >> 4, base.getBlockZ() >> 4));
                }
                return keys;
            }
        }
        Field inputsField = findField(operation.getClass(), "inputs");
        if (inputsField != null && inputsField.get(operation) instanceof Collection<?> inputs) {
            for (Object in : inputs) {
                if (in == null) continue;
                Field locF = findField(in.getClass(), "location");
                Field clipF = findField(in.getClass(), "clipboard");
                Field rotF = findField(in.getClass(), "rotation");
                if (locF == null || clipF == null) continue;
                int rot = rotF != null ? (int) rotF.get(in) : 0;
                addBox(keys, (Location) locF.get(in), clipF.get(in), rot);
                if (keys.size() > MAX_FOOTPRINT_CHUNKS) return Set.of();
            }
            return keys;
        }
        return Set.of();
    }

    private static void addBox(Set<Key> keys, Location corner, Object clipboard, int rotation) throws Exception {
        if (corner == null || clipboard == null || corner.getWorld() == null) return;
        Object dims = accessible(clipboard.getClass().getMethod("getDimensions")).invoke(clipboard);
        Method mx = accessible(dims.getClass().getMethod("x")), mz = accessible(dims.getClass().getMethod("z"));
        int dx = (int) mx.invoke(dims), dz = (int) mz.invoke(dims);
        int width = Math.floorMod(rotation, 180) == 0 ? dx : dz;
        int depth = Math.floorMod(rotation, 180) == 0 ? dz : dx;
        int minX = corner.getBlockX() >> 4, maxX = (corner.getBlockX() + Math.max(width, 1) - 1) >> 4;
        int minZ = corner.getBlockZ() >> 4, maxZ = (corner.getBlockZ() + Math.max(depth, 1) - 1) >> 4;
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > MAX_FOOTPRINT_CHUNKS) return;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                keys.add(new Key(corner.getWorld(), x, z));
            }
        }
    }

    /** Fallback: ticket the chunk BetterStructures is waiting for even if it unloaded before the next tick. */
    private void hookPending(Object operation) throws IllegalAccessException {
        Field nextLocField = findField(operation.getClass(), "nextLocation");
        if (nextLocField != null && nextLocField.get(operation) instanceof Location loc && loc.getWorld() != null) {
            keepChunk(new Key(loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
        }
        Field chunksField = findField(operation.getClass(), "chunks");
        if (chunksField == null) return;
        Object readiness = chunksField.get(operation);
        if (readiness == null) return;
        Object request = pending.get(readiness);
        if (!(request instanceof CompletableFuture<?> future) || !hooked.add(future)) return;
        future.thenAccept(result -> {
            if (result instanceof Chunk chunk) {
                Runnable keep = () -> {
                    if (!isEnabled()) return;
                    keepChunk(new Key(chunk.getWorld(), chunk.getX(), chunk.getZ()));
                };
                if (Bukkit.isPrimaryThread()) keep.run();
                else Bukkit.getScheduler().runTask(this, keep);
            }
        });
    }

    private void keepChunk(Key k) {
        Long prev = releaseAt.get(k);
        if (prev == null) {
            k.world().addPluginChunkTicket(k.x(), k.z(), this);
            releaseAt.put(k, tick + RELEASE_DELAY_TICKS);
            fallbackKeeps++;
        } else if (prev != Long.MAX_VALUE) {
            releaseAt.put(k, tick + RELEASE_DELAY_TICKS);
        }
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

    private static Method accessible(Method m) {
        m.setAccessible(true);
        return m;
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
