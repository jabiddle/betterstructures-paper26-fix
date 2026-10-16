package diag;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Read-only diagnostics for BetterStructures' new-chunk and paste pipelines. Every 10 s it logs, for the
 * server's primary world: chunk loads, new chunks, and whether each new chunk is still loaded 1-2 ticks later;
 * the sizes of NewChunkLoadEvent's internal queues; and a reflective dump of Schematic's active paste operation.
 * Changes nothing. Temporary use only: it logs every 10 seconds.
 */
public final class BSDiag extends JavaPlugin implements Listener {
    private long loads, newLoads, newStill1, newStill2, newGone1, offMain;
    private String firstThread;
    private String worldName;

    @Override
    public void onEnable() {
        worldName = Bukkit.getWorlds().get(0).getName();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::report, 200L, 200L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent e) {
        if (!e.getWorld().getName().equals(worldName)) return;
        loads++;
        if (!Bukkit.isPrimaryThread()) offMain++;
        if (firstThread == null) firstThread = Thread.currentThread().getName();
        if (!e.isNewChunk()) return;
        newLoads++;
        World w = e.getWorld();
        int x = e.getChunk().getX(), z = e.getChunk().getZ();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (w.isChunkLoaded(x, z)) newStill1++; else newGone1++;
        }, 1L);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (w.isChunkLoaded(x, z)) newStill2++; }, 2L);
    }

    private void report() {
        String bs = "n/a";
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("BetterStructures");
            Class<?> c = Class.forName("com.magmaguy.betterstructures.listeners.NewChunkLoadEvent", false, p.getClass().getClassLoader());
            bs = "deferred=" + size(c, "deferredNewChunks") + " recent=" + size(c, "recentlyGeneratedChunks")
                    + " loading=" + size(c, "loadingChunks") + " remainingNew=" + get(c, "remainingNewInspections")
                    + " drainTask=" + (get(c, "deferredDrainTask") != null)
                    + " reloading=" + Class.forName("com.magmaguy.betterstructures.BetterStructures", false, p.getClass().getClassLoader())
                            .getMethod("isReloading").invoke(null);
        } catch (Throwable t) {
            bs = "reflect-fail " + t;
        }
        getLogger().info("loads=" + loads + " new=" + newLoads + " newLoadedAfter1t=" + newStill1 + " newGoneAfter1t=" + newGone1
                + " newLoadedAfter2t=" + newStill2 + " offMain=" + offMain + " thread=" + firstThread + " | BS " + bs);
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("BetterStructures");
            Class<?> s = Class.forName("com.magmaguy.betterstructures.worldedit.Schematic", false, p.getClass().getClassLoader());
            Object op = get(s, "activePasteOperation");
            Object task = get(s, "activePasteTask");
            String taskState = task == null ? "null" : ("cancelled=" + ((org.bukkit.scheduler.BukkitTask) task).isCancelled()
                    + " queued=" + Bukkit.getScheduler().isQueued(((org.bukkit.scheduler.BukkitTask) task).getTaskId()));
            getLogger().info("PASTE queue=" + size(s, "pasteQueue") + " distributing=" + get(s, "isDistributedPasting")
                    + " task=" + taskState + " active=" + dump(op, 0));
        } catch (Throwable t) {
            getLogger().info("PASTE reflect-fail " + t);
        }
    }

    private static String dump(Object o, int depth) {
        if (o == null) return "null";
        Class<?> c = o.getClass();
        if (depth > 0 && !c.getName().startsWith("com.magmaguy")) {
            if (o instanceof org.bukkit.Location l) return "(" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + ")";
            if (o instanceof org.bukkit.Chunk ch) return "Chunk(" + ch.getX() + "," + ch.getZ() + " loaded=" + ch.isLoaded() + ")";
            if (o instanceof java.util.concurrent.CompletableFuture<?> f) return "Future(done=" + f.isDone() + " exc=" + f.isCompletedExceptionally() + ")";
            String v = String.valueOf(o);
            return v.length() > 80 ? v.substring(0, 80) : v;
        }
        StringBuilder sb = new StringBuilder(c.getSimpleName()).append('{');
        for (Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                String name = f.getName();
                if (name.startsWith("this$")) {
                    Object outer = v;
                    String sc = "?";
                    try {
                        Field fc = outer.getClass().getSuperclass().getDeclaredField("schematicContainer");
                        fc.setAccessible(true);
                        Object cont = fc.get(outer);
                        sc = String.valueOf(cont.getClass().getMethod("getClipboardFilename").invoke(cont));
                    } catch (Throwable ignored) { }
                    sb.append("outer=").append(outer.getClass().getSimpleName()).append('[').append(sc).append("] ");
                    continue;
                }
                if (v != null && v.getClass().getName().startsWith("com.magmaguy") && depth < 1) sb.append(name).append('=').append(dump(v, depth + 1)).append(' ');
                else if (v instanceof java.util.Iterator<?>) sb.append(name).append("=iter ");
                else sb.append(name).append('=').append(dump(v, 1)).append(' ');
            } catch (Throwable t) {
                sb.append(f.getName()).append("=! ");
            }
        }
        return sb.append('}').toString();
    }

    private static Object get(Class<?> c, String f) throws Exception {
        Field fld = c.getDeclaredField(f);
        fld.setAccessible(true);
        return fld.get(null);
    }

    private static int size(Class<?> c, String f) throws Exception {
        Object o = get(c, f);
        if (o instanceof Map<?, ?> m) return m.size();
        if (o instanceof Collection<?> col) return col.size();
        return -1;
    }
}
