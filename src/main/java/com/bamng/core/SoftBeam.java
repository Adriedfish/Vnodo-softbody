package com.bamng.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A node-to-node beam with BeamNG-style axial spring, damper, plastic flow and
 * force-threshold breaking.
 *
 * <p>The solver is force based: {@link #solveForce(SoftNode[])} accumulates
 * spring and damper force on both nodes.  Position projection is retained as
 * {@link #solve(SoftNode[])} for legacy callers and for a stable fallback on
 * very stiff lattices.</p>
 */
public final class SoftBeam {
    /** Sentinel matching BeamNG's "FLT_MAX". */
    public static final double FLT_MAX = Float.MAX_VALUE;

    public final int a;
    public final int b;

    /** Current rest length (plastic flow updates this). */
    public double restLength;
    /** Original rest length, used to cap accumulated plasticity. */
    public double originalRestLength;

    /** Legacy position-based stiffness in [0,1]. */
    public double stiffness;

    /** Strain thresholds retained for non-force damage paths. */
    public double yieldStrain;
    public double breakStrain;

    public boolean broken;
    public boolean deformed;
    public double plasticStrain;
    public int yieldCount;

    /** Last evaluated axial strain and force. */
    public double lastStrain;
    public double lastForce;
    public boolean lastForceValid;

    /** BeamNG-style material values. */
    public double beamSpring = 1_400_000.0;
    public double beamDamp = 1_200.0;
    public double beamDeform = 30_000.0;
    public double beamStrength = 90_000.0;
    public String beamType = "NORMAL";

    /**
     * ROR-style asymmetric yield thresholds.  Positive = compression limit,
     * negative = extension limit.  They are lazily initialised from
     * {@link #beamDeform} so existing definitions keep their exact behaviour
     * until someone sets them explicitly.
     */
    public double maxPosStress = -1.0;
    public double maxNegStress = 1.0;
    /** Smallest of the three stress limits, used for the break-protection cap. */
    public double minmaxStress = -1.0;
    private boolean lastSolveYielded;
    private double initialStrength = -1.0;
    private boolean stressLimitsReady;

    /** Optional asymmetric stiffness; <= 0 means use beamSpring. */
    public double beamSpringCompress = -1.0;
    /**
     * Crash-only stiffness multiplier.  Normal driving keeps the authored
     * spring rate; inside a confirmed crash window the front crush zone can
     * use a much softer rate without making the parked truck sag.
     */
    public double crashSpringScale = 1.0;

    /** Authoring region used by the Vnodo live tuning screen. */
    public String region = "body";
    /** Unscaled authored yield force, retained so a slider can restore it. */
    public double baseDeform = -1.0;

    /** Support beams only resist compression. */
    public boolean support;

    /** Precompression force (positive pushes the two ends apart). */
    public double precompression;

    /** Maximum accumulated plastic length change as a fraction of L0. */
    public double deformLimit = 0.35;
    /** Fraction of the over-threshold length written into L0 per event. */
    public double plasticityRate = 0.40;

    /**
     * Discrete thin-wall buckling.  When foldStep > 0 the beam accumulates
     * over-yield compression instead of creeping every substep; once a full
     * step is accumulated it snaps the rest length by exactly foldStep and
     * raises its compression yield by foldHardening.  This produces the
     * load-fold-harden-load sawtooth of a stamped steel rail instead of a
     * smooth rubber crush.  Zero keeps the legacy continuous model for the
     * chassis, suspension and every non-front group.
     */
    public double foldStep = 0.0;
    public double foldHardening = 0.0;
    public double foldAccum = 0.0;
    public int foldEvents = 0;
    /**
     * Fold events are permanent deformation.  They are only allowed inside a
     * confirmed crash window; ordinary driving compression must reload the
     * elastic spring and must not shorten the rest length.
     */
    public boolean foldEnabled = true;
    /**
     * Permanent plastic flow (both the discrete front fold and the legacy
     * continuous branch) is disabled while the truck is merely driving.
     * Elastic compression still works; only rest-length changes are gated.
     */
    public boolean plasticEnabled = true;

    /** Last tick this beam was settled by applyWallDents (one pass per tick). */
    public long wallDentTick = Long.MIN_VALUE;

    /** Whether force thresholds instead of legacy strain thresholds decide damage. */
    public boolean forceDamage = true;

    /** breakGroup event graph membership. */
    public final List<String> breakGroups = new ArrayList<>();

    /**
     * 0 = this beam triggers the group and can be broken by it;
     * 1 = this beam only follows the group and never triggers it.
     */
    public int breakGroupType;

    /** Visual/powertrain deform group membership. */
    public final List<String> deformGroups = new ArrayList<>();
    public double deformationTriggerRatio = 0.10;
    public boolean deformTriggered;

    /** BeamNG mesh/triangle break switches. */
    public boolean disableMeshBreaking;
    public boolean disableTriangleBreaking;

    public SoftBeam(int a, int b, double restLength, double stiffness,
                    double yieldStrain, double breakStrain) {
        this.a = a;
        this.b = b;
        this.restLength = restLength;
        this.originalRestLength = restLength;
        this.stiffness = stiffness;
        this.yieldStrain = yieldStrain;
        this.breakStrain = breakStrain;
    }

    /**
     * Force-based beam solve.  Implements:
     * <pre>
     *   F = k * (L - L0) + c * dot(vB - vA, u) + precompression
     *   FA += F * u; FB -= F * u
     * </pre>
     * Collisions are deliberately not springs; they are resolved after
     * integration by {@link SoftBodyWorld}.
     */
    public double solveForce(SoftNode[] nodes) {
        lastForceValid = false;
        lastSolveYielded = false;
        if (broken) {
            return 0.0;
        }
        SoftNode na = nodes[a];
        SoftNode nb = nodes[b];
        double dx = nb.position.x - na.position.x;
        double dy = nb.position.y - na.position.y;
        double dz = nb.position.z - na.position.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-9) {
            return 0.0;
        }

        double ux = dx / length;
        double uy = dy / length;
        double uz = dz / length;
        double extension = length - restLength;
        ensureStressLimits();

        double k = beamSpring;
        if (beamSpringCompress > 0.0 && extension < 0.0) {
            k = beamSpringCompress;
        }
        if (this.plasticEnabled && this.crashSpringScale > 0.0) {
            k *= this.crashSpringScale;
        }
        double springForce = k * extension;
        if (support) {
            springForce = Math.max(0.0, -springForce);
        }
        double vrel = (nb.velocity.x - na.velocity.x) * ux
                + (nb.velocity.y - na.velocity.y) * uy
                + (nb.velocity.z - na.velocity.z) * uz;
        double dampForce = beamDamp * vrel;

        double force = springForce + dampForce + precompression;

        // ROR CalcBeams: compression and extension have separate yield
        // limits.  Compression shortens the rest length but keeps the
        // breaking strength (structure must not chain-collapse).  Extension
        // also shortens the strength, which is what lets a torn front end
        // break on the next hit.
        if (this.plasticEnabled && force < -maxPosStress && extension < 0.0) {
            double yieldLength = maxPosStress / Math.max(1.0e-9, k);
            double deform = extension + yieldLength * (1.0 - plasticityRate);
            double oldLength = restLength;
            double limit = originalRestLength * Math.max(0.0, deformLimit);
            double floor = Math.max(0.1, originalRestLength - limit);

            if (this.foldStep > 0.0) {
                // Discrete buckling: accumulate the over-yield compression,
                // but do not move restLength until one full fold step exists.
                // Between events the spring keeps loading (force rises); at
                // the event it snaps one step and the local yield rises.  This
                // is the sawtooth platform the continuous flow model erased.
                // Outside a crash window this branch is inert: ordinary
                // gravity/suspension compression must not ratchet the nose
                // shorter while the truck is simply driving.
                if (this.foldEnabled) {
                    this.foldAccum += Math.abs(deform);
                    if (this.foldAccum + 1.0e-9 >= this.foldStep
                            && restLength > floor + 1.0e-9) {
                        double step = Math.min(this.foldStep, restLength - floor);
                        double nextLength = restLength - step;
                        double applied = nextLength - oldLength;
                        restLength = nextLength;
                        this.foldAccum = Math.max(0.0, this.foldAccum - this.foldStep);
                        this.foldEvents++;
                        maxPosStress = Math.min(beamStrength * 0.85,
                                maxPosStress + this.foldHardening);
                        force = 0.5 * (force - maxPosStress);
                        if (restLength > 0.0 && oldLength > restLength) {
                            maxPosStress = Math.min(beamStrength * 0.85,
                                    maxPosStress * oldLength / restLength);
                            minmaxStress = Math.min(maxPosStress,
                                    Math.min(-maxNegStress, beamStrength));
                        }
                        plasticStrain += Math.abs(applied);
                        yieldCount++;
                        deformed = true;
                        lastSolveYielded = true;
                        // The renderer and HUD read target + plastic.  A fold
                        // that only shortens restLength moves the live solver
                        // nodes for a few frames and then disappears from the
                        // visual rest shape.  Record half the fold travel on
                        // each endpoint so panelFrontDepth/bucket ridges
                        // actually reach the mesh.
                        applyFoldPlastic(na, nb, step);
                    }
                }
            } else {
                double nextLength = Math.max(floor, Math.max(0.1, restLength + deform));
                double applied = nextLength - oldLength;
                restLength = nextLength;
                // The continuous chassis/body path only changes restLength,
                // which the renderer cannot see.  Mirror the compression into
                // the permanent node offset as well so a whole-body softening
                // actually deforms the cab, bed sides and frame rails.
                if (applied < 0.0) {
                    applyFoldPlastic(na, nb, -applied);
                }
                force = 0.5 * (force - maxPosStress);
                if (restLength > 0.0 && oldLength > restLength) {
                    maxPosStress *= oldLength / restLength;
                    minmaxStress = Math.min(maxPosStress,
                            Math.min(-maxNegStress, beamStrength));
                }
                plasticStrain += Math.abs(applied);
                yieldCount++;
                deformed = true;
                lastSolveYielded = true;
            }
        } else if (force > -maxNegStress && extension > 0.0) {
            double maxTension = -maxNegStress;
            double yieldLength = maxTension / Math.max(1.0e-9, k);
            double deform = extension - yieldLength * (1.0 - plasticityRate);
            double oldLength = restLength;
            double limit = originalRestLength * Math.max(0.0, deformLimit);
            double ceiling = originalRestLength + limit;
            double nextLength = Math.min(ceiling, Math.max(0.1, restLength + deform));
            double applied = nextLength - oldLength;
            restLength = nextLength;
            force = 0.5 * (force + maxTension);
            if (oldLength > 0.0 && restLength > oldLength) {
                maxNegStress *= restLength / oldLength;
                minmaxStress = Math.min(maxPosStress,
                        Math.min(-maxNegStress, beamStrength));
            }
            beamStrength -= applied * k;
            plasticStrain += Math.abs(applied);
            yieldCount++;
            deformed = true;
            lastSolveYielded = true;
        }

        // A single beam must never inject an unbounded impulse.  The cap is
        // deliberately well above the break threshold so it cannot hide a
        // legitimate break, but low enough to keep a bad frame finite.
        double cap = Math.max(beamStrength * 4.0, 1_000_000.0);
        force = Math.max(-cap, Math.min(cap, force));

        lastForce = force;
        lastStrain = extension / Math.max(1.0e-6, restLength);
        lastForceValid = true;

        double fx = force * ux;
        double fy = force * uy;
        double fz = force * uz;
        na.addForce(fx, fy, fz);
        nb.addForce(-fx, -fy, -fz);
        return force;
    }

    private void ensureStressLimits() {
        if (stressLimitsReady) {
            return;
        }
        stressLimitsReady = true;
        if (maxPosStress <= 0.0) {
            maxPosStress = Math.max(1.0, beamDeform);
        }
        if (maxNegStress >= 0.0) {
            maxNegStress = -Math.max(1.0, beamDeform);
        }
        if (initialStrength < 0.0) {
            initialStrength = Math.max(1.0, beamStrength);
        }
        if (minmaxStress <= 0.0) {
            minmaxStress = Math.min(maxPosStress,
                    Math.min(-maxNegStress, initialStrength));
        }
    }

    /**
     * Position projection fallback.  Kept for the standalone tests and for
     * emergency stabilization of a NaN/stiff substep.
     */
    public void solve(SoftNode[] nodes) {
        if (broken) {
            return;
        }
        SoftNode na = nodes[a];
        SoftNode nb = nodes[b];
        double dx = nb.position.x - na.position.x;
        double dy = nb.position.y - na.position.y;
        double dz = nb.position.z - na.position.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-9) {
            return;
        }

        double wA = na.inverseMass;
        double wB = nb.inverseMass;
        double wSum = wA + wB;
        if (wSum <= 0.0) {
            return;
        }

        double correction = (length - restLength) / length * stiffness;
        double cx = dx * correction;
        double cy = dy * correction;
        double cz = dz * correction;

        if (wA > 0.0) {
            double s = wA / wSum;
            na.position.x += cx * s;
            na.position.y += cy * s;
            na.position.z += cz * s;
        }
        if (wB > 0.0) {
            double s = wB / wSum;
            nb.position.x -= cx * s;
            nb.position.y -= cy * s;
            nb.position.z -= cz * s;
        }
    }

    /**
     * One damage evaluation per substep.  Returns true only when this call
     * transitions the beam from intact to broken.
     */
    public boolean evaluateDamage(SoftNode[] nodes) {
        if (broken) {
            return false;
        }
        SoftNode na = nodes[a];
        SoftNode nb = nodes[b];
        double dx = nb.position.x - na.position.x;
        double dy = nb.position.y - na.position.y;
        double dz = nb.position.z - na.position.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-9) {
            return false;
        }

        double rest = Math.max(1.0e-6, restLength);
        lastStrain = (length - rest) / rest;
        boolean fromSolver = lastForceValid;

        if (!lastForceValid) {
            double nx = dx / length;
            double ny = dy / length;
            double nz = dz / length;
            double relativeVelocity = (nb.velocity.x - na.velocity.x) * nx
                    + (nb.velocity.y - na.velocity.y) * ny
                    + (nb.velocity.z - na.velocity.z) * nz;
            lastForce = beamSpring * (length - rest) + beamDamp * relativeVelocity;
            lastForceValid = true;
        }

        boolean shouldBreak;
        if (forceDamage) {
            shouldBreak = Math.abs(lastForce) > beamStrength;
            // solveForce already applied ROR's asymmetric yield and plastic
            // length change.  Only the legacy direct-damage path (tests or
            // callers that never ran the solver) needs the old plastic probe.
            if (!shouldBreak && !fromSolver && Math.abs(lastForce) > beamDeform) {
                applyPlasticFlow(length - restLength, plasticityRate);
            }
        } else {
            shouldBreak = Math.abs((length - restLength) / restLength) > breakStrain;
            if (!shouldBreak && Math.abs(lastStrain) > yieldStrain) {
                applyPlasticFlow(length - restLength, plasticityRate);
            }
        }

        if (shouldBreak) {
            broken = true;
            return true;
        }
        return false;
    }

    private void applyPlasticFlow(double strainLength, double flow) {
        double limit = originalRestLength * deformLimit;
        double next = restLength + strainLength * flow;
        next = Math.max(originalRestLength - limit, Math.min(originalRestLength + limit, next));
        restLength = next;
        plasticStrain += Math.abs(strainLength);
        yieldCount++;
        deformed = true;
        // Plastic flow is permanent geometry, not a softer spring.  Keeping
        // k constant is important: otherwise repeated yields turn the whole
        // chassis into a noodle.
    }

    /**
     * Write a fold event into the permanent node offset used by the renderer.
     * The solver has already moved the live nodes; this keeps the rest shape
     * (target + plastic) folded after the spring settles back.
     */
    private static void applyFoldPlastic(SoftNode na, SoftNode nb, double step) {
        double dx = nb.position.x - na.position.x;
        double dy = nb.position.y - na.position.y;
        double dz = nb.position.z - na.position.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6) {
            return;
        }
        double half = step * 0.5;
        double ux = dx / len * half;
        double uy = dy / len * half;
        double uz = dz / len * half;
        addPlasticOffset(na, ux, uy, uz);
        addPlasticOffset(nb, -ux, -uy, -uz);

        // A pure axial shortening reads as the whole nose being pushed
        // straight back.  A real stamped rail buckles into a kink first.
        // Add the same permanent vertical sag to both ends of the folded
        // member: shared nodes accumulate the same sign (no cancellation at
        // a panel seam), while the front-most folds get more events and the
        // result is a smooth banana-shaped drop instead of a knife cut.
        double lateral = (na.rest.z + nb.rest.z) * 0.5;
        double sag = Math.min(0.035, Math.abs(step) * 0.55);
        double side = lateral >= 0.0 ? 1.0 : -1.0;
        addPlasticOffset(na, 0.0, -sag, side * sag * 0.10);
        addPlasticOffset(nb, 0.0, -sag, side * sag * 0.10);
    }

    private static void addPlasticOffset(SoftNode node, double dx, double dy, double dz) {
        double px = node.plasticX + dx;
        double py = node.plasticY + dy;
        double pz = node.plasticZ + dz;
        double limit = node.plasticLimit;
        if (limit > 0.0 && limit < Double.MAX_VALUE) {
            double d = Math.sqrt(px * px + py * py + pz * pz);
            if (d > limit && d > 1.0e-9) {
                double scale = limit / d;
                px *= scale;
                py *= scale;
                pz *= scale;
            }
        }
        node.plasticX = px;
        node.plasticY = py;
        node.plasticZ = pz;
    }

    /** Restore original topology for a world reset (D7). */
    public void resetDamageState() {
        broken = false;
        restLength = originalRestLength;
        plasticStrain = 0.0;
        yieldCount = 0;
        foldAccum = 0.0;
        foldEvents = 0;
        wallDentTick = Long.MIN_VALUE;
        deformed = false;
        lastStrain = 0.0;
        lastForce = 0.0;
        lastForceValid = false;
        lastSolveYielded = false;
        deformTriggered = false;
        maxPosStress = Math.max(1.0, beamDeform);
        maxNegStress = -Math.max(1.0, beamDeform);
        if (initialStrength > 0.0) {
            beamStrength = initialStrength;
        }
        minmaxStress = Math.min(maxPosStress,
                Math.min(-maxNegStress, Math.max(1.0, beamStrength)));
        stressLimitsReady = true;
    }

    /** Break a beam because a breakGroup fired, without doing force maths.
     *  @return true when this call transitioned the beam to broken. */
    public boolean breakFromGroup() {
        if (!broken) {
            broken = true;
            return true;
        }
        return false;
    }

    public double currentLength(SoftNode[] nodes) {
        SoftNode na = nodes[a];
        SoftNode nb = nodes[b];
        double dx = nb.position.x - na.position.x;
        double dy = nb.position.y - na.position.y;
        double dz = nb.position.z - na.position.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Critical damping for this beam's two endpoint masses. */
    public double criticalDamping(SoftNode[] nodes) {
        double mA = Math.max(1.0e-9, nodes[a].mass);
        double mB = Math.max(1.0e-9, nodes[b].mass);
        double effective = (mA * mB) / (mA + mB);
        return 2.0 * Math.sqrt(Math.max(1.0, beamSpring) * effective);
    }

    /** Maximum stable integration step for semi-implicit Euler. */
    public double stableStep(SoftNode[] nodes) {
        double mA = Math.max(1.0e-9, nodes[a].mass);
        double mB = Math.max(1.0e-9, nodes[b].mass);
        double omega = Math.sqrt(beamSpring * (1.0 / mA + 1.0 / mB));
        return omega > 1.0e-9 ? 2.0 / omega : 1.0e9;
    }
}
