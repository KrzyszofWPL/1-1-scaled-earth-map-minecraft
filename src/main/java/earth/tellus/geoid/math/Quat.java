package earth.tellus.geoid.math;

/**
 * Minimal unit-quaternion for rotating reference frames.
 *
 * <p>Used by the physics layer to rotate the gravity vector smoothly. The canonical example is the
 * antipodal core traversal: when a player passes through the planet centre the local "down" must
 * rotate exactly 180 degrees. Doing that as an Euler-angle interpolation gimbal-locks and snaps;
 * doing it as a quaternion {@link #slerp} produces the smooth, continuous 180-degree flip the design
 * requires.
 */
public final class Quat {

    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    public final double w;
    public final double x;
    public final double y;
    public final double z;

    public Quat(double w, double x, double y, double z) {
        this.w = w;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Rotation of {@code angle} radians about the given (need not be unit) axis. */
    public static Quat axisAngle(Vec3 axis, double angleRad) {
        Vec3 n = axis.normalize();
        if (n.lengthSq() == 0.0) {
            return IDENTITY;
        }
        double half = angleRad * 0.5;
        double s = Math.sin(half);
        return new Quat(Math.cos(half), n.x * s, n.y * s, n.z * s);
    }

    /**
     * Shortest-arc rotation taking unit vector {@code from} to unit vector {@code to}.
     * Handles the antipodal ({@code from == -to}) case by picking an arbitrary perpendicular axis.
     */
    public static Quat between(Vec3 from, Vec3 to) {
        Vec3 f = from.normalize();
        Vec3 t = to.normalize();
        double d = f.dot(t);
        if (d >= 1.0 - 1.0e-9) {
            return IDENTITY;
        }
        if (d <= -1.0 + 1.0e-9) {
            // Opposite vectors: rotate 180 deg about any axis perpendicular to f.
            Vec3 axis = Math.abs(f.x) < 0.9 ? f.cross(Vec3.UNIT_X) : f.cross(Vec3.UNIT_Y);
            return axisAngle(axis, Math.PI);
        }
        Vec3 axis = f.cross(t);
        double w = 1.0 + d;
        return new Quat(w, axis.x, axis.y, axis.z).normalize();
    }

    public double length() {
        return Math.sqrt(w * w + x * x + y * y + z * z);
    }

    public Quat normalize() {
        double len = length();
        if (len < 1.0e-12) {
            return IDENTITY;
        }
        double inv = 1.0 / len;
        return new Quat(w * inv, x * inv, y * inv, z * inv);
    }

    public Quat conjugate() {
        return new Quat(w, -x, -y, -z);
    }

    /** Hamilton product {@code this * o}. */
    public Quat mul(Quat o) {
        return new Quat(
                w * o.w - x * o.x - y * o.y - z * o.z,
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w);
    }

    /** Rotates a vector by this (assumed unit) quaternion: {@code q * v * q^-1}. */
    public Vec3 rotate(Vec3 v) {
        // t = 2 * (q_xyz x v); v' = v + w*t + q_xyz x t   (Rodrigues via quaternion)
        Vec3 u = new Vec3(x, y, z);
        Vec3 t = u.cross(v).scale(2.0);
        return v.add(t.scale(w)).add(u.cross(t));
    }

    /** Spherical linear interpolation, {@code alpha in [0,1]}. */
    public static Quat slerp(Quat a, Quat b, double alpha) {
        a = a.normalize();
        b = b.normalize();
        double cos = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z;
        if (cos < 0.0) {
            // Take the shorter path.
            b = new Quat(-b.w, -b.x, -b.y, -b.z);
            cos = -cos;
        }
        if (cos > 0.9995) {
            // Nearly identical: fall back to normalized lerp to avoid division by ~0.
            return new Quat(
                    a.w + (b.w - a.w) * alpha,
                    a.x + (b.x - a.x) * alpha,
                    a.y + (b.y - a.y) * alpha,
                    a.z + (b.z - a.z) * alpha).normalize();
        }
        double theta0 = Math.acos(cos);
        double theta = theta0 * alpha;
        double sin0 = Math.sin(theta0);
        double s0 = Math.sin(theta0 - theta) / sin0;
        double s1 = Math.sin(theta) / sin0;
        return new Quat(
                a.w * s0 + b.w * s1,
                a.x * s0 + b.x * s1,
                a.y * s0 + b.y * s1,
                a.z * s0 + b.z * s1);
    }

    @Override
    public String toString() {
        return String.format("Quat(w=%.5f, x=%.5f, y=%.5f, z=%.5f)", w, x, y, z);
    }
}
