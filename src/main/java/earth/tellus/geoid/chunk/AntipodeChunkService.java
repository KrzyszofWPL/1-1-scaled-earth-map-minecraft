package earth.tellus.geoid.chunk;

import earth.tellus.geoid.mixin.ChunkTicketTypeInvoker;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * Real {@link AntipodeChunkLoader.TicketSink} backed by a {@link ServerWorld}'s chunk manager.
 *
 * <p>Uses a dedicated, self-expiring {@link ChunkTicketType} so the pre-loaded far-side terrain is
 * held only as long as the traversal/fold needs it and is reclaimed automatically if the player
 * changes their mind. The ticket level is chosen to fully generate (but not force-tick) the chunk, so
 * the terrain, structures and Tellus heightmap are all present when the player arrives, without paying
 * to keep entities and block-ticks running there.
 *
 * <p>Targets Fabric / Yarn on Minecraft 1.21.x. If your mappings differ, only the three MC calls here
 * ({@code register}, {@code addTicket}, {@code removeTicket}) need adjusting — the scheduling logic lives
 * entirely in {@link AntipodeChunkLoader} and is untouched.
 */
public final class AntipodeChunkService implements AntipodeChunkLoader.TicketSink {

    /**
     * Ticket type for spherical pre-loading. Expires after 200 ticks (10s) so a stale bridge unloads
     * itself; the loader refreshes it every tick while the player is still approaching.
     */
    public static final ChunkTicketType GEOID_PRELOAD =
            ChunkTicketTypeInvoker.geoid$register("geoid_preload", 200, 0);

    /**
     * Level 33 = "border" (loaded + full generation, no ticking). 31 would also tick entities/blocks;
     * we deliberately stay one level out to keep the antipode cheap until the player is actually there.
     */
    private static final int PRELOAD_LEVEL = 33;

    private final ServerWorld world;

    public AntipodeChunkService(ServerWorld world) {
        this.world = world;
    }

    @Override
    public void addTicket(int chunkX, int chunkZ) {
        ServerChunkManager cm = world.getChunkManager();
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        cm.addTicket(GEOID_PRELOAD, pos, PRELOAD_LEVEL);
    }

    @Override
    public void removeTicket(int chunkX, int chunkZ) {
        ServerChunkManager cm = world.getChunkManager();
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        cm.removeTicket(GEOID_PRELOAD, pos, PRELOAD_LEVEL);
    }
}
