package com.bamng;

import com.bamng.entity.SoftBodyVehicleEntity;
import com.bamng.core.VnodoServerField;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;
import java.util.function.Supplier;

public final class BamngNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BamngMod.MODID, "main"),
            () -> VERSION,
            VERSION::equals,
            VERSION::equals
    );

    private BamngNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(DriveMessage.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DriveMessage::encode)
                .decoder(DriveMessage::new)
                .consumerMainThread(DriveMessage::handle)
                .add();
        CHANNEL.messageBuilder(CollisionFieldMessage.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CollisionFieldMessage::encode)
                .decoder(CollisionFieldMessage::new)
                .consumerMainThread(CollisionFieldMessage::handle)
                .add();
    }

    public static void sendDrive(int entityId, float throttle, float brake, float steer) {
        CHANNEL.sendToServer(new DriveMessage(entityId, throttle, brake, steer));
    }

    /**
     * Publish the client Vnodo crush field to the server.  An empty field is
     * the repair/clear signal; it removes the server shadow and lets Vettura
     * own the boxes again.
     */
    public static void sendCollisionField(UUID uuid, int version, float[] field) {
        if (uuid == null) {
            return;
        }
        CHANNEL.sendToServer(new CollisionFieldMessage(uuid, version,
                field == null ? new float[0] : field));
    }

    public record DriveMessage(int entityId, float throttle, float brake, float steer) {
        public DriveMessage(FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(this.entityId);
            buf.writeFloat(this.throttle);
            buf.writeFloat(this.brake);
            buf.writeFloat(this.steer);
        }

        public void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer player = context.getSender();
            if (player != null && player.level().getEntity(this.entityId) instanceof SoftBodyVehicleEntity truck) {
                truck.acceptDrive(this.throttle, this.brake, this.steer);
            }
            context.setPacketHandled(true);
        }
    }

    /**
     * Quantized float field.  One short per component gives sub-millimetre
     * resolution over the useful ±2.5 m crash range while keeping a full
     * 2856-cell frame under 6 KB on the wire.
     */
    public record CollisionFieldMessage(UUID uuid, int version, float[] field) {
        private static final double FIELD_SCALE = 8192.0;

        public CollisionFieldMessage(FriendlyByteBuf buf) {
            this(new UUID(buf.readLong(), buf.readLong()), buf.readVarInt(),
                    readField(buf));
        }

        private static float[] readField(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            if (length < 0 || length > 16_384) {
                return new float[0];
            }
            float[] values = new float[length];
            for (int i = 0; i < length; i++) {
                values[i] = (float) (buf.readShort() / FIELD_SCALE);
            }
            return values;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeLong(this.uuid.getMostSignificantBits());
            buf.writeLong(this.uuid.getLeastSignificantBits());
            buf.writeVarInt(this.version);
            buf.writeVarInt(this.field.length);
            for (float value : this.field) {
                double clamped = Math.max(-3.999, Math.min(3.999, value));
                buf.writeShort((int) Math.round(clamped * FIELD_SCALE));
            }
        }

        public void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> VnodoServerField.put(this.uuid, this.version, this.field));
            context.setPacketHandled(true);
        }
    }
}
