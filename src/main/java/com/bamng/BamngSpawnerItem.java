package com.bamng;

import com.antaurora.vettura.vehicle.CabStowage;
import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.antaurora.vettura.vehicle.VehicleKeyItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Spawns the real Vettura pickup, not a BAMNG shell entity.  The marker tells
 * BAMNG's client physics overlay to take over this particular truck.  The
 * actual type comes from the Vettura entity; nothing here assumes T15.
 */
public final class BamngSpawnerItem extends Item {
    public static final String SOFT_TAG = "VnodoSoft";
    public static final String LEGACY_SOFT_TAG = "BamngSoft";
    public static final String SOFT_NAME = "Vnodo Soft Vehicle";
    public static final String LEGACY_SOFT_NAME = "BAMNG Soft Vehicle";

    public BamngSpawnerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer server) {
            Vec3 look = player.getLookAngle();
            Vec3 spawn = player.getEyePosition().add(look.scale(2.2D));
            this.spawn(level, server, spawn, player.getYRot());
            this.consume(player, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        if (!level.isClientSide && player instanceof ServerPlayer server) {
            BlockPos base = context.getClickedPos().relative(context.getClickedFace());
            Vec3 spawn = Vec3.atBottomCenterOf(base);
            this.spawn(level, server, spawn, player.getYRot());
            this.consume(player, context.getItemInHand());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private void consume(Player player, ItemStack stack) {
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    private void spawn(Level level, ServerPlayer player, Vec3 spawn, float yaw) {
        // Both the item's air-use and block-use paths can hand us a point
        // above the road.  Letting the truck fall even one block produced a
        // permanent dent before the player ever drove it.  Snap the chassis
        // origin to the actual surface at the spawn column.
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(spawn.x), Mth.floor(spawn.z));
        Vec3 placed = new Vec3(spawn.x, groundY, spawn.z);
        PickupTruckEntity truck = PickupTruckEntity.create(level, placed, yaw);
        truck.getPersistentData().putBoolean(SOFT_TAG, true);
        // ForgeData is server-side only; the custom name is synced in the
        // entity metadata and is what the client uses to find the truck.
        truck.setCustomName(Component.literal(SOFT_NAME));
        truck.setCustomNameVisible(false);

        int paint = truck.vehicleType().paints.get(0).main();
        truck.setPaint(paint, paint);
        truck.setWear(0.0F);
        truck.setFuel(truck.tank());
        truck.setKeySpot(CabStowage.KeySpot.ELSEWHERE);

        ItemStack key = VehicleKeyItem.forVehicle(truck.getUUID(), truck.vehicleType());
        if (!player.getInventory().add(key)) {
            player.drop(key, false);
        }

        level.addFreshEntity(truck);
    }

    /** Server tag or the synced custom name; client cannot read ForgeData. */
    public static boolean isSoft(PickupTruckEntity truck) {
        return truck.getPersistentData().getBoolean(SOFT_TAG)
                || truck.getPersistentData().getBoolean(LEGACY_SOFT_TAG)
                || (truck.hasCustomName() && (SOFT_NAME.equals(truck.getCustomName().getString())
                || LEGACY_SOFT_NAME.equals(truck.getCustomName().getString())));
    }
}
