package earth.tellus.geoid.world;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import earth.tellus.geoid.GeoidMod;
import earth.tellus.geoid.chunk.AntipodeChunkLoader;
import earth.tellus.geoid.chunk.AntipodeChunkService;
import earth.tellus.geoid.config.GeoidConfig;
import earth.tellus.geoid.integration.ImmersivePortalsSupport;
import earth.tellus.geoid.integration.TellusBridge;
import earth.tellus.geoid.integration.TellusBridges;
import earth.tellus.geoid.math.Geodetic;
import earth.tellus.geoid.math.SphereMath;
import earth.tellus.geoid.math.Vec3;
import earth.tellus.geoid.net.GeoStatePayload;
import earth.tellus.geoid.physics.SphericalPhysics;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * Server-side orchestration: one place that turns the pure spherical logic into actual moves on the
 * {@link ServerPlayerEntity}s each tick.
 *
 * <p>Per player it owns the authoritative {@link PlayerGeoState} and, when relevant, an active
 * {@link CoreTunnel}. Per world it owns a {@link WorldFolding} (window offset is world state) and an
 * {@link AntipodeChunkLoader}. The tick pipeline is:
 *
 * <ol>
 *   <li>Sync geodetic from the player's Minecraft position (surface phase).</li>
 *   <li>Run folding; if a fold fires, teleport the player and pre-load the seam bridge.</li>
 *   <li>Detect / advance a core traversal; pre-load the antipode after the centre; place the player on
 *       the antipodal surface on exit.</li>
 *   <li>Push a {@link GeoStatePayload} to the client so its physics and camera stay in lock-step.</li>
 * </ol>
 *
 * <p>MC-facing calls are isolated to a handful of well-marked spots so the class can be ported across
 * versions by touching only those.
 */
public final class GeoidServer {

    private static final GeoidServer INSTANCE = new GeoidServer();

