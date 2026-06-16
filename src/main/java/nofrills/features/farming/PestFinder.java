package nofrills.features.farming;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import nofrills.config.Feature;
import nofrills.config.SettingBool;
import nofrills.config.SettingColor;
import nofrills.events.HudRenderEvent;
import nofrills.events.WorldRenderEvent;
import nofrills.misc.RenderColor;
import nofrills.misc.Utils;

import java.util.List;
import java.util.Map;

import static nofrills.Main.mc;

/**
 * HUD display showing total pest count and which plots are infested,
 * plus optional world-space plot outlines and waypoint text.
 *
 * Ported from SkyHanni's PestFinder.kt.
 *
 * Register in Main.java with: eventBus.subscribe(PestFinder.class);
 */
public class PestFinder {

    public static final Feature instance = new Feature("pestFinder");

    // ─── Settings ─────────────────────────────────────────────────────────────
    public static final SettingBool showHud        = new SettingBool(true,  "showHud",        instance);
    public static final SettingBool showWorldText  = new SettingBool(true,  "showWorldText",  instance);
    public static final SettingBool showOutlines   = new SettingBool(true,  "showOutlines",   instance);
    public static final SettingBool onlyWithVacuum = new SettingBool(false, "onlyWithVacuum", instance);
    public static final SettingColor outlineColor  = new SettingColor(RenderColor.fromArgb(0xffff5555), "outlineColor", instance);
    public static final SettingColor insideColor   = new SettingColor(RenderColor.fromArgb(0xffffff55), "insideColor",  instance);

    // Vacuum & lasso SkyBlock IDs
    private static final java.util.Set<String> VACUUM_IDS = java.util.Set.of(
        "SKYMART_VACUUM", "SKYMART_TURBO_VACUUM", "SKYMART_HYPER_VACUUM",
        "INFINI_VACUUM", "INFINI_VACUUM_HOOVERIUS"
    );
    private static final java.util.Set<String> LASSO_IDS = java.util.Set.of(
        "PEST_LASSO", "THYST_PET_ITEM"  // placeholder IDs
    );

    // ─── HUD ──────────────────────────────────────────────────────────────────

    /** X/Y screen position for the HUD (fraction of screen). */
    private static int hudX = 5;
    private static int hudY = 5;

    @EventHandler
    private static void onHudRender(HudRenderEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        if (!showHud.value()) return;
        if (onlyWithVacuum.value() && !isHoldingVacuumOrLasso()) return;

        List<String> lines = buildHudLines();
        int y = hudY;
        for (String line : lines) {
            event.context.drawTextWithShadow(event.textRenderer, Text.literal(line), hudX, y, 0xFFFFFF);
            y += 10;
        }
    }

    private static List<String> buildHudLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        int total = PestTracker.scoreboardPests;
        out.add("§6Pests: §e" + total + "§6/§e8");

        Map<String, Integer> infested = PestTracker.infestedPlots;
        if (infested.isEmpty() && total > 0) {
            out.add("§cBugged pests! Run §f/desk");
        } else {
            for (Map.Entry<String, Integer> e : infested.entrySet()) {
                int count = e.getValue();
                String countStr = count < 0 ? "1+" : String.valueOf(count);
                String noun = (count == 1) ? "pest" : "pests";
                out.add("§e" + countStr + " §c" + noun + " §7in §bPlot " + e.getKey());
            }
        }
        return out;
    }

    // ─── World Render ─────────────────────────────────────────────────────────

    @EventHandler
    private static void onWorldRender(WorldRenderEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        if (!showOutlines.value() && !showWorldText.value()) return;
        if (onlyWithVacuum.value() && !isHoldingVacuumOrLasso()) return;

        for (Map.Entry<String, Integer> e : PestTracker.infestedPlots.entrySet()) {
            PestData.GardenPlot plot = PestData.PLOTS.get(e.getKey());
            if (plot == null) continue;

            boolean playerInside = plot.isPlayerAbove();
            RenderColor col = playerInside ? insideColor.value() : outlineColor.value();

            if (showOutlines.value()) {
                event.drawOutline(plot.boundingBox, true, col);
            }

            if (showWorldText.value() && !playerInside) {
                int count = e.getValue();
                String countStr = count < 0 ? "?" : String.valueOf(count);
                String noun = (count == 1) ? "pest" : "pests";
                Vec3d textPos = plot.getMiddle().add(0, 1.5, 0);
                event.drawDistanceScaledText(
                    textPos,
                    Text.literal("§e" + countStr + " §c" + noun + " §7in §bPlot " + e.getKey()),
                    0.05f,
                    true,
                    col
                );
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isHoldingVacuumOrLasso() {
        String id = Utils.getSkyblockId(Utils.getHeldItem());
        return VACUUM_IDS.contains(id) || LASSO_IDS.contains(id);
    }

    /** Teleports player to the nearest infested plot. Called from command. */
    public static void teleportToNearest() {
        if (!Utils.isInGarden()) {
            Utils.info("§cYou must be in your Garden to use this command.");
            return;
        }
        if (PestTracker.infestedPlots.isEmpty()) {
            Utils.info("§eNo infested plots detected.");
            return;
        }
        String nearest = null;
        double best = Double.MAX_VALUE;
        for (String id : PestTracker.infestedPlots.keySet()) {
            PestData.GardenPlot p = PestData.PLOTS.get(id);
            if (p == null) continue;
            double d = p.distanceToPlayer();
            if (d < best) { best = d; nearest = id; }
        }
        if (nearest != null) {
            Utils.sendMessage("/plottp " + nearest);
        }
    }
}
