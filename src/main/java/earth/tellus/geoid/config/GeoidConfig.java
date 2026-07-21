package earth.tellus.geoid.config;

/**
 * Engine configuration. Kept as a tiny POJO with sane defaults so it can be loaded from JSON, set from
 * a data pack, or synced server -> client without dragging in a config framework.
 *
 * <p>All spatial values are in blocks; all toggles default to "on" so a fresh install behaves as a
 * spherical planet immediately.
 */
public final class GeoidConfig {

    private static volatile GeoidConfig instance = new GeoidConfig();

    /** Minecraft Y that geodetic altitude 0 (reference sphere surface) maps to. Must match Tellus. */
    public double seaLevelY = 64.0;

    /** Enable seamless east-west/pole circumnavigation folding. */
    public boolean enableCircumnavigation = true;

    /** Enable spherical gravity + antipodal core traversal. */
    public boolean enableSphericalGravity = true;

    /** Chunks of overlap pre-generated ahead of the antimeridian seam. */
    public int seamOverlapChunks = 12;

    /** Radius (chunks) of the disc pre-loaded at the antipode before a core exit. */
    public int antipodePreloadRadius = 10;

    /** Max chunk tickets the antipode loader may issue per server tick (TPS guard). */
    public int chunkBudgetPerTick = 8;

    /** Depth below the local surface at which straight-down digging enters a core traversal. */
    public double coreEntryDepth = 480.0;

    /** If false, past-centre gravity pulls back to the centre (realistic); if true, it re-anchors "down"
     *  toward the antipodal surface so players fall out the far side. Defaults to the realistic model. */
    public boolean coreExitAssist = false;

    public static GeoidConfig get() {
        return instance;
    }

    public static void set(GeoidConfig cfg) {
        instance = cfg;
    }
}