    /** The compressed-interior dimension a core traversal moves the player through (see data/geoid). */
    private static final RegistryKey<World> CORE_WORLD_KEY =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("geoid", "core"));

    /**
     * Y inside {@code geoid:core} where a traversal begins: the player stands on the freshly exposed
     * rock face (solid block at Y-1, air at Y) with a full {@link CoreTunnel#visualShaftHeight()} of
     * headroom below down to the dimension floor, well inside its {@code min_y=-2032} floor.
     */
    private static final double CORE_DIM_ENTRY_Y = 1500.0;

    public static GeoidServer get() {
        return INSTANCE;
    }

    private final Map<UUID, PlayerGeoState> states = new ConcurrentHashMap<>();
    private final Map<UUID, CoreTunnel> traversals = new ConcurrentHashMap<>();
    private final Map<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, WorldFolding> folds =
            new ConcurrentHashMap<>();
    private final Map<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, AntipodeChunkLoader> loaders =
            new ConcurrentHashMap<>();

    /** So a missing {@code geoid:core} dimension logs once, not once per tick a player is digging down. */
    private volatile boolean warnedCoreDimensionMissing = false;

    private GeoidServer() {
    }

    private WorldFolding foldingFor(ServerWorld world) {
        TellusBridge bridge = TellusBridges.active();
        return folds.computeIfAbsent(world.getRegistryKey(), k ->
                new WorldFolding(bridge.projection(), bridge.seaLevelY(), GeoidConfig.get().seamOverlapChunks));
    }

    private AntipodeChunkLoader loaderFor(ServerWorld world) {
        return loaders.computeIfAbsent(world.getRegistryKey(), k ->
                new AntipodeChunkLoader(new AntipodeChunkService(world), GeoidConfig.get().chunkBudgetPerTick));
    }

    private PlayerGeoState stateFor(ServerPlayerEntity player, WorldFolding folding) {
        return states.computeIfAbsent(player.getUuid(), id -> {
            Geodetic g = folding.mcToGeodetic(player.getX(), player.getY(), player.getZ());
            return new PlayerGeoState(g);
        });
    }

    /**
     * Called once per player per server tick (from a Fabric ServerTickEvents hook).
     *
     * <p>Folding and the antipode loader are always keyed to the Overworld, never to whichever world the
     * player currently occupies: during a core traversal the player physically stands in the compressed
     * {@code geoid:core} dimension, but the geodetic <-> Minecraft mapping only ever means something for
     * the Overworld that Tellus actually paints terrain on.
     */
    public void tickPlayer(ServerPlayerEntity player) {
        GeoidConfig cfg = GeoidConfig.get();
        ServerWorld overworld = player.getServer().getOverworld();
        WorldFolding folding = foldingFor(overworld);
        AntipodeChunkLoader loader = loaderFor(overworld);
        PlayerGeoState state = stateFor(player, folding);

        state.bearingRad = Math.toRadians(player.getYaw()); // yaw is clockwise-from-south; adjust in mapper

        if (state.phase == PlayerGeoState.Phase.SURFACE) {
            tickSurface(player, folding, loader, state, cfg);
        } else {
            tickCore(player, folding, loader, state, cfg);
        }

        // Keep the loader draining its budget regardless of phase.
        loader.tick();

        ServerPlayNetworking.send(player, GeoStatePayload.from(state));
    }

    // ------------------------------------------------------------------ surface / circumnavigation

    private void tickSurface(ServerPlayerEntity player, WorldFolding folding, AntipodeChunkLoader loader,
                             PlayerGeoState state, GeoidConfig cfg) {
        // 1. Reconcile geodetic from where Minecraft actually placed the player.
        state.geodetic = folding.mcToGeodetic(player.getX(), player.getY(), player.getZ());

        if (cfg.enableCircumnavigation) {
            if (ImmersivePortalsSupport.PRESENT) {
                // A real, see-through Immersive Portals wrap portal (set up by the server operator via
                // /portal global create_outward_wrapping — see the README) already relocated the player
                // physically if they just crossed the seam; teleporting them again here would double-move
                // them. Just detect that jump and keep windowOffsetX in sync with it.
                if (!Double.isNaN(state.lastMcX) && folding.reconcileExternalFold(state.lastMcX, player.getX())) {
                    state.geodetic = folding.mcToGeodetic(player.getX(), player.getY(), player.getZ());
                    state.foldEpoch++; // still suppress client interpolation across the jump
                }
            } else {
                // 2. Pre-generate the seam bridge if we are approaching the antimeridian.
                double bridgeMapX = folding.seamBridgeTargets(player.getX());
                if (!Double.isNaN(bridgeMapX)) {
                    loader.requestArea(bridgeMapX, player.getZ(), cfg.seamOverlapChunks);
                }

                // 3. Fold longitude, teleporting seamlessly if needed.
                WorldFolding.FoldResult lon = folding.foldLongitude(player.getX(), player.getZ());
                if (lon.teleported) {
                    applyFold(player, state, lon.newMinecraftX, player.getY(), lon.newMinecraftZ, lon.bearingDelta);
                }
            }

            // Pole seam is a reflect-and-flip, not a plain loop, so it doesn't fit Immersive Portals'
            // generic wrapping-zone feature — always Geoid's own invisible teleport, regardless of (3).
            WorldFolding.FoldResult pole = folding.foldPole(player.getX(), player.getZ());
            if (pole.teleported) {
                applyFold(player, state, pole.newMinecraftX, player.getY(), pole.newMinecraftZ, pole.bearingDelta);
            }
        }
        state.lastMcX = player.getX();

        // 4. Core-entry detection: sustained straight-down digging past coreEntryDepth blocks below sea
        //    level (not below wherever the player started digging — see GeoidConfig#coreEntryDepth).
        if (cfg.enableSphericalGravity && state.geodetic.altitude <= -cfg.coreEntryDepth && isDiggingDown(player)) {
            beginCoreTraversal(player, folding, state, cfg);
        }
    }

    /** Teleports the player as part of a fold and records it so the client can suppress interpolation. */
    private void applyFold(ServerPlayerEntity player, PlayerGeoState state,
                           double x, double y, double z, double bearingDelta) {
        Vec3 vel = velocityOf(player);
        // MC-facing: move without a loading screen, preserving look & velocity for seamlessness.
        player.networkHandler.requestTeleport(x, y, z, player.getYaw(), player.getPitch());
        setVelocity(player, vel); // keep momentum across the seam
        state.bearingRad += bearingDelta;
        state.foldEpoch++;

        if (GeoidConfig.get().debugFoldFeedback) {
            playFoldFeedback(player);
        }
    }

    /**
     * Debug-only perceptual cue for a seam crossing (see {@link GeoidConfig#debugFoldFeedback}). The
     * fold itself is designed to be unnoticeable — this exists purely so a player/dev who turns the
     * flag on via {@code /geoid debugFold true} can confirm a wrap actually fired.
     */
    private static void playFoldFeedback(ServerPlayerEntity player) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_PORTAL_TRAVEL, SoundCategory.PLAYERS, 0.4f, 1.6f);
        world.spawnParticles(ParticleTypes.PORTAL,
                player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.5, 1.0, 0.5, 0.05);
        player.sendMessage(Text.literal("~ world seam crossed ~").formatted(Formatting.GRAY, Formatting.ITALIC), true);
    }

    // ------------------------------------------------------------------ core traversal / antipode

    private void beginCoreTraversal(ServerPlayerEntity player, WorldFolding folding,
                                    PlayerGeoState state, GeoidConfig cfg) {
        // Validate before mutating any state: if this bails out, the player must stay in SURFACE phase
        // so the entry check simply retries next tick, instead of getting stranded in CORE_DESCENT with
        // no dimension to actually place them in and no way back (tickCore only resets to SURFACE when
        // its CoreTunnel lookup misses, which never happens once one has been stored below).
        ServerWorld coreWorld = player.getServer().getWorld(CORE_WORLD_KEY);
        if (coreWorld == null) {
            if (!warnedCoreDimensionMissing) {
                warnedCoreDimensionMissing = true;
                GeoidMod.LOG.warn("Dimension '{}' is not loaded (missing datapack?); core traversal will "
                        + "not start until it is.", CORE_WORLD_KEY.getValue());
            }
            return;
        }

        Geodetic origin = state.geodetic.withAltitude(0.0);
        CoreTunnel tunnel = new CoreTunnel(origin);
        traversals.put(player.getUuid(), tunnel);
        state.phase = PlayerGeoState.Phase.CORE_DESCENT;
        state.coreParam = -state.geodetic.altitude; // depth already dug becomes initial s
        state.geodetic = tunnel.geodeticAt(state.coreParam);
        state.frame = tunnel.frameAt(state.coreParam);
        state.foldEpoch++; // suppress client interpolation across the dimension jump

        // Each traversal gets its own column, keyed off the real-world entry point so two players
        // digging in from different places on Earth never collide underground.
        double shaftX = Math.floor(origin.lonDeg() * 1000.0);
        double shaftZ = Math.floor(origin.latDeg() * 1000.0);
        double y = coreDimensionY(tunnel, state.coreParam, cfg.coreEntryDepth);
        teleportCrossDimension(player, coreWorld, shaftX, y, shaftZ, player.getYaw(), player.getPitch());
    }

    private void tickCore(ServerPlayerEntity player, WorldFolding folding, AntipodeChunkLoader loader,
                          PlayerGeoState state, GeoidConfig cfg) {
        CoreTunnel tunnel = traversals.get(player.getUuid());
        if (tunnel == null) {
            state.phase = PlayerGeoState.Phase.SURFACE;
            return;
        }

        double prevParam = state.coreParam;
        SphericalPhysics.advanceTraversal(state, tunnel, player.getY(), CORE_DIM_ENTRY_Y, cfg.coreEntryDepth);

        // Pre-load the antipodal surface as soon as we pass the centre, so the exit never shows a hole.
        boolean crossedCentre = prevParam <= SphereMath.EARTH_RADIUS && state.coreParam > SphereMath.EARTH_RADIUS;
        if (crossedCentre) {
            Geodetic anti = tunnel.antipode();
            Vec3 mc = folding.geodeticToMc(anti);
            loader.requestArea(mc.x, mc.z, cfg.antipodePreloadRadius);
        }

        ServerWorld overworld = player.getServer().getOverworld();

        // Retreat: climbed back up past the entry threshold without reaching the centre -> pop back to
        // the real shaft they dug, instead of leaving them stranded in the compressed dimension.
        if (!tunnel.pastCentre(prevParam) && state.coreParam < cfg.coreEntryDepth - 4.0) {
            exitToOverworld(player, folding, state, tunnel.origin.withAltitude(-cfg.coreEntryDepth), overworld);
            return;
        }

        // Exit: within one shell's depth of the antipodal surface. Re-anchor onto real antipode terrain
        // (already generated by the antipode preloader above) rather than modelling the last stretch of
        // 1:1 digging inside the compressed dimension, where there is no real terrain to land safely on.
        if (state.coreParam >= SphereMath.DIAMETER - cfg.coreEntryDepth) {
            Geodetic landing = realAntipodalSurface(overworld, folding, tunnel.antipode());
            exitToOverworld(player, folding, state, landing, overworld);
        }
    }

    /**
     * The antipode's real ground level, read from the Overworld's own heightmap rather than assumed to
     * be sea level. The antipode preloader already forced full generation of this column when the
     * traversal crossed the centre, so the heightmap is real Tellus terrain, not a guess — without this
     * a player could pop out entombed in a mountain (real ground above sea level) or stranded high over
     * an ocean floor (real ground below it).
     */
    private static Geodetic realAntipodalSurface(ServerWorld overworld, WorldFolding folding, Geodetic anti) {
        Vec3 mapPos = folding.geodeticToMc(anti.withAltitude(0.0));
        int blockX = (int) Math.floor(mapPos.x);
        int blockZ = (int) Math.floor(mapPos.z);
        int topY = overworld.getTopY(Heightmap.Type.WORLD_SURFACE, blockX, blockZ);
        double altitude = topY - TellusBridges.active().seaLevelY();
        return anti.withAltitude(altitude);
    }

    private void exitToOverworld(ServerPlayerEntity player, WorldFolding folding, PlayerGeoState state,
                                 Geodetic landingSpot, ServerWorld overworld) {
        Vec3 mc = folding.geodeticToMc(landingSpot);
        teleportCrossDimension(player, overworld, mc.x, mc.y, mc.z, player.getYaw(), player.getPitch());
        state.geodetic = landingSpot;
        state.phase = PlayerGeoState.Phase.SURFACE;
        state.coreParam = 0;
        state.frame = earth.tellus.geoid.math.Quat.IDENTITY;
        state.foldEpoch++;
        traversals.remove(player.getUuid());
    }

    /** Maps a traversal parameter to the player's Y inside {@code geoid:core} (see {@link #CORE_DIM_ENTRY_Y}). */
    private static double coreDimensionY(CoreTunnel tunnel, double coreParam, double coreEntryDepth) {
        double visualAtEntry = tunnel.sToVisualDepth(coreEntryDepth);
        double visualNow = tunnel.sToVisualDepth(coreParam);
        return CORE_DIM_ENTRY_Y - (visualNow - visualAtEntry);
    }

    public PlayerGeoState peekState(UUID id) {
        return states.get(id);
    }

    public CoreTunnel traversalOf(UUID id) {
        return traversals.get(id);
    }

    public void forget(UUID id) {
        states.remove(id);
        traversals.remove(id);
    }

    // ------------------------------------------------------------------ MC-facing shims

    /**
     * Moves a player to an absolute position, possibly in a different dimension.
     *
     * <p>Version-sensitive: targets {@code ServerPlayerEntity#teleport(ServerWorld, double, double,
     * double, float, float)} (confirmed against the 1.21.1 Yarn mappings — the flag-set/etc. overload
     * used on 1.21.4+ doesn't exist yet here).
     */
    private static void teleportCrossDimension(ServerPlayerEntity player, ServerWorld target,
                                               double x, double y, double z, float yaw, float pitch) {
        player.teleport(target, x, y, z, yaw, pitch);
    }

    private static boolean isDiggingDown(ServerPlayerEntity player) {
        // Heuristic: moving downward and looking steeply down. Refine with a block-break hook if desired.
        return (player.getY() - player.prevY) < -0.05 && player.getPitch() > 45.0f;
    }

    private static Vec3 velocityOf(ServerPlayerEntity player) {
        net.minecraft.util.math.Vec3d v = player.getVelocity();
        return new Vec3(v.x, v.y, v.z);
    }

    private static void setVelocity(ServerPlayerEntity player, Vec3 v) {
        player.setVelocity(v.x, v.y, v.z);
        player.velocityDirty = true;
    }
}
