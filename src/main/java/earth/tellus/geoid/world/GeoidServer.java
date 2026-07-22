package earth.tellus.geoid.world;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import earth.tellus.geoid.chunk.AntipodeChunkLoader;
import earth.tellus.geoid.chunk.AntipodeChunkService;
import earth.tellus.geoid.config.GeoidConfig;
import earth.tellus.geoid.integration.TellusBridge;
import earth.tellus.geoid.integration.TellusBridges;
import earth.tellus.geoid.math.Geodetic;
import earth.tellus.geoid.math.Vec3;
import earth.tellus.geoid.net.GeoStatePayload;
import earth.tellus.geoid.physics.SphericalPhysics;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

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

    public static GeoidServer get() {
        return INSTANCE;
    }

    private final Map<UUID, PlayerGeoState> states = new ConcurrentHashMap<>();
    private final Map<UUID, CoreTunnel> traversals = new ConcurrentHashMap<>();
    private final Map<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, WorldFolding> folds =
            new ConcurrentHashMap<>();
    private final Map<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, AntipodeChunkLoader> loaders =
            new ConcurrentHashMap<>();

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

    /** Called once per player per server tick (from a Fabric ServerTickEvents hook). */
    public void tickPlayer(ServerPlayerEntity player) {
        GeoidConfig cfg = GeoidConfig.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        WorldFolding folding = foldingFor(world);
        AntipodeChunkLoader loader = loaderFor(world);
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
            // 2. Pre-generate the seam bridge if we are approaching the antimeridian.
            double bridgeMapX = folding.seamBridgeTargets(player.getX());
            if (!Double.isNaN(bridgeMapX)) {
                loader.requestArea(bridgeMapX, player.getZ(), cfg.seamOverlapChunks);
            }

            // 3. Fold longitude, then pole, teleporting seamlessly if needed.
            WorldFolding.FoldResult lon = folding.foldLongitude(player.getX(), player.getZ());
            if (lon.teleported) {
                applyFold(player, state, lon.newMinecraftX, player.getY(), lon.newMinecraftZ, lon.bearingDelta);
            }
            WorldFolding.FoldResult pole = folding.foldPole(player.getX(), player.getZ());
            if (pole.teleported) {
                applyFold(player, state, pole.newMinecraftX, player.getY(), pole.newMinecraftZ, pole.bearingDelta);
            }
        }

        // 4. Core-entry detection: sustained straight-down below the local surface threshold.
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
    }

    // ------------------------------------------------------------------ core traversal / antipode

    private void beginCoreTraversal(ServerPlayerEntity player, WorldFolding folding,
                                    PlayerGeoState state, GeoidConfig cfg) {
        Geodetic origin = state.geodetic.withAltitude(0.0);
        CoreTunnel tunnel = new CoreTunnel(origin);
        traversals.put(player.getUuid(), tunnel);
        state.phase = PlayerGeoState.Phase.CORE_DESCENT;
        state.coreParam = -state.geodetic.altitude; // depth already dug becomes initial s
        state.geodetic = tunnel.geodeticAt(state.coreParam);
        state.frame = tunnel.frameAt(state.coreParam);
    }

    private void tickCore(ServerPlayerEntity player, WorldFolding folding, AntipodeChunkLoader loader,
                          PlayerGeoState state, GeoidConfig cfg) {
        CoreTunnel tunnel = traversals.get(player.getUuid());
        if (tunnel == null) {
            state.phase = PlayerGeoState.Phase.SURFACE;
            return;
        }

        double prevParam = state.coreParam;
        double realizedDy = player.getY() - player.lastY; // MC-facing: realised vertical move this tick
        SphericalPhysics.advanceTraversal(state, tunnel, realizedDy);

        // Pre-load the antipodal surface as soon as we pass the centre, so the exit never shows a hole.
        boolean crossedCentre = prevParam <= earth.tellus.geoid.math.SphereMath.EARTH_RADIUS
                && state.coreParam > earth.tellus.geoid.math.SphereMath.EARTH_RADIUS;
        if (crossedCentre) {
            Geodetic anti = tunnel.antipode();
            Vec3 mc = folding.geodeticToMc(anti);
            loader.requestArea(mc.x, mc.z, cfg.antipodePreloadRadius);
        }

        // Exit: reached the antipodal surface. Re-anchor the player onto real antipode terrain.
        if (state.coreParam >= earth.tellus.geoid.math.SphereMath.DIAMETER - 1.0) {
            Geodetic anti = tunnel.antipode().withAltitude(0.0);
            Vec3 mc = folding.geodeticToMc(anti);
            player.networkHandler.requestTeleport(mc.x, mc.y, mc.z, player.getYaw(), player.getPitch());
            state.geodetic = anti;
            state.phase = PlayerGeoState.Phase.SURFACE;
            state.coreParam = 0;
            state.frame = earth.tellus.geoid.math.Quat.IDENTITY;
            state.foldEpoch++;
            traversals.remove(player.getUuid());
        }
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

    private static boolean isDiggingDown(ServerPlayerEntity player) {
        // Heuristic: moving downward and looking steeply down. Refine with a block-break hook if desired.
        return (player.getY() - player.lastY) < -0.05 && player.getPitch() > 45.0f;
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
