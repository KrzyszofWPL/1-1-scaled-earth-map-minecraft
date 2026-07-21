package earth.tellus.geoid.math;

/**
 * Immutable double-precision 3-vector.
 *
 * <p>Deliberately independent of Minecraft's {@code net.minecraft.util.math.Vec3d}: the spherical
 * engine performs planetary-scale arithmetic (radii of ~6.37e6 blocks) where the accumulated error
 * of Minecraft's mixed float/double pipeline is unacceptable. Everything here stays in {@code double}
 * and only crosses into Minecraft types at the very last moment (see {@code MinecraftBridge}).
 *
 * <p>Axis convention used throughout the engine for <em>local</em> (Minecraft) space:
 * <ul>
 *   <li>+X = geographic East</li>
 *   <li>+Y = local Up (away from the planet centre)</li>
 *   <li>+Z = geographic South  (so North = -Z, matching vanilla Minecraft)</li>
 * </ul>
 * ECEF (Earth-Centred Earth-Fixed) space uses its own axes; see {@link Ecef}.
 */
public final class Vec3 {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 UNIT_X = new Vec3(1, 0, 0);
    public static final Vec3 UNIT_Y = new Vec3(0, 1, 0);
    public static final Vec3 UNIT_Z = new Vec3(0, 0, 1);

    public final double x;
    public final double y;
    public final double z;

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    /** Component-wise linear interpolation, {@code t in [0,1]}. */
    public Vec3 lerp(Vec3 o, double t) {
        return new Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public double lengthSq() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSq());
    }

    /** Returns the unit vector; {@link #ZERO} maps to {@link #ZERO} (no NaN blow-up). */
    public Vec3 normalize() {
        double len = length();
        if (len < 1.0e-12) {
            return ZERO;
        }
        double inv = 1.0 / len;
        return new Vec3(x * inv, y * inv, z * inv);
    }

    public double distanceTo(Vec3 o) {
        return sub(o).length();
    }

    /** Reflects this vector about a unit normal {@code n}. */
    public Vec3 reflect(Vec3 n) {
        return sub(n.scale(2.0 * dot(n)));
    }

    /** Projection of this vector onto the plane whose unit normal is {@code n}. */
    public Vec3 projectOntoPlane(Vec3 n) {
        return sub(n.scale(dot(n)));
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    @Override
    public String toString() {
        return String.format("Vec3(%.6f, %.6f, %.6f)", x, y, z);
    }
}
