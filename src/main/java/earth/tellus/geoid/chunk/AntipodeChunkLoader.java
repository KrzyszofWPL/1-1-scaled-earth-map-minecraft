package earth.tellus.geoid.chunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Budgeted, look-ahead chunk pre-loader for the far side of a fold and for the antipode.
 *
 * <p>Three situations force the engine to make far-away terrain ready <em>before</em> the player is
 * teleported onto it, or the fold/wrap/core-exit would show a hole:
 * <ol>
 *   <li>The seam bridge, when a circumnavigating player nears the antimeridian.</li>
 *   <li>The antipodal surface, once a core traversal passes the centre.</li>
 *   <li>The re-based window after a longitudinal fold.</li>
 * </ol>
 *
 * <p>Loading a full render-distance disc in one tick would stall the server. Instead this class walks a
 * ring-by-ring spiral out from the target and issues at most {@link #chunkBudgetPerTick} tickets per
 * tick, on the server thread but spread over many ticks, while Minecraft's own worker pool does the
 * actual generation asynchronously. The result is a steadily-filling target region with no TPS spike.
 *
 * <p>The Minecraft-facing ticket calls are funnelled through {@link TicketSink} so this scheduler stays
 * unit-testable and version-agnostic; {@code AntipodeChunkService} provides the real
 * {@code ServerChunkManager} implementation.
 */
public final class AntipodeChunkLoader {

    /** Abstraction over "make chunk (cx,cz) start generating / stay loaded at ticket level L". */
    public interface TicketSink {
        void addTicket(int chunkX, int chunkZ);

        void removeTicket(int chunkX, int chunkZ);
    }

    private static final class Job {
        final int centerX;
        final int centerZ;
        final int radius;
        int ring;      // current spiral ring being emitted
        int indexInRing;
        final Set<Long> issued = new HashSet<>();

        Job(int centerX, int centerZ, int radius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
        }

        boolean done() {
            return ring > radius;
        }
    }

    private final TicketSink sink;
    private final int chunkBudgetPerTick;
    private final Deque<Job> jobs = new ArrayDeque<>();
    private final Set<Long> globallyIssued = new HashSet<>();

    public AntipodeChunkLoader(TicketSink sink, int chunkBudgetPerTick) {
        this.sink = sink;
        this.chunkBudgetPerTick = Math.max(1, chunkBudgetPerTick);
    }

    /**
     * Requests that a disc of {@code radius} chunks around block position {@code (blockX, blockZ)} be
     * pre-loaded. Idempotent-ish: re-requesting the same area while a job is in flight is cheap.
     */
    public void requestArea(double blockX, double blockZ, int radius) {
        int cx = Math.floorDiv((int) Math.floor(blockX), 16);
        int cz = Math.floorDiv((int) Math.floor(blockZ), 16);
        jobs.add(new Job(cx, cz, radius));
    }

    /** Call once per server tick. Emits up to the per-tick budget of tickets from the nearest ring outward. */
    public void tick() {
        int budget = chunkBudgetPerTick;
        while (budget > 0 && !jobs.isEmpty()) {
            Job job = jobs.peek();
            if (job.done()) {
                jobs.poll();
                continue;
            }
            budget -= emitRing(job, budget);
        }
    }

    /** Emits as much of the current spiral ring as the budget allows; returns tickets actually issued. */
    private int emitRing(Job job, int budget) {
        int issued = 0;
        int r = job.ring;
        // Enumerate the perimeter of the square ring of radius r (r == 0 -> the centre chunk).
        int perimeter = r == 0 ? 1 : 8 * r;
        while (job.indexInRing < perimeter && issued < budget) {
            int[] off = ringOffset(r, job.indexInRing);
            int cx = job.centerX + off[0];
            int cz = job.centerZ + off[1];
            long key = pack(cx, cz);
            if (job.issued.add(key) && globallyIssued.add(key)) {
                sink.addTicket(cx, cz);
                issued++;
            }
            job.indexInRing++;
        }
        if (job.indexInRing >= perimeter) {
            job.ring++;
            job.indexInRing = 0;
        }
        return issued;
    }

    /** Maps a perimeter index on the square ring of radius {@code r} to an (dx,dz) offset. */
    private static int[] ringOffset(int r, int i) {
        if (r == 0) {
            return new int[] {0, 0};
        }
        int side = 2 * r;          // length of one edge in steps
        int edge = i / side;       // 0=top,1=right,2=bottom,3=left
        int pos = i % side;
        switch (edge) {
            case 0:  return new int[] {-r + pos, -r};
            case 1:  return new int[] { r, -r + pos};
            case 2:  return new int[] { r - pos, r};
            default: return new int[] {-r, r - pos};
        }
    }

    /** Releases all tickets issued for a target area once the player is safely established there. */
    public void releaseArea(double blockX, double blockZ, int radius) {
        int cx = Math.floorDiv((int) Math.floor(blockX), 16);
        int cz = Math.floorDiv((int) Math.floor(blockZ), 16);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long key = pack(cx + dx, cz + dz);
                if (globallyIssued.remove(key)) {
                    sink.removeTicket(cx + dx, cz + dz);
                }
            }
        }
    }

    public int pendingJobs() {
        return jobs.size();
    }

    private static long pack(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }
}
