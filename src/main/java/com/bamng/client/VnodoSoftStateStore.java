package com.bamng.client;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.bamng.BamngMod;
import com.bamng.core.SoftBeam;
import com.bamng.core.SoftBodyWorld;
import com.bamng.core.SoftNode;
import com.bamng.core.SoftTriangle;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side persistence for the Vnodo shell.
 *
 * <p>Vettura stores its own damage NBT, but the Vnodo lattice is a client
 * runtime built from the truck layout.  On every world re-entry it was reset
 * by {@code SoftRuntime.init()}, so a crash looked repaired until you hit the
 * same wall again.  The shell is keyed by dimension + truck UUID and written
 * next to the game directory; no server-side entity schema is required.</p>
 */
public final class VnodoSoftStateStore {
    private static final String FILE_SUFFIX = ".nbt";
    private static final String KEY_SOFT = "Soft";
    private static final String KEY_FRONT = "FrontDepth";
    private static final String KEY_REAR = "RearDepth";
    private static final String KEY_YAW = "Yaw";
    private static final Map<UUID, String> LAST_DIMENSIONS = new HashMap<>();
    private static String lastWorldName = "remote";

    private VnodoSoftStateStore() {
    }

    public static CompoundTag read(PickupTruckEntity truck) {
        Path path = path(truck);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(path.toFile());
        } catch (IOException | RuntimeException error) {
            BamngMod.LOGGER.warn("Vnodo shell state read failed {}: {}", path, error.toString());
            return null;
        }
    }

    public static void write(PickupTruckEntity truck, CompoundTag tag) {
        Path path = path(truck);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(tag, temp.toFile());
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            BamngMod.LOGGER.warn("Vnodo shell state write failed {}: {}", path, error.toString());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best effort: the temp file is harmless and the next save
                // will replace it.
            }
        }
    }

    public static void delete(PickupTruckEntity truck) {
        Path path = path(truck);
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            BamngMod.LOGGER.warn("Vnodo shell state delete failed {}: {}", path, error.toString());
        }
    }

    public static long stateHash(SoftBodyWorld world, double frontDepth, double rearDepth) {
        // Damage-only hash: the live positions/velocities change every tick,
        // and hashing them made a parked-but-moving truck write its snapshot
        // every 20 ticks even when nothing permanent changed.
        long hash = world.damageStateHash();
        hash = mix(hash, Double.doubleToLongBits(frontDepth));
        hash = mix(hash, Double.doubleToLongBits(rearDepth));
        return hash;
    }

    private static long mix(long hash, long value) {
        long mixed = hash ^ value;
        mixed *= 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 29;
        return mixed;
    }

    private static Path path(PickupTruckEntity truck) {
        UUID uuid = truck.getUUID();
        String dimension;
        if (truck.level() != null) {
            dimension = truck.level().dimension().location().toString();
            LAST_DIMENSIONS.put(uuid, dimension);
        } else {
            // LoggingOut can arrive after the level reference is gone.  In
            // that case reuse the dimension seen on the last real tick.
            dimension = LAST_DIMENSIONS.getOrDefault(uuid, "unknown");
        }
        String resolvedWorld = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            resolvedWorld = minecraft.getSingleplayerServer()
                    .getWorldPath(LevelResource.ROOT).getFileName().toString();
        } else if (minecraft.getCurrentServer() != null) {
            resolvedWorld = minecraft.getCurrentServer().name;
        }
        if (resolvedWorld != null && !resolvedWorld.isBlank()) {
            lastWorldName = safe(resolvedWorld);
        }
        return FMLPaths.GAMEDIR.get()
                .resolve("vnodo-soft-state")
                .resolve(lastWorldName)
                .resolve(safe(dimension))
                .resolve(uuid + FILE_SUFFIX);
    }

    private static String safe(String value) {
        String text = value == null || value.isBlank() ? "unknown" : value;
        return text.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static CompoundTag makeTag(SoftBodyWorld world, double frontDepth,
                                      double rearDepth, float yaw) throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray(KEY_SOFT, com.bamng.core.SoftBodySnapshot.saveVehicleState(world));
        tag.putDouble(KEY_FRONT, frontDepth);
        tag.putDouble(KEY_REAR, rearDepth);
        tag.putDouble(KEY_YAW, yaw);
        return tag;
    }

    public static double frontDepth(CompoundTag tag) {
        return tag == null ? 0.0 : tag.getDouble(KEY_FRONT);
    }

    public static double rearDepth(CompoundTag tag) {
        return tag == null ? 0.0 : tag.getDouble(KEY_REAR);
    }

    /** Saved body yaw; NaN means a pre-yaw save that must keep its old axes. */
    public static double savedYaw(CompoundTag tag) {
        return tag == null || !tag.contains(KEY_YAW) ? Double.NaN : tag.getDouble(KEY_YAW);
    }

    public static byte[] softBytes(CompoundTag tag) {
        return tag == null ? null : tag.getByteArray(KEY_SOFT);
    }
}
