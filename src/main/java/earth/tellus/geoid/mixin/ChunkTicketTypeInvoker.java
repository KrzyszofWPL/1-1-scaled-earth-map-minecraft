package earth.tellus.geoid.mixin;

import net.minecraft.server.world.ChunkTicketType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code ChunkTicketType}'s private static factory so {@link
 * earth.tellus.geoid.chunk.AntipodeChunkService} can register its own preload ticket type.
 */
@Mixin(ChunkTicketType.class)
public interface ChunkTicketTypeInvoker {

    @Invoker("register")
    static ChunkTicketType geoid$register(String id, long expiryTicks, int flags) {
        throw new AssertionError("Mixin injection failed");
    }
}
