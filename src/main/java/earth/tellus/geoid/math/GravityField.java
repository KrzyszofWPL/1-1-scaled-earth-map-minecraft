package earth.tellus.geoid.math;

/**
 * The spherical gravity field.
 *
 * <p>Vanilla Minecraft gravity is a hard-coded scalar applied to {@code velocity.y}. This class
 * replaces the <em>direction</em> as well as the magnitude: gravity always points toward the planet
 * centre in ECEF, and its strength follows the uniform-sphere model
 * ({@link SphereMath#gravityMagnitude}). Two behaviours fall straight out of that single rule:
 *
 * <ol>
 *   <li><b>Surface play</b> feels exactly like vanilla — locally, "toward the centre" is "-Y".</li>
 *   <li><b>Core traversal</b> gets its signature 180-degree flip for free: as the player passes the
 *       centre, the outward radial reverses relative to their direction of travel, so the gravity
 *       vector rotates smoothly through zero (at the centre) and points "back the way they came",
 *       i.e. the new local "down".</li>
 * </ol>
 */
public final class GravityField {

    /** Vanilla per-tick downward acceleration for a player, in blocks/tick^2. Our reference at r=R. */
    public static final double MC_SURFACE_GRAVITY = 0.08;

    private GravityField() {
    }

    /**
     * Gravitational acceleration vector at an ECEF position, in blocks/tick^2, expressed in ECEF.
     * Points toward the centre; magnitude scaled from real g to Minecraft's tick-based gravity.
     */
    public static Vec3 accelerationEcef(Vec3 posEcef) {
        double r = posEcef.length();
        if (r < 1.0e-3) {
            return Vec3.ZERO; // exact centre: weightless
        }
        double gReal = SphereMath.gravityMagnitude(r);
        double gTicks = MC_SURFACE_GRAVITY * (gReal / SphereMath.SURFACE_GRAVITY);
        // Inward radial = -posEcef/|posEcef|.
        return posEcef.scale(-gTicks / r);
    }

    /**
     * Gravitational acceleration in the player's <em>local</em> frame at geodetic {@code g}.
     * On/above the surface this is {@code (0, -g, 0)} — pure "-Y down", identical to vanilla feel.
     * For {@code altitude < 0} the magnitude follows the interior model; the sign of the Up component
     * encodes which side of the centre the player is on.
     */
    public static Vec3 accelerationLocal(Geodetic g) {
        double r = SphereMath.EARTH_RADIUS + g.altitude; // may be negative past the centre
        double gReal = SphereMath.gravityMagnitude(r);
        double gTicks = MC_SURFACE_GRAVITY * (gReal / SphereMath.SURFACE_GRAVITY);
        // Local Up is +Y. Gravity is inward: -sign(r) * Up. Past the centre r<0 flips the sign,
        // which is the 180-degree flip expressed in local coordinates.
        double sign = r >= 0 ? 1.0 : -1.0;
        return new Vec3(0.0, -sign * gTicks, 0.0);
    }
}
