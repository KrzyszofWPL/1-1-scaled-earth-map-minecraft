package earth.tellus.geoid.mixin;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@code World#isClient}, now a private field, for {@link earth.tellus.geoid.physics.GeoidGravity}. */
@Mixin(World.class)
public interface WorldAccessor {

    @Accessor("isClient")
    boolean geoid$isClient();
}
