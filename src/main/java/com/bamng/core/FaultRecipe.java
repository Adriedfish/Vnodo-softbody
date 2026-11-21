package com.bamng.core;

/** F8: named, deterministic fault recipes used by regression runs. */
public final class FaultRecipe {
    private FaultRecipe() {
    }

    public static boolean apply(String name, VehicleBody body) {
        if (name == null || body == null) {
            return false;
        }
        switch (name) {
            case "front_crush" -> {
                for (SoftNode node : body.world.nodes) {
                    if (node.part.startsWith("front") || node.part.equals("hood")) {
                        node.velocity.x -= 6.0;
                    }
                }
                return true;
            }
            case "tailgate_shear" -> {
                for (SoftNode node : body.world.nodes) {
                    if (node.part.equals("tailgate")) {
                        node.velocity.y += 4.0;
                        node.velocity.z += 3.0;
                    }
                }
                return true;
            }
            case "tire_burst" -> {
                for (SoftTire tire : body.tires) {
                    tire.chamber.gaugePressure = 0.0;
                }
                return true;
            }
            case "overheat" -> {
                body.world.globalDamping = 0.05;
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
