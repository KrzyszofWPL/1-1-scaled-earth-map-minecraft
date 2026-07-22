package earth.tellus.geoid.config;

/**
 * Engine configuration. Kept as a tiny POJO with sane defaults so it can be loaded from JSON, set from
 * a data pack, or synced server -> client without dragging in a config framework.
 *
 * <p>All spatial values are in blocks. Spherical gravity and the core traversal default to "on" so a
 * fresh install behaves as a spherical planet immediately; Geoid's own teleport-based circumnavigation
 * folding defaults to "off" since Immersive Portals is expected to own world-edge wrapping instead (see
 * {@link #enableCircumnavigation}).
 */
public final class GeoidConfig {

    private static volatile GeoidConfig instance = new GeoidConfig();

    /** Minecraft Y that geodetic altitude 0 (reference sphere surface) maps to. Must match Tellus. */
    public double seaLevelY = 64.0;

    /** Enable Geoid's own seamless east-west/pole circumnavigation folding (teleport-based). Off by
     *  default: world-edge wrapping is expected to be handled by Immersive Portals' wrap portals
     *  instead (see README), so Geoid doesn't also teleport the player and risk double-handling the
     *  seam. Turn this back on (`/geoid circumnavigation true`) only on servers that don't run
     *  Immersive Portals and still want the invisible-teleport fallback. Spherical gravity and the core
     *  traversal ({@link #enableSphericalGravity}) are independent of this flag and stay on. */
    public boolean enableCircumnavigation = false;

    /** Enable spherical gravity + antipodal core traversal. */
    public boolean enableSphericalGravity = true;

    /** Chunks of overlap pre-generated ahead of the antimeridian seam. */
    public int seamOverlapChunks = 12;

    /** Radius (chunks) of the disc pre-loaded at the antipode before a core exit. */
    public int antipodePreloadRadius = 10;

    /** Max chunk tickets the antipode loader may issue per server tick (TPS guard). */
    public int chunkBudgetPerTick = 8;

    /**
     * Trigger depth for a core traversal, in blocks below {@link #seaLevelY} (<em>not</em> below
     * wherever the player started digging — the check is {@code mcY <= seaLevelY - coreEntryDepth}).
     *
     * <p>Must stay well under how far a player can actually dig below sea level in whatever dimension
     * they're standing in, or the core traversal can never trigger at all. A stock 1.21.1 Overworld
     * (`min_y=-64`) only has 128 blocks below the default sea level of 64 before hitting the bedrock
     * floor — nowhere near Earth's real radius, hence the whole point of this mod. 100 leaves margin
     * above the randomized bedrock layer (~Y -59..-64) so the switch into {@code geoid:core} always
     * lands on solid, breakable rock. Raise this only on servers whose Overworld dimension type has
     * been extended deeper than vanilla.
     */
    public double coreEntryDepth = 100.0;

    /** If false, past-centre gravity pulls back to the centre (realistic); if true, it re-anchors "down"
     *  toward the antipodal surface so players fall out the far side. Defaults to the realistic model. */
    public boolean coreExitAssist = false;

    /** Debug aid: play a sound/particle burst and show an action-bar message on every longitude/pole
     *  fold, so a seam crossing is perceptible instead of perfectly invisible. Off by default — the
     *  whole point of folding is that it normally isn't felt. */
    public boolean debugFoldFeedback = false;

    public static GeoidConfig get() {
        return instance;
    }

    public static void set(GeoidConfig cfg) {
        instance = cfg;
    }
}
