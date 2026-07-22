package earth.tellus.geoid.world;

import earth.tellus.geoid.math.Geodetic;
import earth.tellus.geoid.math.GravityField;
import earth.tellus.geoid.math.Quat;
import earth.tellus.geoid.math.SphereMath;
import earth.tellus.geoid.math.Vec3;

/**
 * Antipodal core traversal: the physics and coordinate model for digging straight through the planet.
 *
 * <p>A real straight-down dig from a point {@code P} passes through the centre and emerges at the
 * antipode. The full path is one diameter — {@link SphereMath#DIAMETER} ~= 12.74 million blocks — which
 * cannot be laid out 1:1 along Minecraft's ~384-block Y range. The traversal is therefore modelled on
 * two scales at once:
 *
 * <ul>
 *   <li><b>Physics scale (honest):</b> a parameter {@code s in [0, 2R]} measured along the true
 *       diameter. Gravity, weightlessness at the centre, and the geodetic position are all computed
 *       from {@code s} using real numbers.</li>
 *   <li><b>Render scale (compressed):</b> the two thin surface shells (real Tellus terrain, a few
 *       hundred blocks each) are 1:1, while the vast molten interior between them is compressed into a
 *       fixed visual height of "mantle / outer core / inner core" strata. The player still crosses it,
 *       just faster than reality — the same trick every game uses to make a planet-sized interior
 *       traversable.</li>
 * </ul>
 *
 * <p><b>The 180-degree flip.</b> On the near side the centre is below the player, so gravity is -Y
 * (vanilla feel) and digging down goes <em>with</em> gravity. Past the centre the centre is above the
 * player, so gravity becomes +Y. Rather than leave the player upside-down, we slerp their reference
 * frame ({@link #frameAt}) through 180 degrees across the weightless centre: afterwards "down" is
 * re-defined toward the (now overhead) centre, the horizon has rolled over, and the same downward
 * digging <em>feels</em> like climbing up toward the antipodal sky — exactly the intended experience.
 */
public final class CoreTunnel {

    /** Real 1:1 terrain depth kept on each surface shell before compression begins (blocks). */
    public static final double SHELL_DEPTH = 512.0;

    /**
     * Compressed visual height allotted to the entire deep interior, per side (blocks).
     *
     * <p>Sized so the full shaft ({@link #visualShaftHeight()} = {@code 2*SHELL_DEPTH +
     * 2*INTERIOR_VISUAL_HALF}) fits inside the {@code geoid:core} dimension's world-height budget
     * ({@code min_y=-2032, height=4064}, the largest span Minecraft's chunk format allows), with margin
     * to spare on both ends. See {@code GeoidServer} for the actual Y placement inside that dimension.
     */
    public static final double INTERIOR_VISUAL_HALF = 1400.0;

    /** Half-width (in {@code s}) of the centre zone over which the frame flip is slerped. */
    private static final double FLIP_ZONE = 2000.0;

    /** The near-side surface geodetic where this traversal began. */
    public final Geodetic origin;

    public CoreTunnel(Geodetic origin) {
        this.origin = origin.withAltitude(0.0);
    }

    /** The antipodal surface geodetic this traversal emerges at. */
    public Geodetic antipode() {
        return origin.antipode();
    }

    // ------------------------------------------------------------------ geodesy along the diameter

    /**
     * Geodetic position at diameter parameter {@code s in [0, 2R]}.
     * Near half ({@code s <= R}) stays at the origin column, descending; far half rises toward the
     * antipode. Altitude is negative throughout (underground), reaching {@code -R} at the centre.
     */
    public Geodetic geodeticAt(double s) {
        s = SphereMath.clamp(s, 0.0, SphereMath.DIAMETER);
        if (s <= SphereMath.EARTH_RADIUS) {
            return origin.withAltitude(-s); // radius = R - s
        }
        double altFar = s - SphereMath.DIAMETER; // -R at centre, 0 at antipodal surface
        return antipode().withAltitude(altFar);
    }

    /** ECEF position along the diameter — a genuine straight line through the planet centre. */
    public Vec3 ecefAt(double s) {
        Vec3 dir = earth.tellus.geoid.math.Ecef.up(origin); // outward radial at origin
        double r = SphereMath.EARTH_RADIUS - s;             // signed: +R -> -R
        return dir.scale(r);
    }

    // ------------------------------------------------------------------ physics

    /**
     * Gravity acceleration applied along the Minecraft Y axis for this traversal, in blocks/tick^2.
     * Negative = pulled toward -Y, positive = toward +Y. Points toward the centre at all times, so it
     * is negative on the near side, zero at the centre, and positive on the far side.
     */
    public double gravityY(double s) {
        return gravityYForParam(s);
    }

