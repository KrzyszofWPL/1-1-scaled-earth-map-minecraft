package earth.tellus.geoid.integration;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Detects whether the Immersive Portals mod is installed alongside Geoid.
 *
 * <p>Unlike {@link TellusBridges}, this integration needs no reflective binding to any of the other
 * mod's classes: Geoid never calls Immersive Portals' API directly. Instead, the server operator sets
 * up a real, see-through wrap portal at the antimeridian seam themselves, in game, via Immersive
 * Portals' own command (see the README) — {@code /portal global create_outward_wrapping <p1> <p2>}
 * with corners at the seam ({@code +-wrapPeriodX()/2} in X, the pole Z-limits in Z). Once that portal
 * exists, it physically relocates the player when they walk through it, so {@link
 * earth.tellus.geoid.world.GeoidServer} must stop teleporting them itself for that seam (that would
 * double-move them) and instead just keep {@code windowOffsetX} bookkeeping in sync by detecting the
 * jump after the fact (see {@code WorldFolding#reconcileExternalFold}).
 *
 * <p>Longitude wrap is handed off to Immersive Portals when present; the pole seam (an unusual
 * reflect-and-flip, not a plain loop, so it doesn't fit Immersive Portals' generic wrapping-zone
 * feature) keeps using Geoid's own invisible teleport regardless.
 */
public final class ImmersivePortalsSupport {

    private static final String MOD_ID = "immersive_portals";

    /** True if the Immersive Portals mod is loaded this session. Checked once; mods don't come and go. */
    public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded(MOD_ID);

    private ImmersivePortalsSupport() {
    }
}
