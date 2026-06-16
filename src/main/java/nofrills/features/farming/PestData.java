package nofrills.features.farming;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;

import static nofrills.Main.mc;

/**
 * Central data store for all pest-related constants and shared garden plot layout.
 * Ported from SkyHanni's PestType.kt, SprayType.kt, and GardenPlotApi.
 */
public class PestData {

    // ─── Pest Types ──────────────────────────────────────────────────────────────

    public enum PestType {
        BEETLE      ("Beetle",        "Beetles",    "PEST_BEETLE_MONSTER",         SprayType.DUNG,         CropType.NETHER_WART),
        CRICKET     ("Cricket",       "Crickets",   "PEST_CRICKET_MONSTER",        SprayType.HONEY_JAR,    CropType.CARROT),
        EARTHWORM   ("Earthworm",     "Earthworms", "PEST_EARTHWORM_MONSTER",      SprayType.COMPOST,      CropType.MELON),
        FIELD_MOUSE ("Field Mouse",   "Field Mice", "PEST_FIELD_MOUSE_MONSTER",    null,                   null),
        FLY         ("Fly",           "Flies",      "PEST_FLY_MONSTER",            SprayType.DUNG,         CropType.WHEAT),
        LOCUST      ("Locust",        "Locusts",    "PEST_LOCUST_MONSTER",         SprayType.PLANT_MATTER, CropType.POTATO),
        LUNAR_MOTH  ("Lunar Moth",    "Lunar Moths","PEST_LUNAR_MOTH_MONSTER",     SprayType.MOONDEW,      null),
        MITE        ("Mite",          "Mites",      "PEST_MITE_MONSTER",           SprayType.TASTY_CHEESE, CropType.CACTUS),
        MOSQUITO    ("Mosquito",      "Mosquitoes", "PEST_MOSQUITO_MONSTER",       SprayType.COMPOST,      CropType.SUGAR_CANE),
        MOTH        ("Moth",          "Moths",      "PEST_MOTH_MONSTER",           SprayType.HONEY_JAR,    CropType.COCOA_BEANS),
        RAT         ("Rat",           "Rats",       "PEST_RAT_MONSTER",            SprayType.TASTY_CHEESE, CropType.PUMPKIN),
        SLUG        ("Slug",          "Slugs",      "PEST_SLUG_MONSTER",           SprayType.PLANT_MATTER, CropType.MUSHROOM),
        PRAYING_MANTIS("Praying Mantis","Praying Mantises","PEST_PRAYING_MANTIS_MONSTER",SprayType.JELLY, CropType.WILD_ROSE),
        FIREFLY     ("Firefly",       "Fireflies",  "PEST_FIREFLY_MONSTER",        SprayType.JELLY,        CropType.MOONFLOWER),
        DRAGONFLY   ("Dragonfly",     "Dragonflies","PEST_DRAGONFLY_MONSTER",      SprayType.JELLY,        CropType.SUNFLOWER);

        public final String displayName;
        public final String pluralName;
        public final String internalName;
        public final SprayType spray;
        public final CropType crop;

        PestType(String displayName, String pluralName, String internalName, SprayType spray, CropType crop) {
            this.displayName  = displayName;
            this.pluralName   = pluralName;
            this.internalName = internalName;
            this.spray        = spray;
            this.crop         = crop;
        }

        /** Lookup by display name, case-insensitive. Returns null if not found. */
        public static PestType getByName(String name) {
            for (PestType t : values()) {
                if (t.displayName.equalsIgnoreCase(name)) return t;
            }
            return null;
        }
    }

    // ─── Spray Types ─────────────────────────────────────────────────────────────

    public enum SprayType {
        COMPOST      ("Compost"),
        PLANT_MATTER ("Plant Matter"),
        DUNG         ("Dung"),
        HONEY_JAR    ("Honey Jar"),
        TASTY_CHEESE ("Tasty Cheese"),
        JELLY        ("Jelly"),
        MOONDEW      ("Moondew");

