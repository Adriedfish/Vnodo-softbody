package com.bamng.entity;

import com.bamng.BamngNetwork;
import com.bamng.core.SoftNode;
import com.bamng.core.T15Body;
import com.bamng.core.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Soft-body T15 vehicle entity. Physics runs on the logical side that owns the
 * entity; the client sends a small drive packet to the server.
 */
public class SoftBodyVehicleEntity extends Entity {
    private final T15Body body;
    private boolean initialized;
    private double baseEntityY;
    private float throttle;
    private float brake;
    private float steer;

    public SoftBodyVehicleEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.body = T15Body.create(0.82);
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.baseEntityY = tag.getDouble("BamngBaseY");
        this.initialized = tag.getBoolean("BamngInit");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putDouble("BamngBaseY", this.baseEntityY);
        tag.putBoolean("BamngInit", this.initialized);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.initialized) {
            this.initBody();
        }

        if (this.level().isClientSide) {
            this.clientControls();
        }

        this.body.world.throttle = this.throttle;
        this.body.world.brake = this.brake;
        this.body.world.steer = this.steer;
        this.body.world.step(0.05, 32);

        this.body.world.groundY = this.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(this.getX()), (int) Math.floor(this.getZ()));

        Vec3 center = this.body.world.center();
        this.setPos(center.x, this.baseEntityY, center.z);
        this.setDeltaMovement(0.0, 0.0, 0.0);
    }

    private void initBody() {
        double x = this.getX();
        double y = this.getY();
        double z = this.getZ();
        this.baseEntityY = y + this.body.baseY;
        for (SoftNode node : this.body.world.nodes) {
            node.position.add(x, y, z);
            node.previous.set(node.position);
            node.rest.set(node.position);
        }
        this.setPos(x, this.baseEntityY, z);
        this.initialized = true;
    }

    @OnlyIn(Dist.CLIENT)
    private void clientControls() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.getVehicle() != this) {
            this.throttle = 0.0F;
            this.brake = 0.0F;
            this.steer = 0.0F;
            return;
        }
        float t = player.input.up ? 1.0F : 0.0F;
        float b = player.input.down ? 1.0F : 0.0F;
        float s = (player.input.left ? 1.0F : 0.0F) - (player.input.right ? 1.0F : 0.0F);
        if (t != this.throttle || b != this.brake || s != this.steer) {
            this.throttle = t;
            this.brake = b;
            this.steer = s;
            BamngNetwork.sendDrive(this.getId(), t, b, s);
        }
    }

    public void acceptDrive(float t, float b, float s) {
        this.throttle = Math.max(0.0F, Math.min(1.0F, t));
        this.brake = Math.max(0.0F, Math.min(1.0F, b));
        this.steer = Math.max(-1.0F, Math.min(1.0F, s));
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!this.level().isClientSide && !player.isPassenger()) {
            player.startRiding(this);
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    @Override
    public double getPassengersRidingOffset() {
        return 1.05D;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    public T15Body body() {
        return this.body;
    }

    public float throttle() {
        return this.throttle;
    }

    public float brake() {
        return this.brake;
    }
}
