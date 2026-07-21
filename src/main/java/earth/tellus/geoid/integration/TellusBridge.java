package earth.tellus.geoid.integration;

import earth.tellus.geoid.math.GeoProjection;
import earth.tellus.geoid.math.Geodetic;

/**
 * Adapter between the Geoid spherical engine and the Tellus terrain mod.
 *
 * <p>Geoid does <em>not</em> generate terrain — Tellus does. What Geoid needs from Tellus is agreement
 * on the single most important fact: <b>which geodetic point does each Minecraft block correspond to</b>.
 * If the two mods disagree on the projection, folds and antipode teleports would land on the wrong
 * terrain. So the whole coupling is reduced to this small interface:
 *
 * <ul>
 *   <li>{@link #projection()} — the exact chart Tellus laid its blocks on. Geoid derives all folding,
 *       wrapping and antipode math from it.</li>
 *   <li>{@link #surfaceAltitude} — Tellus's terrain height at a geodetic point, so the antipode
 *       pre-loader and the core-exit placement know where the ground is before the chunk is real.</li>
 * </ul>
 *
 * <p>The concrete binding is discovered at runtime (see {@code TellusBridges}); when Tellus is absent
 * the engine still runs against {@link FallbackTellusBridge} using a plain equirectangular map, which
 * keeps the mod useful for testing and for non-Tellus worlds.
 */
public interface TellusBridge {

    /** The projection Tellus used to place its terrain. Source of truth for all coordinate transforms. */
    GeoProjection projection();

    /**
     * Terrain surface altitude (blocks above the reference sphere) at a geodetic point, if Tellus can
     * answer cheaply/without generating. Return {@link Double#NaN} if unknown; callers then fall back to
     * the sea-level default until the chunk actually generates.
     */
    double surfaceAltitude(Geodetic g);

    /** Whether the real Tellus mod is present and wired up (false for the fallback). */
    boolean isTellusPresent();

    /** Sea-level Minecraft Y that altitude 0 maps to. Tellus and Geoid must share this value. */
    double seaLevelY();
}