        public final String displayName;

        SprayType(String displayName) {
            this.displayName = displayName;
        }

        /** Returns all PestTypes that are attracted by this spray. */
        public List<PestType> getPests() {
            List<PestType> list = new ArrayList<>();
            for (PestType p : PestType.values()) {
                if (p.spray == this) list.add(p);
            }
            return list;
        }

        /** Builds a short readable description like "Beetle, Fly". */
        public String getEffect() {
            List<PestType> pests = getPests();
            if (pests.isEmpty()) return "Unknown Effect";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pests.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pests.get(i).displayName);
            }
            return sb.toString();
        }

        public static SprayType getByName(String name) {
            for (SprayType s : values()) {
                if (s.displayName.equalsIgnoreCase(name)) return s;
            }
            return null;
        }
    }

    // ─── Crop Types ──────────────────────────────────────────────────────────────

    public enum CropType {
        NETHER_WART  ("Nether Wart"),
        CARROT       ("Carrot"),
        MELON        ("Melon"),
        WHEAT        ("Wheat"),
        POTATO       ("Potato"),
        CACTUS       ("Cactus"),
        SUGAR_CANE   ("Sugar Cane"),
        COCOA_BEANS  ("Cocoa Beans"),
        PUMPKIN      ("Pumpkin"),
        MUSHROOM     ("Mushroom"),
        WILD_ROSE    ("Wild Rose"),
        MOONFLOWER   ("Moonflower"),
        SUNFLOWER    ("Sunflower");

        public final String displayName;
        CropType(String d) { this.displayName = d; }
    }

    // ─── Garden Plot Layout ──────────────────────────────────────────────────────
    // Matches NoFrills' existing PlotBorders coordinate system exactly.

    public static final Map<String, GardenPlot> PLOTS = buildPlots();

    private static Map<String, GardenPlot> buildPlots() {
        Map<String, GardenPlot> map = new LinkedHashMap<>();
        int[][] coords = {
            {  0,   0}, {  0, -96}, {-96,   0}, { 96,   0}, {  0,  96},
            {-96, -96}, { 96, -96}, {-96,  96}, { 96,  96}, {  0,-192},
            {-192,  0}, {192,   0}, {  0, 192}, {-96,-192}, { 96,-192},
            {-192,-96}, {192, -96}, {-192, 96}, {192,  96}, {-96, 192},
            { 96, 192}, {-192,-192},{192,-192}, {-192,192}, {192, 192}
        };
        for (int i = 0; i < coords.length; i++) {
            map.put(String.valueOf(i), new GardenPlot(String.valueOf(i), coords[i][0], coords[i][1]));
        }
        return Collections.unmodifiableMap(map);
    }

    public static class GardenPlot {
        public final String id;
        public final BlockPos center;
        public final Box boundingBox;

        public GardenPlot(String id, int cx, int cz) {
            this.id = id;
            this.center = new BlockPos(cx, 66, cz);
            this.boundingBox = Box.of(this.center.toCenterPos().add(-0.5, 0.5, -0.5), 96, 0, 96);
        }

        public boolean isPlayerAbove() {
            if (mc.player == null) return false;
            Vec3d pos = mc.player.getPos();
            return pos.y > 66 && pos.y < 142
                && pos.x > boundingBox.minX && pos.x < boundingBox.maxX
                && pos.z > boundingBox.minZ && pos.z < boundingBox.maxZ;
        }

        public Vec3d getMiddle() {
            return new Vec3d(center.getX(), 68, center.getZ());
        }

        /** Distance from plot center to player, ignoring Y. */
        public double distanceToPlayer() {
            if (mc.player == null) return Double.MAX_VALUE;
            Vec3d p = mc.player.getPos();
            double dx = center.getX() - p.x;
            double dz = center.getZ() - p.z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }
}
