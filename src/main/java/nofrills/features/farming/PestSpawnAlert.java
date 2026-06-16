package nofrills.features.farming;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;
import nofrills.config.Feature;
import nofrills.config.SettingBool;
import nofrills.events.ChatMsgEvent;
import nofrills.misc.Utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows a title alert and optional chat notification whenever pests spawn.
 * Also suppresses the vanilla TP-click line that Hypixel sends after spawn.
 *
 * Combines SkyHanni PestSpawn.kt + PestSpawnSound.kt.
 *
 * Register in Main.java with: eventBus.subscribe(PestSpawnAlert.class);
 */
public class PestSpawnAlert {

    public static final Feature instance = new Feature("pestSpawnAlert");

    // ─── Settings ─────────────────────────────────────────────────────────────
    public static final SettingBool showTitle      = new SettingBool(true,  "showTitle",      instance);
    public static final SettingBool compactChat    = new SettingBool(true,  "compactChat",    instance);
    public static final SettingBool suppressTpLine = new SettingBool(true,  "suppressTpLine", instance);
    public static final SettingBool playSound      = new SettingBool(true,  "playSound",      instance);
    public static final SettingBool muteVacuum     = new SettingBool(false, "muteVacuum",     instance);

    // ─── Patterns ─────────────────────────────────────────────────────────────
    // "GROSS! A ൠ Pest has appeared in Plot - 7!"
    private static final Pattern ONE_PEST = Pattern.compile(
        ".*! A .{1,4} Pest has appeared in (?:Plot - )?(.+)!"
    );
    // "YUCK! 4 ൠ Pests have spawned in Plot - 14!"
    private static final Pattern MULTI_PEST = Pattern.compile(
        ".*! (\\d) .{1,4} Pests? have spawned in (?:Plot - )?(.+)!"
    );
    // Offline: "While you were offline, ൠ Pests spawned in Plots 12, 9 and 3!"
    private static final Pattern OFFLINE_PEST = Pattern.compile(
        ".*! While you were offline, .{1,4} Pests? spawned in Plots (.+)!"
    );
    // Hypixel's "CLICK HERE to teleport to the plot!" followup
    private static final Pattern CLICK_TP = Pattern.compile(
        "\\s*CLICK HERE to teleport to the plot!"
    );
    // Vacuum fire sound characteristic pitch
    private static final float VACUUM_PITCH = 1.4920635f;

    private static long lastSpawnMs = 0;

    // ─── Chat event ───────────────────────────────────────────────────────────

    @EventHandler
    private static void onChat(ChatMsgEvent event) {
        if (!instance.isActive() || !Utils.isInGarden()) return;

        String msg = event.msg();

        // Suppress the CLICK HERE line if we show our own compact message
        if (suppressTpLine.value() && CLICK_TP.matcher(msg).matches()) {
            if (System.currentTimeMillis() - lastSpawnMs < 2000) {
                event.setCancelled(true);
            }
            return;
        }

        // 1 pest
        Matcher m1 = ONE_PEST.matcher(msg);
        if (m1.matches()) {
            String plot = m1.group(1);
            handleSpawn(1, plot, event);
            return;
        }

        // Multiple pests
        Matcher mm = MULTI_PEST.matcher(msg);
        if (mm.matches()) {
            int count = Integer.parseInt(mm.group(1));
            String plot = mm.group(2);
            handleSpawn(count, plot, event);
            return;
        }

        // Offline spawn — don't suppress, but show title
        Matcher mo = OFFLINE_PEST.matcher(msg);
        if (mo.matches()) {
            String plotList = mo.group(1);
            if (showTitle.value()) {
                Utils.showTitle("§cPests Spawned!", "§7" + plotList, 5, 70, 20);
            }
        }
    }

    private static void handleSpawn(int count, String plot, ChatMsgEvent event) {
        lastSpawnMs = System.currentTimeMillis();

        String noun   = count == 1 ? "Pest" : "Pests";
        String notice = "§e" + count + " §a" + noun + " §aSpawned in §bPlot " + plot + "§a!";

        if (showTitle.value()) {
            Utils.showTitle(notice, "", 5, 70, 20);
        }

        if (playSound.value()) {
            playSpawnSound();
        }

        // Compact mode: replace the verbose Hypixel line with our own.
        if (compactChat.value()) {
            event.setCancelled(true);
            Utils.infoFormat("{}", notice);
        }
    }

    private static void playSpawnSound() {
        // Play a pling sound — uses MC's built-in sounds, no NBS dependency needed.
        if (nofrills.Main.mc.player != null) {
            nofrills.Main.mc.player.playSound(
                SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
                1.0f,
                VACUUM_PITCH
            );
        }
    }
}
