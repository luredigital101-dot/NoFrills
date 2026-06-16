package nofrills.features.farming;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;
import nofrills.config.Feature;
import nofrills.config.SettingBool;
import nofrills.events.HudRenderEvent;
import nofrills.events.ServerJoinEvent;
import nofrills.events.WorldTickEvent;
import nofrills.misc.Utils;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks Mantid reforge bonus: each pest kill while wearing a Mantid piece
 * grants +1 bonus (capped at 20) that expires after 10 minutes per kill.
 *
 * Ported from SkyHanni's MantidKillDisplay.kt.
 *
 * Register in Main.java with: eventBus.subscribe(MantidDisplay.class);
 */
public class MantidDisplay {

    public static final Feature instance = new Feature("mantidDisplay");

    // ─── Settings ─────────────────────────────────────────────────────────────
    public static final SettingBool onlyWithArmor   = new SettingBool(false, "onlyWithArmor",   instance);
    public static final SettingBool showExpireTimer = new SettingBool(true,  "showExpireTimer", instance);

    // ─── Constants ────────────────────────────────────────────────────────────
    private static final int  MAX_BONUS      = 20;
    private static final long EXPIRE_MS      = 10L * 60 * 1000; // 10 minutes
    private static final long GROUP_MARGIN_MS = 5_000;           // group within 5 s

    // ─── State ────────────────────────────────────────────────────────────────
    /** Queue of expiry timestamps (ms), oldest first. */
    private static final Deque<Long> expireQueue = new ArrayDeque<>();
    private static int tickCount = 0;

    static {
        // Subscribe to pest kills via PestTracker's mini bus
        PestTracker.PestKillEvent.addListener(e -> onPestKill());
    }

    // ─── Pest kill listener ───────────────────────────────────────────────────

    private static void onPestKill() {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        if (onlyWithArmor.value() && !isWearingMantid()) return;
        expireQueue.addLast(System.currentTimeMillis() + EXPIRE_MS);
        // Cap at MAX_BONUS
        while (expireQueue.size() > MAX_BONUS) expireQueue.pollFirst();
    }

    // ─── Tick: prune expired entries ──────────────────────────────────────────

    @EventHandler
    private static void onTick(WorldTickEvent event) {
        if (!instance.isActive()) return;
        tickCount++;
        if (tickCount % 20 != 0) return;
        tickCount = 0;
        long now = System.currentTimeMillis();
        while (!expireQueue.isEmpty() && expireQueue.peekFirst() <= now) {
            expireQueue.pollFirst();
        }
    }

    // ─── Server join: Mantid resets on world change ───────────────────────────

    @EventHandler
    private static void onJoin(ServerJoinEvent event) {
        expireQueue.clear();
    }

    // ─── HUD render ───────────────────────────────────────────────────────────

    @EventHandler
    private static void onHudRender(HudRenderEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        if (onlyWithArmor.value() && !isWearingMantid()) return;

        int bonus     = Math.min(expireQueue.size(), MAX_BONUS);
        String bonCol = bonus >= MAX_BONUS ? "§a" : "§c";

        int x = 5, y = 115;
        event.context.drawTextWithShadow(
            event.textRenderer,
            Text.literal("§2Mantid: " + bonCol + bonus + "§7/§a" + MAX_BONUS),
            x, y, 0xFFFFFF
        );

        if (showExpireTimer.value() && bonus > 0) {
            long now = System.currentTimeMillis();
            // Find earliest expiry in the "group" (entries within GROUP_MARGIN_MS of the first)
            long firstExpiry = expireQueue.peekFirst();
            int groupCount = 0;
            for (long t : expireQueue) {
                if (t - firstExpiry <= GROUP_MARGIN_MS) groupCount++;
                else break;
            }
            long remaining = Math.max(0, firstExpiry - now);
            String noun = groupCount == 1 ? "1 pest expires" : groupCount + " pests expire";
            event.context.drawTextWithShadow(
                event.textRenderer,
                Text.literal("§e" + noun + ": §b" + PestSpawnTimer.formatDuration(remaining)),
                x, y + 10, 0xFFFFFF
            );
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns true if the player has any armor piece with the "mantid" reforge.
     * In 1.21 Fabric, item NBT is accessed via the SkyBlock ExtraAttributes compound.
     */
    private static boolean isWearingMantid() {
        if (nofrills.Main.mc.player == null) return false;
        for (net.minecraft.item.ItemStack stack : nofrills.Main.mc.player.getArmorItems()) {
            if (stack == null || stack.isEmpty()) continue;
            var nbt = Utils.getCustomData(stack);
            if (nbt != null) {
                // "modifier" key holds reforge name e.g. "mantid"
                var val = nbt.getString("modifier");
                if (val.isPresent() && val.get().equalsIgnoreCase("mantid")) return true;
            }
        }
        return false;
    }
}
