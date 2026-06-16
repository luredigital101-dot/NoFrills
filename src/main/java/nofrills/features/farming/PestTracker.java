package nofrills.features.farming;

import meteordevelopment.orbit.EventHandler;
import nofrills.events.ChatMsgEvent;
import nofrills.events.ServerJoinEvent;
import nofrills.events.WorldTickEvent;
import nofrills.misc.Utils;

import java.util.*;
import java.util.regex.*;

/**
 * Central pest state manager for NoFrills.
 *
 * Tracks:
 *  – total pest count from the scoreboard ("⏣ The Garden ൠ x3")
 *  – which plot numbers are infested (from the tab-list "Plots: 4, 12, 13")
 *  – spawn/kill events parsed from chat
 *
 * All other pest features read from this class.
 * Ported from SkyHanni's PestApi.kt + PestSpawn.kt.
 */
public class PestTracker {

    // ─── Public State ─────────────────────────────────────────────────────────

    /** Total pests reported on the scoreboard (0–8). */
    public static int scoreboardPests = 0;

    /**
     * Map of plotId → pest count for infested plots.
     * A value of -1 means "at least 1 but exact count unknown" (inaccurate).
     */
    public static final Map<String, Integer> infestedPlots = new LinkedHashMap<>();

    /** Listeners that want to be notified when pest state changes. */
    public static final List<Runnable> updateListeners = new ArrayList<>();

    // ─── Chat Patterns ────────────────────────────────────────────────────────

    // "GROSS! A ൠ Pest has appeared in Plot - 7!"
    private static final Pattern ONE_PEST = Pattern.compile(
        ".*! A .{1,4} Pest has appeared in (?:Plot - )?(.+)!"
    );
    // "YUCK! 4 ൠ Pests have spawned in Plot - 14!"
    private static final Pattern MULTI_PEST = Pattern.compile(
        ".*! (\\d) .{1,4} Pests? have spawned in (?:Plot - )?(.+)!"
    );
    // "While you were offline, ൠ Pests spawned in Plots 12, 9 and 3!"
    private static final Pattern OFFLINE_PEST = Pattern.compile(
        ".*While you were offline, .{1,4} Pests? spawned in Plots (.+)!"
    );
    // "You received 7x Enchanted Potato for killing a Locust!"
    private static final Pattern PEST_KILL = Pattern.compile(
        "You received \\d+x .+ for killing an? (\\w[\\w ]+)!"
    );
    // No pests remaining
    private static final Pattern NO_PESTS = Pattern.compile(
        "There are not any Pests on your Garden right now.*"
    );
    // Scoreboard: " ⏣ The Garden ൠ x3"
    private static final Pattern SB_PESTS = Pattern.compile(
        ".*The Garden.*\u0D3A.*x(\\d+)"
    );
    // Scoreboard: " ⏣ The Garden" (no pests)
    private static final Pattern SB_NO_PESTS = Pattern.compile(
        ".*\u29c2.*The Garden$"
    );
    // Tab list: "  Plots: 4, 12, 13"
    private static final Pattern TAB_PLOTS = Pattern.compile(
        "\\s*Plots: ([\\d, ]+)"
    );
    // Pest kill count (tab or chat), used to fire kill event
    private static int lastKillCount = 0;

    // ─── Event: Tick ──────────────────────────────────────────────────────────

    private static int ticksSinceTabRead = 0;

    @EventHandler
    private static void onTick(WorldTickEvent event) {
        if (!Utils.isInGarden()) return;
        ticksSinceTabRead++;
        if (ticksSinceTabRead >= 20) {
            ticksSinceTabRead = 0;
            readFromTabList();
            readFromScoreboard();
        }
    }

    // ─── Event: Chat ──────────────────────────────────────────────────────────

    @EventHandler
    private static void onChat(ChatMsgEvent event) {
        if (!Utils.isInGarden()) return;
        String msg = event.msg();

        // Pest spawn – 1 pest
        Matcher m1 = ONE_PEST.matcher(msg);
        if (m1.matches()) {
            String plot = normalisePlotName(m1.group(1));
            addPestToPlot(plot, 1);
            notifyAll();
            return;
        }

        // Pest spawn – multiple
        Matcher mm = MULTI_PEST.matcher(msg);
        if (mm.matches()) {
            int count = Integer.parseInt(mm.group(1));
            String plot = normalisePlotName(mm.group(2));
            addPestToPlot(plot, count);
            notifyAll();
            return;
        }

        // Offline pests
        Matcher mo = OFFLINE_PEST.matcher(msg);
        if (mo.matches()) {
            String[] parts = mo.group(1).split(",| and ");
            for (String part : parts) {
                String plot = part.trim();
                if (!plot.isEmpty()) addPestToPlot(plot, -1); // count unknown
            }
            notifyAll();
            return;
        }

        // Pest kill
        Matcher mk = PEST_KILL.matcher(msg);
        if (mk.matches()) {
            String pestName = mk.group(1);
            PestData.PestType type = PestData.PestType.getByName(pestName);
            removeNearestPest();
            PestKillEvent.fire(type);
            notifyAll();
            return;
        }

        // No pests
        if (NO_PESTS.matcher(msg).matches()) {
            reset();
            notifyAll();
        }
    }

