package nofrills.features.farming;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;
import nofrills.config.Feature;
import nofrills.config.SettingBool;
import nofrills.events.HudRenderEvent;
import nofrills.events.WorldTickEvent;
import nofrills.misc.Utils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the "Bonus Pest Chance" value from the tab-list widget and displays
 * it as a compact or full HUD element.
 *
 * Ported from SkyHanni's BonusPestChanceDisplay.kt.
 *
 * Register in Main.java with: eventBus.subscribe(BonusPestChanceDisplay.class);
 */
public class BonusPestChanceDisplay {

    public static final Feature instance = new Feature("bonusPestChance");

    // ─── Settings ─────────────────────────────────────────────────────────────
    public static final SettingBool compact = new SettingBool(false, "compact", instance);

    // ─── Pattern ──────────────────────────────────────────────────────────────
    // " Bonus Pest Chance: ൠ70" (after stripping colour codes)
    private static final Pattern BPC_PATTERN = Pattern.compile(
        "\\s*Bonus Pest Chance:\\s*.\\.?([\\d,.]+)"
    );

    // ─── State ────────────────────────────────────────────────────────────────
    private static String displayText = null;
    private static int tickCount = 0;

    // ─── Tick: read tab-list ──────────────────────────────────────────────────

    @EventHandler
    private static void onTick(WorldTickEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        tickCount++;
        if (tickCount % 20 != 0) return;
        tickCount = 0;
        updateFromTabList();
    }

    private static void updateFromTabList() {
        List<String> lines = Utils.getTabListLines();
        for (String raw : lines) {
            String line = PestTracker.stripColor(raw);
            Matcher m = BPC_PATTERN.matcher(line);
            if (!m.matches()) continue;

            String amountRaw = m.group(1).replace(",", "");
            int amount;
            try { amount = Integer.parseInt(amountRaw); }
            catch (NumberFormatException e) { return; }

            // Check if the line contains strikethrough-style markers (disabled)
            // In plain text the "§m" is stripped, but if value is 0 we show disabled
            boolean disabled = amount == 0;

            if (compact.value()) {
                displayText = "§2ൠ BPC " + (disabled ? "§c§m" : "§f") + amount + "%";
            } else {
                displayText = "§2ൠ Bonus Pest Chance "
                    + (disabled ? "§c§m" : "§f") + amount + "%"
                    + (disabled ? "§r §cDISABLED" : "");
            }
            return;
        }
        displayText = null; // Widget not active / not on garden
    }

    // ─── HUD render ───────────────────────────────────────────────────────────

    @EventHandler
    private static void onHudRender(HudRenderEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;
        if (displayText == null) return;

        event.context.drawTextWithShadow(
            event.textRenderer,
            Text.literal(displayText),
            5, 105, 0xFFFFFF
        );
    }
}
