package nofrills.features.farming;

import meteordevelopment.orbit.EventHandler;
import nofrills.config.Feature;
import nofrills.config.SettingBool;
import nofrills.events.ChatMsgEvent;
import nofrills.misc.Utils;

import java.util.regex.Pattern;

/**
 * Shows a brief title when the garden is confirmed to have no pests —
 * either from a chat message or when the scoreboard pest count drops to 0.
 *
 * Ported from SkyHanni PestFinder.kt (noPestTitle option).
 *
 * Register in Main.java with: eventBus.subscribe(NoPestTitle.class);
 */
public class NoPestTitle {

    public static final Feature instance = new Feature("noPestTitle");

    public static final SettingBool enabled = new SettingBool(true, "enabled", instance);

    // "There are not any Pests on your Garden right now."
    private static final Pattern NO_PEST_PATTERN = Pattern.compile(
        "There are not any Pests on your Garden right now.*"
    );

    private static int prevPests = 0;

    @EventHandler
    private static void onChat(ChatMsgEvent event) {
        if (!instance.isActive() || !enabled.value()) return;
        if (!Utils.isInGarden()) return;

        if (NO_PEST_PATTERN.matcher(event.msg()).matches()) {
            Utils.showTitle("§eNo Pests!", "", 5, 40, 20);
        }
    }

    // Also trigger from scoreboard pest count dropping to 0
    static {
        PestTracker.updateListeners.add(() -> {
            int cur = PestTracker.scoreboardPests;
            if (prevPests > 0 && cur == 0) {
                // Only show if the feature instance is active
                if (instance.isActive() && enabled.value() && Utils.isInGarden()) {
                    Utils.showTitle("§eNo Pests!", "", 5, 40, 20);
                }
            }
            prevPests = cur;
        });
    }
}