    // ─── Event: Server join / world change ────────────────────────────────────

    @EventHandler
    private static void onJoin(ServerJoinEvent event) {
        reset();
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private static void readFromScoreboard() {
        for (String line : Utils.getLines()) {
            String plain = stripColor(line);

            Matcher ms = SB_PESTS.matcher(plain);
            if (ms.matches()) {
                int n = Integer.parseInt(ms.group(1));
                if (n != scoreboardPests) {
                    scoreboardPests = n;
                    notifyAll();
                }
                return;
            }
            if (SB_NO_PESTS.matcher(plain).matches()) {
                if (scoreboardPests != 0) {
                    reset();
                    notifyAll();
                }
                return;
            }
        }
    }

    private static void readFromTabList() {
        for (String line : Utils.getTabListLines()) {
            Matcher mt = TAB_PLOTS.matcher(line);
            if (mt.matches()) {
                Set<String> tabPlots = new HashSet<>();
                for (String p : mt.group(1).split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) tabPlots.add(t);
                }
                // Remove plots no longer infested
                infestedPlots.keySet().retainAll(tabPlots);
                // Add newly infested plots (count unknown until chat/scoreboard confirms)
                for (String p : tabPlots) {
                    infestedPlots.putIfAbsent(p, -1);
                }
                notifyAll();
                return;
            }
        }
    }

    private static void addPestToPlot(String plot, int count) {
        scoreboardPests = Math.max(0, scoreboardPests + Math.max(count, 1));
        int existing = infestedPlots.getOrDefault(plot, 0);
        infestedPlots.put(plot, (existing < 0 || count < 0) ? -1 : existing + count);
    }

    private static void removeNearestPest() {
        if (infestedPlots.isEmpty()) {
            scoreboardPests = Math.max(0, scoreboardPests - 1);
            return;
        }
        // Find infested plot closest to player
        String nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (String id : infestedPlots.keySet()) {
            PestData.GardenPlot plot = PestData.PLOTS.get(id);
            if (plot == null) continue;
            double d = plot.distanceToPlayer();
            if (d < bestDist) { bestDist = d; nearest = id; }
        }
        if (nearest != null) {
            int cur = infestedPlots.get(nearest);
            if (cur <= 1 || cur < 0) {
                infestedPlots.remove(nearest);
            } else {
                infestedPlots.put(nearest, cur - 1);
            }
        }
        scoreboardPests = Math.max(0, scoreboardPests - 1);
    }

    /** Normalises "The Barn" → "0", plot numbers already fine. */
    private static String normalisePlotName(String raw) {
        if (raw.equalsIgnoreCase("The Barn")) return "0";
        return raw.trim();
    }

    public static void reset() {
        scoreboardPests = 0;
        infestedPlots.clear();
    }

    private static void notifyAll() {
        for (Runnable r : updateListeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    /** Strip Minecraft §-colour codes from a string. */
    public static String stripColor(String s) {
        return s.replaceAll("§[0-9a-fk-or]", "");
    }

    // ─── Convenience accessors ────────────────────────────────────────────────

    public static List<String> getInfestedPlotIds() {
        return new ArrayList<>(infestedPlots.keySet());
    }

    public static boolean hasInfestedPlots() {
        return !infestedPlots.isEmpty();
    }

    /** Returns the pest count for a plot, or 0. -1 means "unknown but ≥1". */
    public static int getPestsInPlot(String id) {
        return infestedPlots.getOrDefault(id, 0);
    }

    // ─── Micro event bus for pest kills ───────────────────────────────────────

    /** Lightweight kill event fired by PestTracker when a pest kill chat line is seen. */
    public static class PestKillEvent {
        public final PestData.PestType type; // may be null if unknown pest name
        private PestKillEvent(PestData.PestType type) { this.type = type; }

        private static final List<java.util.function.Consumer<PestKillEvent>> listeners = new ArrayList<>();
        public static void addListener(java.util.function.Consumer<PestKillEvent> l) { listeners.add(l); }
        static void fire(PestData.PestType type) {
            PestKillEvent e = new PestKillEvent(type);
            for (var l : listeners) { try { l.accept(e); } catch (Exception ignored) {} }
        }
    }
}