    /**
     * Static form used by client-side prediction, which knows {@code s} from the synced state but not
     * the origin. Gravity along the shaft depends only on the diameter parameter.
     */
    public static double gravityYForParam(double s) {
        double r = SphereMath.EARTH_RADIUS - s; // distance from centre, signed toward near side
        double mag = SphereMath.gravityMagnitude(r);
        double gTicks = GravityField.MC_SURFACE_GRAVITY * (mag / SphereMath.SURFACE_GRAVITY);
        // Toward centre: on the near side (s<R, r>0) centre is below -> -Y; past centre (r<0) -> +Y.
        double sign = r > 0 ? -1.0 : 1.0;
        return sign * gTicks;
    }

    /**
     * The reference-frame orientation at {@code s}: identity on the near side, a full 180-degree roll
     * on the far side, smoothly slerped across the centre. The camera reads this to roll the horizon;
     * the input mapper reads it so "forward"/"down" stay intuitive after the flip.
     */
    public Quat frameAt(double s) {
        return frameForParam(s);
    }

    /** Static form for client-side prediction (depends only on {@code s}). */
    public static Quat frameForParam(double s) {
        double centre = SphereMath.EARTH_RADIUS;
        double t = SphereMath.smoothstep(centre - FLIP_ZONE, centre + FLIP_ZONE, s);
        // Flip about the player's local east axis so the roll is a pitch-over, not a yaw spin.
        Quat flipped = Quat.axisAngle(Vec3.UNIT_X, Math.PI);
        return Quat.slerp(Quat.IDENTITY, flipped, t);
    }

    /** True once the player has crossed the weightless centre. */
    public boolean pastCentre(double s) {
        return s > SphereMath.EARTH_RADIUS;
    }

    /** Traversal progress {@code [0,1]}: 0 near surface, 0.5 centre, 1 antipodal surface. */
    public double progress(double s) {
        return SphereMath.clamp(s / SphereMath.DIAMETER, 0.0, 1.0);
    }

    // ------------------------------------------------------------------ render-scale mapping

    /**
     * Maps a diameter parameter {@code s} to a signed "visual depth" in blocks for the Core dimension.
     * The two surface shells are 1:1; everything between is linearly compressed into
     * {@link #INTERIOR_VISUAL_HALF} per side. Monotonic in {@code s}, so it is invertible for the
     * inverse (block-dig -> s) mapping.
     */
    public double sToVisualDepth(double s) {
        double R = SphereMath.EARTH_RADIUS;
        if (s <= SHELL_DEPTH) {
            return s; // near shell, 1:1
        }
        if (s >= SphereMath.DIAMETER - SHELL_DEPTH) {
            double fromFarSurface = SphereMath.DIAMETER - s; // 0..SHELL_DEPTH
            double farShellTop = SHELL_DEPTH + 2.0 * INTERIOR_VISUAL_HALF;
            return farShellTop + (SHELL_DEPTH - fromFarSurface); // 1:1 far shell
        }
        // Interior: compress [SHELL_DEPTH, DIAMETER-SHELL_DEPTH] into 2*INTERIOR_VISUAL_HALF.
        double interiorSpan = SphereMath.DIAMETER - 2.0 * SHELL_DEPTH;
        double frac = (s - SHELL_DEPTH) / interiorSpan;
        return SHELL_DEPTH + frac * (2.0 * INTERIOR_VISUAL_HALF);
    }

    /** Inverse of {@link #sToVisualDepth}: visual depth in the Core dimension -> true diameter {@code s}. */
    public double visualDepthToS(double depth) {
        double farShellTop = SHELL_DEPTH + 2.0 * INTERIOR_VISUAL_HALF;
        if (depth <= SHELL_DEPTH) {
            return depth;
        }
        if (depth >= farShellTop) {
            double intoFarShell = depth - farShellTop; // 0..SHELL_DEPTH
            return SphereMath.DIAMETER - (SHELL_DEPTH - intoFarShell);
        }
        double interiorSpan = SphereMath.DIAMETER - 2.0 * SHELL_DEPTH;
        double frac = (depth - SHELL_DEPTH) / (2.0 * INTERIOR_VISUAL_HALF);
        return SHELL_DEPTH + frac * interiorSpan;
    }

    /** Total visual height of the Core dimension shaft in blocks. */
    public static double visualShaftHeight() {
        return 2.0 * SHELL_DEPTH + 2.0 * INTERIOR_VISUAL_HALF;
    }
}
