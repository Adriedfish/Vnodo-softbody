package com.bamng.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Wraps Vettura's original MultiBufferSource and deforms every emitted vertex
 * in the authored model space.
 *
 * <p>Vextris' {@code MeshRenderer.write} transforms the part vertices through
 * the live {@link PoseStack} before it calls {@link VertexConsumer#vertex}.
 * The old buffer assumed the incoming x/y/z were 1/16-block model-local
 * coordinates; in reality they were already entity/world-space.  It then
 * divided the folded result by 16 and wrote that back, which collapsed the
 * front shell into a tiny patch (visually a black, missing or torn nose).
 * This buffer now inverts the exact PoseStack matrix for the current part,
 * skins/folds in model-local block units, and transforms the result back
 * through the same matrix.</p>
 */
public final class BamngSkinBuffer implements MultiBufferSource {
    private final MultiBufferSource delegate;
    private final BamngRenderState.Skin skin;
    private final PoseStack pose;
    private static long bufferCalls;
    private static long vertexCalls;

    public static long bufferCalls() { return bufferCalls; }
    public static long vertexCalls() { return vertexCalls; }
    public static void resetCounters() { bufferCalls = 0; vertexCalls = 0; }

    public BamngSkinBuffer(MultiBufferSource delegate, BamngRenderState.Skin skin,
                           com.antaurora.vettura.vehicle.PickupTruckEntity truck,
                           PoseStack pose) {
        this.delegate = delegate;
        this.skin = skin;
        this.pose = pose;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        bufferCalls++;
        // RenderType identifies a material/texture, not a Vextris mesh. Use
        // the geometry path captured at Vettura's byGeometry call instead;
        // otherwise wheels/interior can accidentally inherit body weights.
        if (!BamngRenderHooks.shouldSkin()) {
            return this.delegate.getBuffer(type);
        }
        return new SkinVertexConsumer(this.delegate.getBuffer(type), this.skin, this.pose);
    }

    private static final class SkinVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final BamngRenderState.Skin skin;
        private final PoseStack pose;
        private final Vector3f point = new Vector3f();
        private final Vector3f local = new Vector3f();
        private final Vector3f normalLocal = new Vector3f();
        private final double cos;
        private final double sin;
        private final double frontDepth;
        private final double rearDepth;
        private final double bodyDepth;

        private final Matrix4f matrix = new Matrix4f();
        private final Matrix4f inverse = new Matrix4f();
        private final Matrix3f normalMatrix = new Matrix3f();
        private final Matrix3f normalInverse = new Matrix3f();
        private int matrixGeneration = -1;
        private boolean hasMatrix;
        private boolean invertible;
        private boolean normalInvertible;
        private boolean vertexPending;
        private double lastDent;
        private double lastFront;
        private double lastA;
        private double lastB;

        private SkinVertexConsumer(VertexConsumer delegate, BamngRenderState.Skin skin,
                                  PoseStack pose) {
            this.delegate = delegate;
            this.skin = skin;
            this.pose = pose;
            double yaw = Math.toRadians(skin.yaw());
            this.cos = Math.cos(yaw);
            this.sin = Math.sin(yaw);
            // The skin travel and the authored nose fold are both constant for
            // this frame.  Resolve the visual crush budget once per buffer, not
            // once per emitted vertex: a pickup shell emits six figures of
            // vertices.  The per-vertex share already supplied by the skin is
            // subtracted after skin.apply below, so an unbound vertex still
            // receives the full authored fold instead of staying rigid.
            this.frontDepth = Math.min(0.85, Math.max(0.0, skin.frontDepth()));
            this.rearDepth = Math.min(0.85, Math.max(0.0, skin.rearDepth()));
            this.bodyDepth = Math.min(0.85, Math.max(0.0, skin.bodyDepth()));
        }

        /** Cache the exact part matrix that Vextris wrote the vertices through. */
        private void ensureMatrix() {
            int generation = BamngRenderHooks.bonePivotGeneration();
            if (this.hasMatrix && generation == this.matrixGeneration) {
                return;
            }
            Matrix4f current = this.pose.last().pose();
            this.matrix.set(current);
            this.invertible = Math.abs(current.determinant()) > 1.0e-9;
            if (this.invertible) {
                this.inverse.set(current).invert();
            }
            Matrix3f normal = this.pose.last().normal();
            this.normalMatrix.set(normal);
            this.normalInvertible = Math.abs(normal.determinant()) > 1.0e-9;
            if (this.normalInvertible) {
                this.normalInverse.set(normal).invert();
            }
            this.matrixGeneration = generation;
            this.hasMatrix = true;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            vertexCalls++;
            BamngRenderStats.vertex();
            ensureMatrix();
            this.vertexPending = true;
            // Never let a normal from the previous vertex reuse stale fold
            // coefficients.  Every early-return branch below also writes
            // these, but resetting at entry makes the invariant explicit.
            this.lastDent = 0.0;
            this.lastFront = 0.0;
            this.lastA = 0.0;
            this.lastB = 1.0;
            if (!this.invertible) {
                this.delegate.vertex((float) x, (float) y, (float) z);
                return this;
            }
            // Undo the exact matrix Vextris used for this part.  The result is
            // the authored model space in blocks (the T15 front shell spans
            // z=-2.39..0.07, x=+/-0.89), not 1/16-block units.
            this.local.set((float) x, (float) y, (float) z);
            this.inverse.transformPosition(this.local);
            // Vextris stores vertices in bone-pivot-local blocks.  The pose
            // that was inverted above also contains the bone pivot translation,
            // so adding the current pivot restores full authored model space.
            // The body bone pivot is zero; hood/tailgate/wipers are not.
            double px = BamngRenderHooks.bonePivotX();
            double py = BamngRenderHooks.bonePivotY();
            double pz = BamngRenderHooks.bonePivotZ();
            double rx = this.local.x + px;
            double ry = this.local.y + py;
            double rz = this.local.z + pz;

            // The authored meshes are split into front shell, main body,
            // chassis and cab.  The old path only folded the front shell and
            // the tailgate, so a crash showed a vanished nose on top of a
            // completely rigid chassis, and a reverse hit left the whole rear
            // bed/body untouched.  Deform by authored region instead of by
            // mesh name: front clip, rear body, chassis rails, but not the cab
            // doors/interior.
            double lx = rx;
            double ly = ry;
            double lz = rz;
            double dent = 0.0;
            double front = 0.0;
            double a = 0.0;
            double b = 1.0;

            // Door/window bones live away from the centreline.  Never fold a
            // door with the bed or the nose; the pivot check keeps the cab
            // visually intact without needing a per-part name table.
            // Doors/windows/interior/wheels stay rigid by bone name.  Do not
            // use a pivot-distance test here: bed sides and rear quarters are
            // authored around an outer pivot.  The bone hook is populated by
            // MeshGpuCacheMixin for every CPU bone draw; if it is genuinely
            // unknown, skinning is safer than leaving a rear fender hole.
            if (BamngRenderHooks.rigidBone()) {
                this.delegate.vertex((float) x, (float) y, (float) z);
                this.lastDent = 0.0;
                this.lastFront = 0.0;
                return this;
            }

            // Reverse impact: move the whole rear body/chassis assembly
            // forward, not just the tailgate.  The authored tailgate pivot is
            // z=2.245; the bed sides, rear quarters, bumper, lamps and rear
            // frame rails all sit between z=0.42 and z=2.37 and must share
            // the same smooth fold or the rear looks unskinned.
            if (this.rearDepth > 0.005 && rz > 0.15) {
                double rear = Math.min(1.0, (rz - 0.15) / 2.22);
                double f = rear * rear * (3.0 - 2.0 * rear);
                lx = rx * (1.0 - this.rearDepth * 0.040 * f);
                ly = ry - this.rearDepth * 0.320 * f;
                lz = rz - this.rearDepth * f;
                BamngRenderState.noteVisualDelta(lx - rx, ly - ry, lz - rz);
                // rx/ly/lz are authored model space (they include the
                // bone pivot).  The matrix already contains that pivot
                // translation, so subtract it before transforming back;
                // otherwise the part is translated twice.
                this.local.set((float) (lx - px), (float) (ly - py), (float) (lz - pz));
                this.matrix.transformPosition(this.local);
                this.delegate.vertex(this.local.x, this.local.y, this.local.z);
                this.lastDent = 0.0;
                this.lastFront = 0.0;
                return this;
            }

            // The middle of the truck is not authored as a separate front
            // clip.  It must still go through skin.apply: that is where the
            // engine block, spare tire, interior trim and cab floor bind to
            // the nearest lattice nodes.  The old early return left every
            // vertex at rz >= -1.02 raw when the front was the only active
            // depth, which is exactly why the engine internals never moved.
            // Keep the no-field early return only when nothing is active.
            if (this.frontDepth <= 0.005 && this.bodyDepth <= 0.005
                    && this.rearDepth <= 0.005) {
                this.delegate.vertex((float) x, (float) y, (float) z);
                this.lastDent = 0.0;
                this.lastFront = 0.0;
                return this;
            }

            dent = this.frontDepth;
            if (this.skin.hasFrontField() || this.bodyDepth > 0.005
                    || this.frontDepth > 0.005 || this.rearDepth > 0.005) {
                double wx = this.skin.originX() + rx * this.cos + rz * this.sin;
                double wy = this.skin.originY() + ry;
                double wz = this.skin.originZ() - rx * this.sin + rz * this.cos;
                this.point.set((float) wx, (float) wy, (float) wz);
                this.skin.apply(this.point, BamngRenderState.Skin.vertexKey(rx, ry, rz));
                double dx = this.point.x - this.skin.originX();
                double dz = this.point.z - this.skin.originZ();
                lx = dx * this.cos - dz * this.sin;
                ly = this.point.y - this.skin.originY();
                lz = dx * this.sin + dz * this.cos;
                // Only the part of the skin travel that actually moved the
                // vertex rearward (+Z in authored model space) consumes the
                // visual crush budget.  Side sway must not cancel the fold.
                if (lz > rz) {
                    dent = Math.max(0.0, dent - (lz - rz));
                }
            }

            // Authored front crush.  The T15 front mesh runs along -Z: the
            // bumper sits near z=-2.4 blocks and the cab side near z=-1.0.
            // Fold the whole authored front clip (chassis rails included)
            // rearward (+Z); the cab side stays rigid; f is a smoothstep so
            // the crease is not a hard step.
            if (dent > 0.005) {
                front = (-rz - 0.98) / 1.45;
                if (front > 0.0) {
                    front = Math.min(1.0, front);
                    double f = front * front * (3.0 - 2.0 * front);
                    // Derivative of the smoothstep, used to bend the normal.
                    double df = 6.0 * front * (1.0 - front);
                    a = dent * 0.22 * df / 1.45;
                    b = 1.0 - dent * df / 1.45;
                    if (b < 0.20) {
                        b = 0.20;
                    }
                    // The old sawtooth made a hard crease every 0.20 m.  That is
                // exactly what read as a knife cut at the seam.  Use a smooth
                // sinusoidal buckle instead: the fold still has ridges, but
                // the surface and normals remain continuous.
                double wave = buckleWave(rz, ry);
                    lz += dent * f * (0.86 + 0.14 * wave);
                    // A real front clip does not only translate rearward:
                    // the nose drops and the outer corners pinch in.  The old
                    // 0.22 vertical / 0.040 lateral factors were so small that
                    // a 0.36 m dent still read as a rigid slab pushed back.
                    ly -= dent * 0.30 * f * (0.90 + 0.10 * wave);
                    lx *= 1.0 - dent * 0.085 * f * (0.90 + 0.10 * wave);
                }
            }
            this.lastDent = front > 0.0 ? dent : 0.0;
            this.lastFront = front;
            this.lastA = a;
            this.lastB = b;
            double vx = lx - rx;
            double vy = ly - ry;
            double vz = lz - rz;
            BamngRenderState.noteVisualDelta(vx, vy, vz);

            // Transform the corrected model-space position back through the
            // same part matrix Vextris would have used.  The pivot is part of
            // that matrix already: subtract it here so the hood does not
            // receive a second pivot translation and float away from the cab.
            this.local.set((float) (lx - px), (float) (ly - py), (float) (lz - pz));
            this.matrix.transformPosition(this.local);
            this.delegate.vertex(this.local.x, this.local.y, this.local.z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            this.delegate.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.delegate.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            this.delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            this.delegate.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            if (!this.vertexPending || this.lastDent <= 0.005 || this.lastFront <= 0.0
                    || !this.normalInvertible) {
                this.delegate.normal(x, y, z);
                return this;
            }
            this.vertexPending = false;
            // Vextris already transformed the normal through the part matrix.
            // Undo that, apply the inverse-transpose of the authored fold, then
            // transform through the same normal matrix again.
            this.normalLocal.set(x, y, z);
            this.normalInverse.transform(this.normalLocal);
            double nx = this.normalLocal.x;
            double ny = this.normalLocal.y;
            double nz = (this.normalLocal.z - this.lastA * this.normalLocal.y) / this.lastB;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0e-9) {
                this.delegate.normal(x, y, z);
                return this;
            }
            this.normalLocal.set((float) (nx / len), (float) (ny / len), (float) (nz / len));
            this.normalMatrix.transform(this.normalLocal);
            double nlen = this.normalLocal.length();
            if (nlen < 1.0e-9) {
                this.delegate.normal(x, y, z);
                return this;
            }
            this.normalLocal.mul((float) (1.0 / nlen));
            this.delegate.normal(this.normalLocal.x, this.normalLocal.y, this.normalLocal.z);
            return this;
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            this.delegate.defaultColor(red, green, blue, alpha);
        }

        @Override
        public void unsetDefaultColor() {
            this.delegate.unsetDefaultColor();
        }

        @Override
        public void endVertex() {
            this.vertexPending = false;
            this.delegate.endVertex();
        }

        /**
         * Sharp triangular wave along the authored nose.  Unlike sin(), its
         * derivative flips at every fold line, so the mesh shows a crease
         * instead of a soft ripple.  The height term tilts the folds slightly
         * so left and right sides do not buckle at the same z.
         */
        private static double buckleWave(double z, double y) {
            double u = (-z - 1.02) / 0.20 + 0.12 * (y - 0.90);
            // Smooth, not a floor/abs sawtooth.  A one-metre period gives
            // distinct folds without a derivative jump at every seam.
            return 0.5 * Math.sin(u * Math.PI * 2.0);
        }
    }
}
