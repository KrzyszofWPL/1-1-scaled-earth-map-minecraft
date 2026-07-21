package earth.tellus.geoid.net;

import earth.tellus.geoid.world.PlayerGeoState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> client sync of a player's spherical state.
 *
 * <p>The server owns the canonical {@link PlayerGeoState}; the client needs enough of it to run
 * matching predictive physics and to roll the camera. We send a compact snapshot every time the state
 * meaningfully changes (a fold, a phase transition, or every few ticks during a traversal) rather than
 * every tick, keeping bandwidth negligible.
 *
 * <p>Targets Fabric networking on 1.21.x ({@link CustomPayload} + {@link PacketCodec}).
 */
public record GeoStatePayload(
        double latRad,
        double lonRad,
        double altitude,
        double bearingRad,
        int phaseOrdinal,
        double coreParam,
        double frameW, double frameX, double frameY, double frameZ,
        long foldEpoch) implements CustomPayload {

    public static final Id<GeoStatePayload> ID =
            new Id<>(Identifier.of("geoid", "geo_state"));

    public static final PacketCodec<PacketByteBuf, GeoStatePayload> CODEC =
            PacketCodec.of(GeoStatePayload::write, GeoStatePayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeDouble(latRad);
        buf.writeDouble(lonRad);
        buf.writeDouble(altitude);
        buf.writeDouble(bearingRad);
        buf.writeVarInt(phaseOrdinal);
        buf.writeDouble(coreParam);
        buf.writeDouble(frameW);
        buf.writeDouble(frameX);
        buf.writeDouble(frameY);
        buf.writeDouble(frameZ);
        buf.writeVarLong(foldEpoch);
    }

    private static GeoStatePayload read(PacketByteBuf buf) {
        return new GeoStatePayload(
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readVarInt(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readVarLong());
    }

    public static GeoStatePayload from(PlayerGeoState s) {
        return new GeoStatePayload(
                s.geodetic.latRad, s.geodetic.lonRad, s.geodetic.altitude, s.bearingRad,
                s.phase.ordinal(), s.coreParam,
                s.frame.w, s.frame.x, s.frame.y, s.frame.z,
                s.foldEpoch);
    }

    /** Applies this snapshot onto a client-side {@link PlayerGeoState} (creating fields as needed). */
    public void applyTo(PlayerGeoState s) {
        s.geodetic = new earth.tellus.geoid.math.Geodetic(latRad, lonRad, altitude);
        s.bearingRad = bearingRad;
        s.phase = PlayerGeoState.Phase.values()[phaseOrdinal];
        s.coreParam = coreParam;
        s.frame = new earth.tellus.geoid.math.Quat(frameW, frameX, frameY, frameZ);
        s.foldEpoch = foldEpoch;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
