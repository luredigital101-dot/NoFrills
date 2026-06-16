package nofrills.features.farming;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import nofrills.config.Feature;
import nofrills.config.SettingBool;
import nofrills.config.SettingColor;
import nofrills.events.InputEvent;
import nofrills.events.SpawnParticleEvent;
import nofrills.events.WorldRenderEvent;
import nofrills.events.ServerJoinEvent;
import nofrills.misc.CurveSolver;
import nofrills.misc.RenderColor;
import nofrills.misc.Utils;
import org.lwjgl.glfw.GLFW;

import java.util.Set;

import static nofrills.Main.mc;

/**
 * When the player left-clicks with a vacuum in hand the feature starts
 * collecting the angry-villager particles emitted by pests and feeds them
 * into the existing NoFrills CurveSolver (Bezier fitter) to predict where
 * the pest is.  The solved position is shown as a beam + label in the world.
 *
 * Additionally, firework + enchant-table particles can optionally be hidden
 * to reduce clutter while vacuum scanning.
 *
 * Ported from SkyHanni's PestParticleWaypoint.kt.
 * Reuses NoFrills' built-in CurveSolver (same class VacuumSolver already uses).
 *
 * Register in Main.java with: eventBus.subscribe(PestWaypoint.class);
 */
public class PestWaypoint {

    public static final Feature instance = new Feature("pestWaypoint");

    // ─── Settings ─────────────────────────────────────────────────────────────
    public static final SettingBool hideParticles = new SettingBool(false, "hideParticles", instance);
    public static final SettingBool drawLine      = new SettingBool(true,  "drawLine",      instance);
    public static final SettingColor waypointColor = new SettingColor(RenderColor.fromArgb(0xaaff5555), "color", instance);

    private static final long ACTIVE_WINDOW_MS = 5000; // listen for particles for 5 s after click
    private static final long SHOW_FOR_MS      = 8000; // keep waypoint visible for 8 s after last particle

    private static final Set<String> VACUUM_IDS = Set.of(
        "SKYMART_VACUUM", "SKYMART_TURBO_VACUUM", "SKYMART_HYPER_VACUUM",
        "INFINI_VACUUM", "INFINI_VACUUM_HOOVERIUS"
    );

    // ─── State ────────────────────────────────────────────────────────────────
    private static final CurveSolver solver = new CurveSolver();
    private static long clickedAtMs   = Long.MIN_VALUE;
    private static long lastParticleMs = Long.MIN_VALUE;
    private static Vec3d solved        = null;

    // ─── Left-click: arm the solver ───────────────────────────────────────────

    @EventHandler
    private static void onInput(InputEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        if (!Utils.matchesKey(mc.options.attackKey, event)) return;
        if (event.action != GLFW.GLFW_PRESS) return;
        if (!isHoldingVacuum()) return;

        reset();
        clickedAtMs = System.currentTimeMillis();
        solver.start();
    }

    // ─── Particles: feed solver ───────────────────────────────────────────────

    @EventHandler
    private static void onParticle(SpawnParticleEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;

        // Hide fireworks/enchants if option is on (reduces visual noise)
        if (hideParticles.value() && isDecorativeParticle(event)) {
            event.setCancelled(true);
            return;
        }

        // Only process within the active window after a click
        if (clickedAtMs == Long.MIN_VALUE) return;
        if (System.currentTimeMillis() - clickedAtMs > ACTIVE_WINDOW_MS) return;

        // Only care about the curved red particles from the vacuum
        if (!event.isCurveParticle()) return;
        if (solver.getLastDist(event.pos) > 5.0) return;

        solver.addPos(event.pos);
        lastParticleMs = System.currentTimeMillis();

        solver.getSolvedPos().ifPresent(pos -> solved = pos);
    }

    // ─── World render ─────────────────────────────────────────────────────────

    @EventHandler
    private static void onRender(WorldRenderEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        if (solved == null) return;

        long now = System.currentTimeMillis();

        // Auto-clear if player walked close enough or timer expired
        if (now - lastParticleMs > SHOW_FOR_MS) { reset(); return; }
        if (mc.player != null) {
            double dx = solved.x - mc.player.getX();
            double dz = solved.z - mc.player.getZ();
            if (Math.sqrt(dx * dx + dz * dz) < 8.0) { reset(); return; }
        }

        // Check if solved pos is a plot middle (pure-centre hit = less accurate)
        boolean isPlotMiddle = isNearPlotMiddle(solved);
        RenderColor col = isPlotMiddle
            ? RenderColor.fromArgb(0xaaffff55)  // yellow = plot middle / less certain
            : waypointColor.value();

        event.drawBeam(solved, 256, true, col);
        event.drawDistanceScaledText(
            solved.subtract(0, 0.25, 0),
            Text.literal(isPlotMiddle ? "§ePest (plot middle?)" : "§aPest"),
            0.05f, true, col
        );

        if (drawLine.value()) {
            event.drawTracer(solved, col);
        }
    }

    // ─── Server join ──────────────────────────────────────────────────────────

    @EventHandler
    private static void onJoin(ServerJoinEvent event) {
        reset();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isHoldingVacuum() {
        return VACUUM_IDS.contains(Utils.getSkyblockId(Utils.getHeldItem()));
    }

    private static boolean isDecorativeParticle(SpawnParticleEvent event) {
        String name = event.particleName();
        return name.contains("FIREWORK") || name.contains("ENCHANT");
    }

    /** Returns true if the solved position is very close to a known plot centre. */
    private static boolean isNearPlotMiddle(Vec3d pos) {
        for (PestData.GardenPlot p : PestData.PLOTS.values()) {
            Vec3d mid = p.getMiddle();
            double dx = Math.abs(pos.x - mid.x);
            double dz = Math.abs(pos.z - mid.z);
            if (dx < 2.0 && dz < 2.0) return true;
        }
        return false;
    }

    private static void reset() {
        solver.clear();
        clickedAtMs    = Long.MIN_VALUE;
        lastParticleMs = Long.MIN_VALUE;
        solved         = null;
    }
}
