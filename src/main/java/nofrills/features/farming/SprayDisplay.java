package nofrills.features.farming;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;
import nofrills.config.Feature;
import nofrills.config.SettingBool;
import nofrills.events.ChatMsgEvent;
import nofrills.events.HudRenderEvent;
import nofrills.events.WorldRenderEvent;
import nofrills.events.WorldTickEvent;
import nofrills.misc.RenderColor;
import nofrills.misc.Utils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static nofrills.Main.mc;

/**
 * Tracks spray applied per plot and shows:
 *   • A HUD line with current plot's spray + time remaining
 *   • Chat notification when a spray expires
 *   • Plot outline when holding the Sprayonator
 *
 * Ported from SkyHanni's SprayDisplay.kt + SprayFeatures.kt.
 *
 * Register in Main.java with: eventBus.subscribe(SprayDisplay.class);
 */
public class SprayDisplay {

    public static final Feature instance = new Feature("sprayDisplay");

    // ─── Settings ─────────────────────────────────────────────────────────────
    public static final SettingBool showHud           = new SettingBool(true,  "showHud",           instance);
    public static final SettingBool showNotSprayed    = new SettingBool(true,  "showNotSprayed",    instance);
    public static final SettingBool expiryNotify      = new SettingBool(true,  "expiryNotify",      instance);
    public static final SettingBool outlineOnHold     = new SettingBool(true,  "outlineOnHold",     instance);
    public static final SettingBool sprayChangeHelper = new SettingBool(true,  "sprayChangeHelper", instance);

    // ─── Spray duration ───────────────────────────────────────────────────────
    /** Default spray duration = 30 minutes */
    private static final long SPRAY_DURATION_MS = 30L * 60 * 1000;

    private static final Set<String> SPRAYONATOR_IDS = Set.of("SPRAYONATOR", "SPRAYONATOR_2");

    // ─── Patterns ─────────────────────────────────────────────────────────────
    // "§a§lSPRAYONATOR! §r§7Your selected material is now §r§aPlant Matter§r§7!"
    private static final Pattern CHANGE_MATERIAL = Pattern.compile(
        ".*SPRAYONATOR! .*Your selected material is now (.+)!?"
    );
    // "§a§lSPRAYONATOR! §r§7You sprayed §r§aPlant Matter §r§7on §r§bPlot - 3§r§7!"
    private static final Pattern SPRAY_APPLIED = Pattern.compile(
        ".*SPRAYONATOR! .*You sprayed (.+) on (?:Plot - )?(.+)!?"
    );

    // ─── State ────────────────────────────────────────────────────────────────
    /** plotId → PlotSpray */
    private static final Map<String, PlotSpray> sprays = new HashMap<>();
    /** Last material selected in Sprayonator (shown as helper text) */
    private static String selectedMaterial = null;
    private static long   selectedMaterialShownUntil = 0;

    private static int tickCount = 0;

    // ─── Events ───────────────────────────────────────────────────────────────

    @EventHandler
    private static void onChat(ChatMsgEvent event) {
        if (!instance.isActive()) return;
        String msg = event.msg();

        // Material selection helper
        if (sprayChangeHelper.value()) {
            Matcher mc = CHANGE_MATERIAL.matcher(msg);
            if (mc.matches()) {
                String name = PestTracker.stripColor(mc.group(1)).trim();
                PestData.SprayType type = PestData.SprayType.getByName(name);
                if (type != null) {
                    selectedMaterial = "§a" + type.displayName + " §7(attracts: §6" + type.getEffect() + "§7)";
                    selectedMaterialShownUntil = System.currentTimeMillis() + 5000;
                }
            }
        }

        // Spray applied to a plot
        Matcher ms = SPRAY_APPLIED.matcher(msg);
        if (ms.matches()) {
            String sprayName = PestTracker.stripColor(ms.group(1)).trim();
            String plotId    = ms.group(2).trim();
            PestData.SprayType type = PestData.SprayType.getByName(sprayName);
            if (type != null) {
                sprays.put(plotId, new PlotSpray(type, System.currentTimeMillis() + SPRAY_DURATION_MS));
            }
        }
    }

    @EventHandler
    private static void onTick(WorldTickEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        tickCount++;
        if (tickCount % 20 != 0) return;
        tickCount = 0;

        if (expiryNotify.value()) {
            checkExpiredSprays();
        }
    }

    @EventHandler
    private static void onHudRender(HudRenderEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;

        int x = 5, y = 80; // below PestSpawnTimer

        // Spray material change helper (5 s after switching)
        if (selectedMaterial != null && System.currentTimeMillis() < selectedMaterialShownUntil) {
            event.context.drawTextWithShadow(event.textRenderer, Text.literal(selectedMaterial), x, y, 0xFFFFFF);
            y += 10;
        }

        if (!showHud.value()) return;

        // Find which plot the player is standing in
        String currentPlot = getCurrentPlotId();
        if (currentPlot == null) return;

        PlotSpray spray = sprays.get(currentPlot);
        String line;
        if (spray == null || System.currentTimeMillis() > spray.expiryMs) {
            if (!showNotSprayed.value()) return;
            line = "§cNot sprayed!";
        } else {
            long remaining = spray.expiryMs - System.currentTimeMillis();
            String timerStr = PestSpawnTimer.formatDuration(remaining);
            String col = remaining < 5 * 60 * 1000 ? "§c" : "§b";
            line = "§eSprayed: §a" + spray.type.displayName + " §7- " + col + timerStr;
        }

        event.context.drawTextWithShadow(event.textRenderer, Text.literal(line), x, y, 0xFFFFFF);
    }

    @EventHandler
    private static void onWorldRender(WorldRenderEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        if (!outlineOnHold.value()) return;
        if (!isHoldingSprayonator()) return;

        String plotId = getCurrentPlotId();
        if (plotId == null) return;

        PestData.GardenPlot plot = PestData.PLOTS.get(plotId);
        if (plot != null) {
            event.drawOutline(plot.boundingBox, true, RenderColor.fromArgb(0xffffff55));
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private static void checkExpiredSprays() {
        long now = System.currentTimeMillis();
        List<String> justExpired = new ArrayList<>();
        for (Map.Entry<String, PlotSpray> e : sprays.entrySet()) {
            if (!e.getValue().notified && now > e.getValue().expiryMs) {
                e.getValue().notified = true;
                justExpired.add("§bPlot " + e.getKey());
            }
        }
        if (!justExpired.isEmpty()) {
            Utils.infoFormat("§7Spray expired on: {}", String.join("§7, ", justExpired));
        }
    }

    private static String getCurrentPlotId() {
        for (Map.Entry<String, PestData.GardenPlot> e : PestData.PLOTS.entrySet()) {
            if (e.getValue().isPlayerAbove()) return e.getKey();
        }
        return null;
    }

    private static boolean isHoldingSprayonator() {
        return SPRAYONATOR_IDS.contains(Utils.getSkyblockId(Utils.getHeldItem()));
    }

    // ─── Data class ───────────────────────────────────────────────────────────

    private static class PlotSpray {
        final PestData.SprayType type;
        final long expiryMs;
        boolean notified = false;

        PlotSpray(PestData.SprayType type, long expiryMs) {
            this.type     = type;
            this.expiryMs = expiryMs;
        }
    }
}
