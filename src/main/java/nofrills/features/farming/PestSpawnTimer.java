package nofrills.features.farming;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;
import nofrills.config.Feature;
import nofrills.config.SettingBool;
import nofrills.events.ChatMsgEvent;
import nofrills.events.HudRenderEvent;
import nofrills.events.WorldTickEvent;
import nofrills.misc.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HUD element that tracks:
 *   • Time since last pest spawned
 *   • Pest spawn cooldown (parsed from tab-list widget)
 *   • Rolling average of spawn intervals
 *   • Warning title + sound when cooldown expires
 *
 * Ported from SkyHanni's PestSpawnTimer.kt.
 *
 * Register in Main.java with: eventBus.subscribe(PestSpawnTimer.class);
 */
public class PestSpawnTimer {

    public static final Feature instance = new Feature("pestSpawnTimer");

    // ─── Settings ─────────────────────────────────────────────────────────────
    public static final SettingBool showLastSpawn   = new SettingBool(true,  "showLastSpawn",   instance);
    public static final SettingBool showCooldown    = new SettingBool(true,  "showCooldown",    instance);
    public static final SettingBool showAverage     = new SettingBool(true,  "showAverage",     instance);
    public static final SettingBool warnOnReady     = new SettingBool(true,  "warnOnReady",     instance);

    // ─── Patterns (tab-list widget lines) ─────────────────────────────────────
    // " Cooldown: 1m 58s" / " Cooldown: READY" / " Cooldown: MAX PESTS"
    private static final Pattern COOLDOWN_PATTERN = Pattern.compile(
        "\\s*Cooldown:\\s*(?:(?<min>\\d+)m)?\\s*(?:(?<sec>\\d+)s)?(?<ready>READY)?(?<max>MAX PESTS)?"
    );
    // Spawn messages from PestSpawnAlert
    private static final Pattern ONE_PEST  = Pattern.compile(".*! A .{1,4} Pest has appeared in (?:Plot - )?(.+)!");
    private static final Pattern MULTI_PEST = Pattern.compile(".*! (\\d) .{1,4} Pests? have spawned in (?:Plot - )?(.+)!");

    // ─── State ────────────────────────────────────────────────────────────────
    private static long lastSpawnMs        = Long.MIN_VALUE;  // -MIN means "never"
    private static long cooldownEndMs      = Long.MIN_VALUE;  // -MIN means unknown
    private static boolean cooldownReady   = false;
    private static boolean maxPests        = false;
    private static boolean warnedThisCycle = false;

    private static final List<Long> spawnIntervals = new ArrayList<>(); // ms between spawns
    private static final int MAX_SAMPLES = 20;

    // HUD screen position (pixels from top-left)
    private static final int HUD_X = 5;
    private static final int HUD_Y = 35; // below PestFinder if also enabled

    // ─── Chat: detect pest spawn ──────────────────────────────────────────────

    @EventHandler
    private static void onChat(ChatMsgEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        String msg = event.msg();

        boolean spawned = false;
        if (ONE_PEST.matcher(msg).matches() || MULTI_PEST.matcher(msg).matches()) {
            spawned = true;
        }

        if (spawned) {
            long now = System.currentTimeMillis();
            if (lastSpawnMs != Long.MIN_VALUE) {
                long interval = now - lastSpawnMs;
                if (interval > 0 && interval < 20 * 60 * 1000L) { // cap at 20 min
                    if (spawnIntervals.size() >= MAX_SAMPLES) spawnIntervals.removeFirst();
                    spawnIntervals.add(interval);
                }
            }
            lastSpawnMs = now;
            warnedThisCycle = false;
        }
    }

    // ─── Tick: read cooldown from tab-list ────────────────────────────────────

    private static int tickCount = 0;

    @EventHandler
    private static void onTick(WorldTickEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;

        tickCount++;
        if (tickCount % 20 != 0) return; // run every second
        tickCount = 0;

        for (String line : Utils.getTabListLines()) {
            String plain = PestTracker.stripColor(line);
            Matcher m = COOLDOWN_PATTERN.matcher(plain);
            if (!m.matches()) continue;

            if (m.group("ready") != null) {
                cooldownReady = true;
                maxPests      = false;
                cooldownEndMs = Long.MIN_VALUE;
                onCooldownExpired();
                return;
            }
            if (m.group("max") != null) {
                cooldownReady = false;
                maxPests      = true;
                cooldownEndMs = Long.MIN_VALUE;
                return;
            }
            int mins = m.group("min") != null ? Integer.parseInt(m.group("min")) : 0;
            int secs = m.group("sec") != null ? Integer.parseInt(m.group("sec")) : 0;
            long remaining = (mins * 60L + secs) * 1000L;
            if (remaining > 0) {
                cooldownEndMs = System.currentTimeMillis() + remaining;
                cooldownReady = false;
                maxPests      = false;
            }
            return;
        }
    }

    // ─── HUD render ───────────────────────────────────────────────────────────

    @EventHandler
    private static void onHudRender(HudRenderEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;

        List<String> lines = buildLines();
        int y = HUD_Y;
        for (String line : lines) {
            event.context.drawTextWithShadow(event.textRenderer, Text.literal(line), HUD_X, y, 0xFFFFFF);
            y += 10;
        }
    }

    private static List<String> buildLines() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();

        if (showLastSpawn.value()) {
            if (lastSpawnMs == Long.MIN_VALUE) {
                out.add("§eLast pest: §cNone this session");
            } else {
                out.add("§eLast pest: §b" + formatDuration(now - lastSpawnMs) + " §7ago");
            }
        }

        if (showCooldown.value()) {
            String cdStr;
            if (maxPests) {
                cdStr = "§cMax Pests!";
            } else if (cooldownReady) {
                cdStr = "§aReady!";
            } else if (cooldownEndMs == Long.MIN_VALUE) {
                cdStr = "§7Unknown";
            } else {
                long remaining = cooldownEndMs - now;
                cdStr = remaining <= 0 ? "§aReady!" : "§b" + formatDuration(remaining);
            }
            out.add("§eCooldown: " + cdStr);
        }

        if (showAverage.value() && spawnIntervals.size() >= 3) {
            long avg = 0;
            for (long v : spawnIntervals) avg += v;
            avg /= spawnIntervals.size();
            out.add("§eAvg spawn: §b" + formatDuration(avg));
        }

        return out;
    }

    // ─── Cooldown-expired warning ─────────────────────────────────────────────

    private static void onCooldownExpired() {
        if (!warnOnReady.value() || warnedThisCycle) return;
        warnedThisCycle = true;
        Utils.showTitle("§aPest Ready!", "§7Spawn cooldown expired", 5, 60, 20);
        Utils.infoFormat("§aPest spawn cooldown has expired!");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Format milliseconds → "1m 23s" or "45s". */
    static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long secs  = ms / 1000;
        long mins  = secs / 60;
        long hours = mins / 60;
        mins %= 60;
        secs %= 60;
        if (hours > 0) return hours + "h " + mins + "m";
        if (mins > 0)  return mins + "m " + secs + "s";
        return secs + "s";
    }
}
