package net.portalmod.common.sorted.portal;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.particles.RedstoneParticleData;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.util.math.vector.*;
import net.minecraft.block.BlockState;
import net.portalmod.PMState;
import net.portalmod.PortalMod;
import net.portalmod.client.render.PortalCamera;
import net.portalmod.client.render.Shader;
import net.portalmod.common.sorted.portalgun.PortalGun;
import net.portalmod.common.sorted.trigger.TriggerTER;
import net.portalmod.core.config.PortalModConfigManager;
import net.portalmod.core.init.ShaderInit;
import net.portalmod.core.math.Mat4;
import net.portalmod.core.math.Vec3;
import net.portalmod.core.util.ModUtil;
import net.portalmod.core.util.RenderUtil;
import net.portalmod.core.util.VertexRenderer;
import net.portalmod.mixins.accessors.LevelRendererAccessor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;

import javax.annotation.Nullable;
import java.awt.*;
import java.util.*;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL32.GL_DEPTH_CLAMP;

public class PortalRenderer {
    private static PortalRenderer instance;

    private static final VertexRenderer portalMesh = new VertexRenderer(DefaultVertexFormats.POSITION_TEX, GL_QUADS);
    private static final VertexRenderer screenQuad = new VertexRenderer(DefaultVertexFormats.POSITION_TEX, GL_QUADS);
    private static final VertexRenderer blitQuad = new VertexRenderer(DefaultVertexFormats.POSITION_TEX, GL_QUADS);

    private static final Framebuffer tempFBO = new Framebuffer(
            Minecraft.getInstance().getWindow().getWidth(),
            Minecraft.getInstance().getWindow().getHeight(),
            true,
            Minecraft.ON_OSX
    );

    // todo create a state class and dump everything in idk
    public int recursion = 0;
    public ActiveRenderInfo currentCamera;
    public int renderedPortals = 0;
    public boolean currentlyRenderingPortals = false;
    private boolean fabulousGraphics = false;
    private final Deque<PortalEntity> portalStack = new ArrayDeque<>();
    public MatrixStack clipMatrix = new MatrixStack();
    private final float[] projectionBuffer = new float[16];
    public Vec3 clearColor = new Vec3(0);

    public List<PortalEntity> outlineRenderingPortalChain;
    public final Deque<PortalEntity> portalChain = new ArrayDeque<>();

    /**
     * While &gt; 0 we are inside a nested portal render in which we temporarily lowered
     * {@code LevelRenderer.lastViewDistance} to reduce the chunk-BFS grid bounds in
     * {@code setupRender}. The mixin in {@link net.portalmod.mixins.renderer.LevelRendererMixin}
     * uses this value to mask the {@code options.renderDistance != lastViewDistance}
     * guard so that the intentional mismatch does not trigger {@code allChanged()} and
     * rebuild every chunk.
     *
     * <p>IMPORTANT: {@code options.renderDistance} is never mutated by this path, so fog,
     * F3, entity view scale, the options GUI, etc. always see the player's real setting.
     */
    public int nestedBfsDistanceOverride = -1;

    /**
     * Screen-space (NDC) bounding rect of the portal we are currently rendering
     * <em>through</em>, stored as {@code {xMin, yMin, xMax, yMax}} in NDC coords
     * ({@code [-1,1]}). At the outer frame it is the full screen; each time we
     * recurse into a portal, we intersect this rect with the portal's own projected
     * silhouette and push it, then restore on exit.
     *
     * <p>This mirrors Source's portal-edge frustum clipping: even if the child
     * portal is inside the camera's full frustum, it gets culled when it projects
     * outside the screen-space region already clipped by the parent portal's
     * silhouette (equivalently: outside the stencil-mask pixels it could draw into).
     * Cheap 2D rect-vs-rect, but kills the exponential fan-out when multiple portal
     * pairs are visible.
     */
    private final float[] currentParentNdcRect = new float[]{-1f, -1f, 1f, 1f};
    private final Deque<float[]> parentNdcRectStack = new ArrayDeque<>();
    public int portalsCulledByNdcRect = 0;
    /** Per outer frame: skipped full stencil/mask/border — all four opening-corner rays hit opaque blocks before the corner. */
    public int portalsStencilSkippedOccludedRays = 0;

    private static final double RAY_CORNER_EPSILON = 0.08;
    private static final long OCCLUSION_CACHE_TICKS = 2L;
    private static final long OCCLUSION_CACHE_MAX_TICKS = 6L;
    private static final double OCCLUSION_CACHE_CAMERA_POS_DELTA_SQR = 0.04;
    private static final double OCCLUSION_CACHE_CAMERA_POS_DELTA_SQR_MAX = 0.36;
    private static final float OCCLUSION_CACHE_CAMERA_ROT_DELTA = 1.5F;
    private static final float OCCLUSION_CACHE_CAMERA_ROT_DELTA_MAX = 8.0F;
    private static final float NESTED_PORTAL_MIN_COVERAGE = 0.01F;
    private static final double FAST_CAMERA_POS_DELTA_SQR = 0.04;
    private static final float FAST_CAMERA_ROT_DELTA = 6.0F;
    private static final float FAST_CAMERA_HARD_CLAMP_START = 0.55F;
    private static final int FULL_DETAIL_PORTAL_BUDGET = 2;
    private static final int MEDIUM_DETAIL_PORTAL_BUDGET = 4;
    private static final float TEMPORAL_VISIBLE_IMPORTANCE_BONUS = 0.08F;
    private static final float TEMPORAL_VISIBLE_STREAK_BONUS = 0.02F;
    private static final float TEMPORAL_CULLED_IMPORTANCE_PENALTY = 0.03F;
    /** Cached {@code Optional.of(VoxelShapes.empty())} for the portal-sight shape override hot path. */
    private static final Optional<VoxelShape> EMPTY_SHAPE_OVERRIDE = Optional.of(VoxelShapes.empty());
    private final Map<UUID, PortalOcclusionCacheEntry> portalOcclusionCache = new HashMap<>();
    private final Map<UUID, PortalProjectionCacheEntry> portalProjectionCache = new HashMap<>();
    private final Map<UUID, PortalFramePlanEntry> portalFramePlan = new HashMap<>();
    private final Map<UUID, PortalTemporalVisibilityEntry> portalTemporalVisibility = new HashMap<>();
    private final Map<String, PortalBorderTextureInfo> portalBorderTextureCache = new HashMap<>();
    private final List<PortalEntity> framePortalEntities = new ArrayList<>();
    private final List<PortalEntity> topLevelPortalCandidates = new ArrayList<>();
    private final Deque<ObjectList<WorldRenderer.LocalRenderInformationContainer>> renderChunkSnapshotPool = new ArrayDeque<>();
    private final Deque<NestedRenderSettings> nestedRenderSettingsStack = new ArrayDeque<>();
    private ClientWorld framePortalLevel;
    private ClientWorld occlusionCacheLevel;
    private long portalProjectionFrameId;
    private Vector3d lastOuterCameraPos;
    private float lastOuterCameraXRot;
    private float lastOuterCameraYRot;
    private float fastCameraMotionFactor;
    private boolean mainFramebufferStencilFailed;
    private boolean tempFramebufferStencilFailed;

    // --- profiler ---
    public static final Profile PROFILE = new Profile();

    /**
     * Lightweight per-frame profiler for the portal renderer. Use {@link #push}/{@link #pop}
     * to measure nested regions. Totals are snapshotted each frame into
     * {@code ClientEvents.debugStrings} and drawn on the HUD.
     *
     * <p>Each label accumulates total nanoseconds, total invocations, and max single-call
     * nanoseconds. Enable with JVM flag {@code -Dportalmod.portalProfiler=true}. Off by default.
     */
    public static final class Profile {
        public boolean enabled = false;
        public int topN = 12;

        private static final class Entry {
            long totalNs;
            long maxNs;
            long calls;
        }

        private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        private final Deque<String> labelStack = new ArrayDeque<>();
        private final Deque<Long> startStack = new ArrayDeque<>();

        public int portalsVisited;
        public int portalsCulled;
        public int portalsNested;
        public int maxRecursionReached;

        public void push(String label) {
            if(!enabled) return;
            labelStack.push(label);
            startStack.push(System.nanoTime());
        }

        public void pop() {
            if(!enabled) return;
            if(labelStack.isEmpty() || startStack.isEmpty()) return;
            long elapsed = System.nanoTime() - startStack.pop();
            String label = labelStack.pop();
            Entry e = entries.get(label);
            if(e == null) {
                e = new Entry();
                entries.put(label, e);
            }
            e.totalNs += elapsed;
            e.calls++;
            if(elapsed > e.maxNs)
                e.maxNs = elapsed;
        }

        public void reset() {
            entries.clear();
            labelStack.clear();
            startStack.clear();
            portalsVisited = 0;
            portalsCulled = 0;
            portalsNested = 0;
            maxRecursionReached = 0;
        }

        private static String fmt(long ns) {
            double ms = ns / 1_000_000.0;
            return String.format(Locale.ROOT, "%6.2fms", ms);
        }

        public void snapshotInto(List<String> out) {
            if(!enabled) return;
            Entry frame = entries.get("frame");
            long frameNs = frame == null ? 1 : Math.max(1, frame.totalNs);

            PortalRenderer pr = PortalRenderer.getInstance();
            out.add(String.format(Locale.ROOT,
                    "PortalProfile %s | portals v=%d c=%d(ndc=%d) nested=%d occ=%d depth=%d/max%d",
                    fmt(frameNs), portalsVisited, portalsCulled, pr.portalsCulledByNdcRect,
                    portalsNested, pr.portalsStencilSkippedOccludedRays, maxRecursionReached, PortalModConfigManager.RECURSION.get()));

            List<Map.Entry<String, Entry>> sorted = new ArrayList<>(entries.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue().totalNs, a.getValue().totalNs));

            int shown = 0;
            for(Map.Entry<String, Entry> kv : sorted) {
                if(shown++ >= topN) break;
                Entry e = kv.getValue();
                double pct = (e.totalNs * 100.0) / frameNs;
                out.add(String.format(Locale.ROOT,
                        "%-28s %s x%-3d max %s %5.1f%%",
                        kv.getKey(), fmt(e.totalNs), e.calls, fmt(e.maxNs), pct));
            }
        }
    }

    private static final class PortalOcclusionCacheEntry {
        long gameTime;
        Vector3d cameraPos;
        float xRot;
        float yRot;
        boolean fullyOccluded;
    }

    private static final class PortalProjectionCacheEntry {
        long frameId;
        Vector3d cameraPos;
        float xRot;
        float yRot;
        @Nullable
        float[] rect;
    }

    private static final class PortalBorderTextureInfo {
        final ResourceLocation location;
        final int frameCount;

        private PortalBorderTextureInfo(ResourceLocation location, int frameCount) {
            this.location = location;
            this.frameCount = frameCount;
        }
    }

    private static final class PortalFramePlanEntry {
        float coverage;
        float importance;
        int rank;
        boolean topLevelCandidate;
    }

    private static final class PortalTemporalVisibilityEntry {
        boolean visibleLastFrame;
        int visibleStreak;
        int culledStreak;
    }

    public static final class NestedRenderSettings {
        final boolean renderEntities;
        final boolean renderBlockEntities;
        final boolean renderTranslucent;
        final boolean renderParticles;
        final boolean renderSky;
        final boolean renderClouds;
        final boolean renderWeather;
        final boolean renderWorldBounds;
        final boolean compileChunks;

        private NestedRenderSettings(boolean renderEntities, boolean renderBlockEntities,
                                     boolean renderTranslucent, boolean renderParticles,
                                     boolean renderSky, boolean renderClouds, boolean renderWeather,
                                     boolean renderWorldBounds,
                                     boolean compileChunks) {
            this.renderEntities = renderEntities;
            this.renderBlockEntities = renderBlockEntities;
            this.renderTranslucent = renderTranslucent;
            this.renderParticles = renderParticles;
            this.renderSky = renderSky;
            this.renderClouds = renderClouds;
            this.renderWeather = renderWeather;
            this.renderWorldBounds = renderWorldBounds;
            this.compileChunks = compileChunks;
        }

        private static NestedRenderSettings full() {
            return new NestedRenderSettings(true, true, true, true, true, true, true, true, true);
        }

        private NestedRenderSettings mergeWithParent(@Nullable NestedRenderSettings parent) {
            if(parent == null)
                return this;
            return new NestedRenderSettings(
                    parent.renderEntities && this.renderEntities,
                    parent.renderBlockEntities && this.renderBlockEntities,
                    parent.renderTranslucent && this.renderTranslucent,
                    parent.renderParticles && this.renderParticles,
                    parent.renderSky && this.renderSky,
                    parent.renderClouds && this.renderClouds,
                    parent.renderWeather && this.renderWeather,
                    parent.renderWorldBounds && this.renderWorldBounds,
                    parent.compileChunks && this.compileChunks
            );
        }
    }

    /** {@code -Dportalmod.portalProfiler=true} */
    public static boolean portalProfilerEnabled() {
        return Boolean.getBoolean("portalmod.portalProfiler");
    }

    public List<PortalEntity> getFramePortalEntities(ClientWorld level) {
        if(framePortalLevel != level || framePortalEntities.isEmpty())
            refreshFramePortalEntities(level);
        return framePortalEntities;
    }

    @Nullable
    public NestedRenderSettings getCurrentNestedRenderSettings() {
        return nestedRenderSettingsStack.peekLast();
    }

    public boolean shouldRenderNestedEntities() {
        NestedRenderSettings settings = getCurrentNestedRenderSettings();
        return settings == null || settings.renderEntities;
    }

    public boolean shouldRenderNestedBlockEntities() {
        NestedRenderSettings settings = getCurrentNestedRenderSettings();
        return settings == null || settings.renderBlockEntities;
    }

    public boolean shouldRenderNestedTranslucent() {
        NestedRenderSettings settings = getCurrentNestedRenderSettings();
        return settings == null || settings.renderTranslucent;
    }

    public boolean shouldRenderNestedParticles() {
        NestedRenderSettings settings = getCurrentNestedRenderSettings();
        return settings == null || settings.renderParticles;
    }

    public boolean shouldRenderNestedSky() {
        NestedRenderSettings settings = getCurrentNestedRenderSettings();
        return settings == null || settings.renderSky;
    }

    public boolean shouldRenderNestedClouds() {
        NestedRenderSettings settings = getCurrentNestedRenderSettings();
        return settings == null || settings.renderClouds;
    }

    public boolean shouldRenderNestedWeather() {
        NestedRenderSettings settings = getCurrentNestedRenderSettings();
        return settings == null || settings.renderWeather;
    }

    public boolean shouldRenderNestedWorldBounds() {
        NestedRenderSettings settings = getCurrentNestedRenderSettings();
        return settings == null || settings.renderWorldBounds;
    }

    public boolean shouldCompileNestedChunks() {
        NestedRenderSettings settings = getCurrentNestedRenderSettings();
        return settings == null || settings.compileChunks;
    }

    private void refreshFramePortalEntities(ClientWorld level) {
        framePortalLevel = level;
        framePortalEntities.clear();
        for(Entity entity : level.entitiesForRendering()) {
            if(entity instanceof PortalEntity)
                framePortalEntities.add((PortalEntity)entity);
        }
    }

    private static double distanceToCameraSqr(PortalEntity portal, Vec3 cameraPos) {
        Vector3d portalPos = portal.position();
        double dx = cameraPos.x - portalPos.x;
        double dy = cameraPos.y - portalPos.y;
        double dz = cameraPos.z - portalPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isPortalFacingAwayFromCamera(PortalEntity portal, Vec3 cameraPos) {
        if(portal.getDirection().getAxis().isVertical())
            return false;

        Vector3d portalPos = portal.position();
        double dx = cameraPos.x - portalPos.x;
        double dy = cameraPos.y - portalPos.y;
        double dz = cameraPos.z - portalPos.z;
        double distanceSqr = dx * dx + dy * dy + dz * dz;
        if(distanceSqr <= 1.0D)
            return false;

        Vector3d portalNormal = Vector3d.atLowerCornerOf(portal.getDirection().getNormal());
        double invDistance = 1.0D / Math.sqrt(distanceSqr);
        return (dx * invDistance) * portalNormal.x + (dy * invDistance) * portalNormal.y + (dz * invDistance) * portalNormal.z < 0.0D;
    }

    private boolean passesCheapPortalViewTests(PortalEntity portal, Vec3 cameraPos, ClippingHelper clippingHelper) {
        double distanceSqr = distanceToCameraSqr(portal, cameraPos);
        if(isPortalFacingAwayFromCamera(portal, cameraPos))
            return false;
        return distanceSqr <= 1.0D || clippingHelper.isVisible(portal.getBoundingBox());
    }

    private void buildPortalFramePlan(ActiveRenderInfo camera, ClippingHelper clippingHelper, Matrix4f projectionMatrix) {
        portalFramePlan.clear();
        topLevelPortalCandidates.clear();
        Vec3 cameraPos = new Vec3(camera.getPosition());
        for(PortalEntity portal : framePortalEntities) {
            boolean passesCheapTests = passesCheapPortalViewTests(portal, cameraPos, clippingHelper);
            float[] rect = null;
            float coverage = 0.0F;
            float centerBias = 0.0F;
            if(passesCheapTests) {
                rect = getPortalNdcRectCached(portal, camera, projectionMatrix);
                coverage = portalCoverageFromRect(rect);
                if(rect != null) {
                    float cx = (rect[0] + rect[2]) * 0.5F;
                    float cy = (rect[1] + rect[3]) * 0.5F;
                    float centerDistance = MathHelper.clamp((float)Math.sqrt(cx * cx + cy * cy), 0.0F, 1.5F);
                    centerBias = 1.0F - MathHelper.clamp(centerDistance / 1.5F, 0.0F, 1.0F);
                }
            }

            PortalFramePlanEntry entry = new PortalFramePlanEntry();
            entry.coverage = coverage;
            entry.importance = coverage * 0.85F + centerBias * 0.15F;
            PortalTemporalVisibilityEntry temporal = portalTemporalVisibility.get(portal.getUUID());
            if(temporal != null) {
                if(rect != null && temporal.visibleLastFrame) {
                    entry.importance += TEMPORAL_VISIBLE_IMPORTANCE_BONUS;
                    entry.importance += Math.min(temporal.visibleStreak, 3) * TEMPORAL_VISIBLE_STREAK_BONUS;
                }
                if(!temporal.visibleLastFrame && temporal.culledStreak > 0)
                    entry.importance -= Math.min(temporal.culledStreak, 3) * TEMPORAL_CULLED_IMPORTANCE_PENALTY;
            }
            entry.importance = MathHelper.clamp(entry.importance, 0.0F, 1.5F);
            entry.topLevelCandidate = passesCheapTests && isTopLevelPortalCandidate(portal, cameraPos, clippingHelper, rect);
            portalFramePlan.put(portal.getUUID(), entry);
        }

        framePortalEntities.sort((a, b) -> {
            PortalFramePlanEntry planA = portalFramePlan.get(a.getUUID());
            PortalFramePlanEntry planB = portalFramePlan.get(b.getUUID());
            return Float.compare(planB == null ? 0.0F : planB.importance, planA == null ? 0.0F : planA.importance);
        });

        for(int i = 0; i < framePortalEntities.size(); i++) {
            PortalFramePlanEntry entry = portalFramePlan.get(framePortalEntities.get(i).getUUID());
            if(entry != null) {
                entry.rank = i;
                if(entry.topLevelCandidate)
                    topLevelPortalCandidates.add(framePortalEntities.get(i));
            }
        }
    }

    private boolean isTopLevelPortalCandidate(PortalEntity portal, Vec3 cameraPos, ClippingHelper clippingHelper, @Nullable float[] rect) {
        double distanceSqr = distanceToCameraSqr(portal, cameraPos);

        if(distanceSqr > 1.0D && !clippingHelper.isVisible(portal.getBoundingBox()))
            return false;

        if(distanceSqr > 2.25D && rect != null && !rectsIntersect(rect, currentParentNdcRect))
            return false;

        return true;
    }

    private void pruneOcclusionCache(ClientWorld level) {
        if(occlusionCacheLevel != level) {
            portalOcclusionCache.clear();
            occlusionCacheLevel = level;
        }

        long gameTime = level.getGameTime();
        portalOcclusionCache.values().removeIf(entry -> gameTime - entry.gameTime > OCCLUSION_CACHE_MAX_TICKS);
    }

    private void pruneTemporalVisibilityState() {
        HashSet<UUID> activePortalIds = new HashSet<>();
        for(PortalEntity portal : framePortalEntities)
            activePortalIds.add(portal.getUUID());
        portalTemporalVisibility.entrySet().removeIf(entry -> !activePortalIds.contains(entry.getKey()));
    }

    private void markPortalRendered(PortalEntity portal) {
        PortalTemporalVisibilityEntry entry = portalTemporalVisibility.computeIfAbsent(portal.getUUID(), ignored -> new PortalTemporalVisibilityEntry());
        entry.visibleLastFrame = true;
        entry.visibleStreak = Math.min(entry.visibleStreak + 1, 8);
        entry.culledStreak = 0;
    }

    private void markPortalCulled(PortalEntity portal) {
        PortalTemporalVisibilityEntry entry = portalTemporalVisibility.computeIfAbsent(portal.getUUID(), ignored -> new PortalTemporalVisibilityEntry());
        entry.visibleLastFrame = false;
        entry.visibleStreak = 0;
        entry.culledStreak = Math.min(entry.culledStreak + 1, 8);
    }

    private void updateFastCameraMotion(ActiveRenderInfo camera) {
        Vector3d currentPos = camera.getPosition();
        if(lastOuterCameraPos == null) {
            lastOuterCameraPos = currentPos;
            lastOuterCameraXRot = camera.getXRot();
            lastOuterCameraYRot = camera.getYRot();
            fastCameraMotionFactor = 0.0F;
            return;
        }

        double posFactor = MathHelper.clamp(
                lastOuterCameraPos.distanceToSqr(currentPos) / FAST_CAMERA_POS_DELTA_SQR,
                0.0,
                1.0);
        double xRotFactor = MathHelper.clamp(
                Math.abs(MathHelper.wrapDegrees(lastOuterCameraXRot - camera.getXRot())) / FAST_CAMERA_ROT_DELTA,
                0.0F,
                1.0F);
        double yRotFactor = MathHelper.clamp(
                Math.abs(MathHelper.wrapDegrees(lastOuterCameraYRot - camera.getYRot())) / FAST_CAMERA_ROT_DELTA,
                0.0F,
                1.0F);

        fastCameraMotionFactor = (float)Math.max(posFactor, Math.max(xRotFactor, yRotFactor));
        lastOuterCameraPos = currentPos;
        lastOuterCameraXRot = camera.getXRot();
        lastOuterCameraYRot = camera.getYRot();
    }

    private NestedRenderSettings buildNestedRenderSettings(PortalEntity portal, float portalCoverage) {
        PortalFramePlanEntry plan = portalFramePlan.get(portal.getUUID());
        int rank = plan == null ? Integer.MAX_VALUE : plan.rank;
        float coverage = plan == null ? portalCoverage : plan.coverage;
        boolean fastMotion = fastCameraMotionFactor >= FAST_CAMERA_HARD_CLAMP_START;
        int fullDetailBudget = fastMotion ? 1 : FULL_DETAIL_PORTAL_BUDGET;
        int mediumDetailBudget = fastMotion ? 2 : MEDIUM_DETAIL_PORTAL_BUDGET;
        boolean topPortal = rank < fullDetailBudget;
        boolean mediumPortal = rank < mediumDetailBudget;
        boolean largePortal = coverage >= 0.18F;
        boolean mediumCoverage = coverage >= 0.08F;
        boolean skyCoverage = coverage >= 0.14F;

        boolean renderEntities = (topPortal && !fastMotion) || largePortal;
        boolean renderBlockEntities = topPortal && largePortal && fastCameraMotionFactor < 0.45F;
        boolean renderTranslucent = topPortal || (mediumPortal && mediumCoverage && fastCameraMotionFactor < 0.75F);
        boolean renderParticles = topPortal && coverage >= 0.2F && fastCameraMotionFactor < 0.35F;
        boolean renderSky = topPortal && skyCoverage && fastCameraMotionFactor < 0.55F;
        boolean renderClouds = renderSky && coverage >= 0.22F && fastCameraMotionFactor < 0.25F;
        boolean renderWeather = topPortal && coverage >= 0.22F && fastCameraMotionFactor < 0.35F;
        boolean renderWorldBounds = topPortal && coverage >= 0.3F && fastCameraMotionFactor < 0.25F;
        boolean compileChunks = topPortal && coverage >= 0.16F && fastCameraMotionFactor < 0.2F;

        if(recursion >= 2) {
            renderEntities &= coverage >= 0.12F && fastCameraMotionFactor < 0.7F;
            renderBlockEntities = false;
            renderTranslucent &= coverage >= 0.12F;
            renderParticles = false;
            renderSky = false;
            renderClouds = false;
            renderWeather = false;
            renderWorldBounds = false;
            compileChunks = false;
        }

        return new NestedRenderSettings(
                renderEntities,
                renderBlockEntities,
                renderTranslucent,
                renderParticles,
                renderSky,
                renderClouds,
                renderWeather,
                renderWorldBounds,
                compileChunks
        ).mergeWithParent(getCurrentNestedRenderSettings());
    }

    private ObjectList<WorldRenderer.LocalRenderInformationContainer> acquireRenderChunkSnapshot() {
        ObjectList<WorldRenderer.LocalRenderInformationContainer> snapshot = renderChunkSnapshotPool.pollFirst();
        if(snapshot == null)
            snapshot = new ObjectArrayList<>();
        else
            snapshot.clear();
        return snapshot;
    }

    private void releaseRenderChunkSnapshot(ObjectList<WorldRenderer.LocalRenderInformationContainer> snapshot) {
        snapshot.clear();
        if(renderChunkSnapshotPool.size() < 8)
            renderChunkSnapshotPool.push(snapshot);
    }

    static {
        portalMesh.reset();
        portalMesh.data(bufferBuilder -> {
            bufferBuilder.vertex(0, 0, 0).uv(0, 0).endVertex();
            bufferBuilder.vertex(1, 0, 0).uv(1, 0).endVertex();
            bufferBuilder.vertex(1, 2, 0).uv(1, 1).endVertex();
            bufferBuilder.vertex(0, 2, 0).uv(0, 1).endVertex();
        });

        screenQuad.reset();
        screenQuad.data(bufferBuilder -> {
            bufferBuilder.vertex(-1, -1, 1).uv(0, 0).endVertex();
            bufferBuilder.vertex( 1, -1, 1).uv(1, 0).endVertex();
            bufferBuilder.vertex( 1,  1, 1).uv(1, 1).endVertex();
            bufferBuilder.vertex(-1,  1, 1).uv(0, 1).endVertex();
        });

        blitQuad.reset();
        blitQuad.data(bufferBuilder -> {
            bufferBuilder.vertex(-1, -1, 0).uv(0, 0).endVertex();
            bufferBuilder.vertex( 1, -1, 0).uv(1, 0).endVertex();
            bufferBuilder.vertex( 1,  1, 0).uv(1, 1).endVertex();
            bufferBuilder.vertex(-1,  1, 0).uv(0, 1).endVertex();
        });
    }

    private PortalRenderer() { }

    public static PortalRenderer getInstance() {
        if(instance == null)
            instance = new PortalRenderer();
        return instance;
    }

    private void renderMask(PortalEntity portal, Matrix4f model, Matrix4f view, Matrix4f projectionMatrix) {
        ShaderInit.PORTAL_MASK.get().bind()
                .setMatrix("model", model)
                .setMatrix("view", view)
                .setMatrix("projection", projectionMatrix);

        this.setupShaderClipPlane(ShaderInit.PORTAL_MASK.get(), this.portalStack.peekFirst());

        int age = portal.getAge();
        boolean spawning = age < 4;

        String path = "textures/portal/"
                + "mask"
                + (spawning ? "_spawning" + age : "")
                + ".png";

        glEnable(GL_TEXTURE_2D);
        RenderSystem.activeTexture(GL_TEXTURE0);
        Minecraft.getInstance().textureManager.bind(new ResourceLocation(PortalMod.MODID, path));

        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();

        GL11.glEnable(GL_ALPHA_TEST);
        glEnable(GL_DEPTH_CLAMP);
        RenderSystem.depthMask(false);
        portalMesh.render();
        RenderSystem.depthMask(true);
        glDisable(GL_DEPTH_CLAMP);
        GL11.glDisable(GL_ALPHA_TEST);

        RenderSystem.colorMask(true, true, true, true);

        RenderSystem.bindTexture(0);
        unbindBuffer();
        ShaderInit.PORTAL_MASK.get().unbind();
    }

    private void renderBackground() {
        ShaderInit.COLOR.get().bind().setFloat("color", (float)clearColor.x, (float)clearColor.y, (float)clearColor.z, 1);

        RenderSystem.depthFunc(GL_ALWAYS);
        screenQuad.render();
        RenderSystem.depthFunc(GL_LESS);

        RenderSystem.bindTexture(0);
        unbindBuffer();
        ShaderInit.COLOR.get().unbind();
    }

    private void renderDepth(Matrix4f modelView) {
        glUseProgram(0);

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();

        GL11.glEnable(GL_ALPHA_TEST);
        glEnable(GL_DEPTH_CLAMP);
        RenderSystem.depthFunc(GL_ALWAYS);
        RenderSystem.colorMask(false, false, false, false);
        portalMesh.render(new Mat4(modelView));
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthFunc(GL_LESS);
        glDisable(GL_DEPTH_CLAMP);
        GL11.glDisable(GL_ALPHA_TEST);

        RenderSystem.bindTexture(0);
        unbindBuffer();
    }

    private void renderBorder(PortalEntity portal, Matrix4f model, Matrix4f view, Matrix4f projectionMatrix) {
        Minecraft mc = Minecraft.getInstance();

        if(mc.player == null)
            return;

        int ticks = mc.player.tickCount;
        int age = portal.getAge();
        boolean open = portal.isOpen() && recursion <= PortalModConfigManager.RECURSION.get();
        boolean spawning = age < 4;

        String path = "textures/portal/"
                + (open ? "open_" : "closed_")
                + portal.getColor()
                + (spawning ? "_spawning" : "")
                + ".png";
        PortalBorderTextureInfo textureInfo = getPortalBorderTextureInfo(path);
        if(textureInfo == null)
            return;
        int frameCount = textureInfo.frameCount;

        int frameIndex;
        if(spawning) {
            frameIndex = age % frameCount;
        } else {
            final int frameTime = 1;
            frameIndex = (ticks / frameTime) % frameCount;
        }

        ShaderInit.PORTAL_FRAME.get().bind()
                .setInt("frameCount", frameCount)
                .setInt("frameIndex", frameIndex)
                .setMatrix("model", model)
                .setMatrix("view", view)
                .setMatrix("projection", projectionMatrix);

        this.setupShaderClipPlane(ShaderInit.PORTAL_FRAME.get(), this.portalStack.peekFirst());

        RenderUtil.bindTexture(ShaderInit.PORTAL_FRAME.get(), "texture", path, 0);

        RenderSystem.enableDepthTest();

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(false);
        portalMesh.render();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        RenderSystem.bindTexture(0);
        unbindBuffer();
        ShaderInit.PORTAL_FRAME.get().unbind();
    }

    @Nullable
    private PortalBorderTextureInfo getPortalBorderTextureInfo(String path) {
        PortalBorderTextureInfo cached = portalBorderTextureCache.get(path);
        if(cached != null)
            return cached;

        ResourceLocation location = new ResourceLocation(PortalMod.MODID, path);
        Optional<Dimension> optionalTextureSize = PortalAnimatedTextureHelper.getTextureSize(location);
        if(!optionalTextureSize.isPresent())
            return null;

        Dimension textureSize = optionalTextureSize.get();
        int frameCount = (int)textureSize.getHeight() / (2 * (int)textureSize.getWidth());
        PortalBorderTextureInfo created = new PortalBorderTextureInfo(location, frameCount);
        portalBorderTextureCache.put(path, created);
        return created;
    }

    private boolean ensureMainFramebufferStencil(Framebuffer mainFBO) {
        if(mainFramebufferStencilFailed)
            return false;
        if(mainFBO.isStencilEnabled())
            return true;

        try {
            mainFBO.enableStencil();
            return mainFBO.isStencilEnabled();
        } catch(RuntimeException exception) {
            mainFramebufferStencilFailed = true;
            PortalMod.LOGGER.error("Failed to enable stencil on the main framebuffer. Disabling portal rendering for this session.", exception);
            return false;
        }
    }

    private boolean ensureTempFramebufferStencil() {
        if(tempFramebufferStencilFailed)
            return false;
        if(tempFBO.isStencilEnabled())
            return true;

        try {
            tempFBO.enableStencil();
            return tempFBO.isStencilEnabled();
        } catch(RuntimeException exception) {
            tempFramebufferStencilFailed = true;
            PortalMod.LOGGER.error("Failed to enable stencil on the portal temp framebuffer. Falling back to non-fabulous portal rendering.", exception);
            return false;
        }
    }

    private boolean shouldUseShaderTransparency(Minecraft mc) {
        if(!Minecraft.useShaderTransparency())
            return false;

        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        if(tempFBO.width != w || tempFBO.height != h)
            tempFBO.resize(w, h, Minecraft.ON_OSX);

        return ensureTempFramebufferStencil();
    }

    public boolean prepareMainFramebufferForPortalRendering(@Nullable ClientWorld level) {
        if(level == null || mainFramebufferStencilFailed)
            return !mainFramebufferStencilFailed;
        if(PortalEntity.getCachedOpenPortals(level).isEmpty())
            return true;
        return ensureMainFramebufferStencil(Minecraft.getInstance().getMainRenderTarget());
    }
    public void renderPortals(ClientWorld level, ActiveRenderInfo camera, ClippingHelper clippingHelper, Matrix4f projectionMatrix, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        Framebuffer mainFBO = mc.getMainRenderTarget();

        boolean isOuter = recursion == 0;
        if(isOuter) {
            PROFILE.enabled = portalProfilerEnabled();
            if(PROFILE.enabled) {
                PROFILE.reset();
                PROFILE.push("frame");
            }
            portalsCulledByNdcRect = 0;
            portalsStencilSkippedOccludedRays = 0;
            parentNdcRectStack.clear();
            nestedRenderSettingsStack.clear();
            currentParentNdcRect[0] = -1f;
            currentParentNdcRect[1] = -1f;
            currentParentNdcRect[2] =  1f;
            currentParentNdcRect[3] =  1f;
            portalProjectionFrameId++;
            portalProjectionCache.clear();
            refreshFramePortalEntities(level);
            pruneOcclusionCache(level);
            pruneTemporalVisibilityState();
            updateFastCameraMotion(camera);
            buildPortalFramePlan(camera, clippingHelper, projectionMatrix);
        }
        PROFILE.push("portals@r" + recursion);

        mc.levelRenderer.renderBuffers.bufferSource().endBatch();

        List<PortalEntity> portalsToRender = recursion == 0 ? topLevelPortalCandidates : getFramePortalEntities(level);
        if(recursion == 0 && portalsToRender.isEmpty()) {
            PROFILE.pop();
            if(isOuter) {
                PROFILE.pop();
                PROFILE.snapshotInto(net.portalmod.core.event.ClientEvents.debugStrings);
            }
            return;
        }

        if(recursion == 0) {
            if(!ensureMainFramebufferStencil(mainFBO)) {
                PROFILE.pop();
                if(isOuter) {
                    PROFILE.pop();
                    PROFILE.snapshotInto(net.portalmod.core.event.ClientEvents.debugStrings);
                }
                return;
            }

            currentlyRenderingPortals = true;
            fabulousGraphics = shouldUseShaderTransparency(mc);

            if(fabulousGraphics) {
                tempFBO.bindWrite(true);
                GL11.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            }

            mainFBO.bindWrite(true);
            GL11.glEnable(GL_STENCIL_TEST);
            RenderSystem.stencilMask(0x80);
            RenderSystem.clear(GL_STENCIL_BUFFER_BIT, false);
        }

        renderedPortals = 0;
        for(PortalEntity portal : portalsToRender) {
            PROFILE.push("renderPortal");
            renderPortal(portal, camera, clippingHelper, projectionMatrix, partialTicks, fabulousGraphics);
            PROFILE.pop();
            renderedPortals++;
        }

        if(recursion == 0) {
            currentlyRenderingPortals = false;
        }

        if(fabulousGraphics) {
            PROFILE.push("fabulousBlit@r" + recursion);
            blitFBOtoFBO(mainFBO, tempFBO);
            tempFBO.copyDepthFrom(mainFBO);
            mainFBO.bindWrite(true);
            PROFILE.pop();
        }

        if(recursion == 0 && PortalModConfigManager.HIGHLIGHTS.get()) {
            PROFILE.push("highlights");
            renderHighlights(camera, projectionMatrix);
            PROFILE.pop();
        }

        if(recursion == 0 && !fabulousGraphics) {
            mainFBO.bindWrite(true);
            GL11.glEnable(GL_STENCIL_TEST);
            RenderSystem.stencilMask(0xFF);
            RenderSystem.clear(GL_STENCIL_BUFFER_BIT, false);
        }

        GL11.glEnable(GL_ALPHA_TEST);
        if(recursion == 0) {
            GL11.glDisable(GL_STENCIL_TEST);
            RenderSystem.stencilMask(~0);
            RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }

        PROFILE.pop();
        if(isOuter) {
            PROFILE.pop();
            PROFILE.snapshotInto(net.portalmod.core.event.ClientEvents.debugStrings);
        }
    }

    private boolean discardPortal(PortalEntity portal, ActiveRenderInfo camera, ClippingHelper clippingHelper, Matrix4f projectionMatrix) {
        Vec3 cameraPos = new Vec3(camera.getPosition());
        double distanceSqr = distanceToCameraSqr(portal, cameraPos);

        // discard portals facing away from camera
        if(isPortalFacingAwayFromCamera(portal, cameraPos))
            return true;

        // discard portals behind parent portal
        if(!portalStack.isEmpty()) {
            PortalEntity parentPortal = portalStack.peek();
            Vec3 portalPos = new Vec3(portal.position());
            Vec3 parentPortalPos = new Vec3(parentPortal.position());
            Vec3 parentPortalNormal = new Vec3(parentPortal.getDirection());
            Vec3 parentPortalPosWithMargin = parentPortalPos.clone().sub(parentPortalNormal.clone().mul(2));
            Vec3 parentPortalToPortal = portalPos.clone().sub(parentPortalPosWithMargin);
            if(parentPortalToPortal.normalize().dot(parentPortalNormal) < 0)
                return true;

            // discard self
            if(portal == portalStack.peek())
                return true;
        }

        // discard portals outside view frustum
        if(distanceSqr > 1.0D && !clippingHelper.isVisible(portal.getBoundingBox()))
            return true;

        // Screen-space portal culling: if the projected portal quad does not intersect
        // the currently reachable screen-space rect, the stencil path cannot possibly
        // contribute pixels. At the outer frame the parent rect is the full screen,
        // and in nested passes it is the already-clipped parent portal silhouette.
        // Skip only when dangerously close to the portal plane, where projection can
        // straddle the near plane and become numerically unstable.
        if(distanceSqr > 2.25D) {
            float[] rect = getPortalNdcRectCached(portal, camera, projectionMatrix);
            if(rect != null && !rectsIntersect(rect, currentParentNdcRect)) {
                portalsCulledByNdcRect++;
                return true;
            }
        }
        return false;
    }

    private static boolean cameraChangedForPortalProjection(PortalProjectionCacheEntry entry, ActiveRenderInfo camera) {
        Vector3d currentPos = camera.getPosition();
        if(entry.cameraPos == null || entry.cameraPos.distanceToSqr(currentPos) > 1.0E-6)
            return true;
        return Math.abs(MathHelper.wrapDegrees(entry.xRot - camera.getXRot())) > 0.01F
                || Math.abs(MathHelper.wrapDegrees(entry.yRot - camera.getYRot())) > 0.01F;
    }

    @Nullable
    private float[] getPortalNdcRectCached(PortalEntity portal, ActiveRenderInfo camera, Matrix4f projectionMatrix) {
        PortalProjectionCacheEntry cached = portalProjectionCache.get(portal.getUUID());
        if(cached != null
                && cached.frameId == portalProjectionFrameId
                && !cameraChangedForPortalProjection(cached, camera)) {
            return cached.rect;
        }

        PortalProjectionCacheEntry updated = new PortalProjectionCacheEntry();
        updated.frameId = portalProjectionFrameId;
        updated.cameraPos = camera.getPosition();
        updated.xRot = camera.getXRot();
        updated.yRot = camera.getYRot();
        updated.rect = computePortalNdcRect(portal, camera, projectionMatrix);
        portalProjectionCache.put(portal.getUUID(), updated);
        return updated.rect;
    }

    /**
     * Project the actual portal surface quad through {@code projection * view} and return
     * an NDC-space bounding rect {@code {xMin, yMin, xMax, yMax}}. Using the portal quad
     * instead of the entity AABB avoids severe overestimation at grazing angles, which
     * would otherwise make tiny side-on portals look much more important than they are.
     *
     * <p>Returns {@code null} when the quad straddles the near plane (any corner has
     * {@code w <= epsilon}), in which case the caller should skip NDC-based culling to
     * avoid false positives.
     */
    private float[] computePortalNdcRect(PortalEntity portal, ActiveRenderInfo camera, Matrix4f projectionMatrix) {
        Matrix4f view = getViewMatrix(camera);
        Matrix4f mvp = projectionMatrix.copy();
        mvp.multiply(view);

        float xMin =  Float.POSITIVE_INFINITY, yMin =  Float.POSITIVE_INFINITY;
        float xMax = Float.NEGATIVE_INFINITY, yMax = Float.NEGATIVE_INFINITY;

        Mat4 model = PortalEntity.setupMatrix(portal.getDirection(), portal.getUpVector(), portal.getPivotPoint());
        Vec3[] corners = new Vec3[]{
                new Vec3(0, 0, 0).transform(model),
                new Vec3(1, 0, 0).transform(model),
                new Vec3(1, 2, 0).transform(model),
                new Vec3(0, 2, 0).transform(model)
        };

        for(Vec3 cornerPos : corners) {
            Vector4f corner = new Vector4f((float)cornerPos.x, (float)cornerPos.y, (float)cornerPos.z, 1f);
            corner.transform(mvp);
            float w = corner.w();
            if(w <= 0.01f)
                return null;
            float nx = corner.x() / w;
            float ny = corner.y() / w;
            if(nx < xMin) xMin = nx;
            if(nx > xMax) xMax = nx;
            if(ny < yMin) yMin = ny;
            if(ny > yMax) yMax = ny;
        }

        if(xMin < -1f) xMin = -1f;
        if(yMin < -1f) yMin = -1f;
        if(xMax >  1f) xMax =  1f;
        if(yMax >  1f) yMax =  1f;
        return new float[]{xMin, yMin, xMax, yMax};
    }

    /** At most one occlusion-debug dust burst per portal per game tick when profiling. */
    private static final Map<UUID, Long> PROFILER_PORTAL_OCCLUSION_LAST_PARTICLE_TICK = new HashMap<>();

    /** Grid width × height for {@link #portalOpeningControlPoints}; product = sample count. */
    private static final int PORTAL_OPENING_GRID_W = 8;
    private static final int PORTAL_OPENING_GRID_H = 16;
    private static final int PORTAL_OPENING_CONTROL_POINT_COUNT = PORTAL_OPENING_GRID_W * PORTAL_OPENING_GRID_H;
    private final Vector3d[] portalOpeningScratch = new Vector3d[PORTAL_OPENING_CONTROL_POINT_COUNT];

    /**
     * World-space sample points on the portal opening laid out in a {@link #PORTAL_OPENING_GRID_W}×{@link #PORTAL_OPENING_GRID_H}
     * grid (row-major: index = row * W + col). Local coords (a,b) in [-1,1]² map to {@code center + a*right + b*up}
     * (source basis: half width 0.5, half height 1).
     *
     * @param out length must be at least {@link #PORTAL_OPENING_CONTROL_POINT_COUNT}
     */
    private static void portalOpeningControlPoints(PortalEntity portal, Vector3d[] out) {
        OrthonormalBasis basis = portal.getSourceBasis();
        Vec3 rightUnit = basis.getX().normalize();
        Vec3 upUnit = basis.getY().normalize();
        double rx = rightUnit.x * 0.5, ry = rightUnit.y * 0.5, rz = rightUnit.z * 0.5;
        double ux = upUnit.x,         uy = upUnit.y,         uz = upUnit.z;
        Vector3d center = portal.getBoundingBox().getCenter();
        int idx = 0;
        for(int row = 0; row < PORTAL_OPENING_GRID_H; row++) {
            double b = portalOpeningGridCoord(PORTAL_OPENING_GRID_H, row);
            for(int col = 0; col < PORTAL_OPENING_GRID_W; col++) {
                double a = portalOpeningGridCoord(PORTAL_OPENING_GRID_W, col);
                out[idx++] = new Vector3d(
                        center.x + a * rx + b * ux,
                        center.y + a * ry + b * uy,
                        center.z + a * rz + b * uz);
            }
        }
    }

    /** {@code [-1, 1]} inclusive edge positions for a uniform grid with {@code dim} samples ({@code dim==1} → 0). */
    private static double portalOpeningGridCoord(int dim, int index) {
        if(dim <= 1)
            return 0;
        return -1.0 + 2.0 * index / (dim - 1);
    }

    private static int portalOpeningGridIndex(int row, int col) {
        return row * PORTAL_OPENING_GRID_W + col;
    }

    private static boolean isPortalOpeningProbeIndex(int index) {
        int midRow = PORTAL_OPENING_GRID_H / 2;
        int midCol = PORTAL_OPENING_GRID_W / 2;
        return index == portalOpeningGridIndex(0, 0)
                || index == portalOpeningGridIndex(0, PORTAL_OPENING_GRID_W - 1)
                || index == portalOpeningGridIndex(PORTAL_OPENING_GRID_H - 1, 0)
                || index == portalOpeningGridIndex(PORTAL_OPENING_GRID_H - 1, PORTAL_OPENING_GRID_W - 1)
                || index == portalOpeningGridIndex(midRow, midCol)
                || index == portalOpeningGridIndex(Math.max(0, midRow - 1), Math.max(0, midCol - 1));
    }

    private static boolean blockCountsAsOpaqueForPortalSight(BlockState state) {
        if(state.isAir())
            return false;
        // Blocks that opt out of face-culling occlusion (glass, panes, bars, leaves,
        // scaffolding, fences, etc.) are visually see-through enough that the portal
        // surface behind them still needs to draw, so the ray should pass through.
        if(!state.canOcclude())
            return false;
        // Defensive fallback for modded blocks that claim canOcclude=true while having
        // no collider (fluids, decorations). In vanilla this is already covered by
        // canOcclude, but modded content doesn't always follow the rule.
        if(!state.getMaterial().blocksMotion())
            return false;
        return true;
    }

    /**
     * {@code true} if the ray from {@code from} reaches {@code corner} without an opaque block in front of it.
     * The shape override collapses every non-sight-blocking block (glass, panes, bars,
     * leaves, scaffolding, fluids, …) to {@link VoxelShapes#empty()}, so the raycast
     * keeps traversing through stacks of non-occluders and only reports a hit when it
     * reaches an actually solid block.
     *
     * <p>Only called from {@link #portalOpeningFullyOccluded} at {@code recursion == 1}
     * with the real main camera, so portal-chain traversal is not needed — a plain
     * {@link ModUtil#customClip} is faster than {@code clipThroughPortals*} for this
     * 128-ray-per-frame hot path.
     *
     * <p>When {@code emitDebugParticles} is {@code true}, spawns a coloured dust at the
     * corner sample and (if the ray was blocked) at the exact block-face hit point, so
     * you can see the ray results ingame.
     */
    private static boolean cornerVisibleAlongRay(ClientWorld world, Vector3d from, Vector3d corner,
                                                 @Nullable Entity viewer, boolean emitDebugParticles) {
        RayTraceContext ctx = new RayTraceContext(from, corner, RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, viewer);
        BlockRayTraceResult hit = ModUtil.customClip(world, ctx, pos ->
                blockCountsAsOpaqueForPortalSight(world.getBlockState(pos))
                        ? Optional.empty()
                        : EMPTY_SHAPE_OVERRIDE);

        boolean visible;
        if(hit.getType() == RayTraceResult.Type.MISS) {
            visible = true;
        } else {
            double distCorner = from.distanceTo(corner);
            double distHit = from.distanceTo(hit.getLocation());
            visible = distHit >= distCorner - RAY_CORNER_EPSILON;
        }

        if(emitDebugParticles) {
            // Corner sample: green = ray reached it (portal will render), red = occluded.
            RedstoneParticleData cornerDust = visible
                    ? new RedstoneParticleData(0.1F, 1.0F, 0.2F, 0.2F)
                    : new RedstoneParticleData(1.0F, 0.15F, 0.1F, 0.2F);
            world.addParticle(cornerDust, corner.x, corner.y, corner.z, 0, 0, 0);

            if(!visible && hit.getType() != RayTraceResult.Type.MISS) {
                // Yellow dust at the block-face where the ray stopped. If grates/glass
                // are working correctly, this should be on the solid wall behind them,
                // not on the grate itself.
                Vector3d h = hit.getLocation();
                RedstoneParticleData hitDust = new RedstoneParticleData(1.0F, 0.9F, 0.2F, 0.2F);
                world.addParticle(hitDust, h.x, h.y, h.z, 0, 0, 0);
            }
        }

        return visible;
    }

    /**
     * {@code true} if every grid sample on the portal opening is blocked by opaque geometry from the camera
     * (cheap skip path). Only meaningful from {@link #renderPortal} when {@code recursion == 1} (main view);
     * nested passes skip this.
     */
    public boolean portalOpeningFullyOccluded(PortalEntity portal, ActiveRenderInfo camera) {
        ClientWorld world = Minecraft.getInstance().level;
        if(world == null)
            return false;

        Vector3d from = camera.getPosition();
        Entity viewer = camera.getEntity();
        if(viewer == null)
            viewer = Minecraft.getInstance().player;

        Vector3d[] points = portalOpeningScratch;
        portalOpeningControlPoints(portal, points);

        // Rate-limit debug particles to once per game tick per portal so the HUD
        // stays readable when the occlusion test runs every frame.
        boolean emitDebugParticles = false;
        if(PROFILE.enabled) {
            long gt = world.getGameTime();
            UUID id = portal.getUUID();
            Long lastTick = PROFILER_PORTAL_OCCLUSION_LAST_PARTICLE_TICK.get(id);
            if(lastTick == null || lastTick != gt) {
                PROFILER_PORTAL_OCCLUSION_LAST_PARTICLE_TICK.put(id, gt);
                emitDebugParticles = true;
            }
        }

        boolean anyVisible = false;
        if(!emitDebugParticles) {
            for(int i = 0; i < points.length; i++) {
                if(!isPortalOpeningProbeIndex(i))
                    continue;
                if(cornerVisibleAlongRay(world, from, points[i], viewer, false))
                    return false;
            }
        }

        for(int i = 0; i < points.length; i++) {
            if(!emitDebugParticles && isPortalOpeningProbeIndex(i))
                continue;

            boolean visible = cornerVisibleAlongRay(world, from, points[i], viewer, emitDebugParticles);
            if(visible) {
                anyVisible = true;
                if(!emitDebugParticles)
                    return false;
            }
        }
        return !anyVisible;
    }

    private static boolean rectsIntersect(float[] a, float[] b) {
        return !(a[2] < b[0] || a[0] > b[2] || a[3] < b[1] || a[1] > b[3]);
    }

    private static float[] intersectRect(float[] a, float[] b) {
        float xMin = Math.max(a[0], b[0]);
        float yMin = Math.max(a[1], b[1]);
        float xMax = Math.min(a[2], b[2]);
        float yMax = Math.min(a[3], b[3]);
        if(xMin >= xMax || yMin >= yMax) return null;
        return new float[]{xMin, yMin, xMax, yMax};
    }

    private static float portalCoverageFromRect(@Nullable float[] rect) {
        if(rect == null)
            return 1F;
        float width = Math.max(0F, rect[2] - rect[0]);
        float height = Math.max(0F, rect[3] - rect[1]);
        return MathHelper.clamp((width * height) * 0.25F, 0F, 1F);
    }

    private float getOcclusionCacheRelaxation(float portalCoverage, boolean fullyOccluded) {
        if(!fullyOccluded)
            return 0.0F;

        float coverageRelaxation = 1.0F - (float)Math.sqrt(Math.max(portalCoverage, 0.0F));
        float motionRelaxation = fastCameraMotionFactor * 0.85F;
        return MathHelper.clamp(Math.max(coverageRelaxation, motionRelaxation), 0.0F, 1.0F);
    }

    private boolean cameraChangedTooMuchForOcclusionCache(PortalOcclusionCacheEntry entry, ActiveRenderInfo camera, float portalCoverage) {
        float relaxation = getOcclusionCacheRelaxation(portalCoverage, entry.fullyOccluded);
        double posDeltaSqr = MathHelper.lerp(relaxation,
                OCCLUSION_CACHE_CAMERA_POS_DELTA_SQR,
                OCCLUSION_CACHE_CAMERA_POS_DELTA_SQR_MAX);
        float rotDelta = MathHelper.lerp(relaxation,
                OCCLUSION_CACHE_CAMERA_ROT_DELTA,
                OCCLUSION_CACHE_CAMERA_ROT_DELTA_MAX);

        Vector3d currentPos = camera.getPosition();
        if(entry.cameraPos == null || entry.cameraPos.distanceToSqr(currentPos) > posDeltaSqr)
            return true;
        return Math.abs(MathHelper.wrapDegrees(entry.xRot - camera.getXRot())) > rotDelta
                || Math.abs(MathHelper.wrapDegrees(entry.yRot - camera.getYRot())) > rotDelta;
    }

    private long getOcclusionCacheTicks(PortalOcclusionCacheEntry entry, float portalCoverage) {
        float relaxation = getOcclusionCacheRelaxation(portalCoverage, entry.fullyOccluded);
        return Math.round(MathHelper.lerp(relaxation, (float)OCCLUSION_CACHE_TICKS, (float)OCCLUSION_CACHE_MAX_TICKS));
    }

    private boolean portalOpeningFullyOccludedCached(PortalEntity portal, ActiveRenderInfo camera, float portalCoverage) {
        ClientWorld world = Minecraft.getInstance().level;
        if(world == null)
            return false;

        long gameTime = world.getGameTime();
        PortalOcclusionCacheEntry cached = portalOcclusionCache.get(portal.getUUID());
        if(cached != null
                && gameTime - cached.gameTime <= getOcclusionCacheTicks(cached, portalCoverage)
                && !cameraChangedTooMuchForOcclusionCache(cached, camera, portalCoverage)) {
            return cached.fullyOccluded;
        }

        boolean fullyOccluded = portalOpeningFullyOccluded(portal, camera);
        PortalOcclusionCacheEntry updated = new PortalOcclusionCacheEntry();
        updated.gameTime = gameTime;
        updated.cameraPos = camera.getPosition();
        updated.xRot = camera.getXRot();
        updated.yRot = camera.getYRot();
        updated.fullyOccluded = fullyOccluded;
        portalOcclusionCache.put(portal.getUUID(), updated);
        return fullyOccluded;
    }

    private void finishCulledPortalEntity() {
        if(PortalMod.DEBUG)
            GL43.glPopDebugGroup();

        this.portalChain.removeLast();
        recursion--;
    }

    private void finishPortalEntity(ActiveRenderInfo camera, float partialTicks, boolean fabulousGraphics, boolean restoreFogState) {
        if(fabulousGraphics)
            GL11.glDisable(GL_STENCIL_TEST);

        if(!isShallowest()) {
            RenderUtil.setStandardClipPlane(clipMatrix.last().pose());
        } else {
            glDisable(GL_CLIP_PLANE0);
        }

        if(restoreFogState)
            setupSkyAndFog(camera, partialTicks);

        if(PortalMod.DEBUG)
            GL43.glPopDebugGroup();

        this.portalChain.removeLast();
        recursion--;
    }

    private ActiveRenderInfo setupCamera(ActiveRenderInfo camera, PortalEntity portal, float partialTicks) {
        if(!portal.getOtherPortal().isPresent())
            return camera;

        Mat4 portalToPortalRotationMatrix = PortalEntity.getPortalToPortalRotationMatrix(portal, portal.getOtherPortal().get());
        Mat4 portalToPortalMatrix = PortalEntity.getPortalToPortalMatrix(portal, portal.getOtherPortal().get());
        Vec3 newCameraPos = new Vec3(camera.getPosition()).transform(portalToPortalMatrix);

        float xRot = camera.getXRot();
        float yRot = camera.getYRot();
        float zRot = camera instanceof PortalCamera ? ((PortalCamera)camera).getRoll() : PMState.cameraRoll;
        OrthonormalBasis basis = EulerConverter.toVectors(xRot, yRot, zRot);
        basis.transform(portalToPortalRotationMatrix);
        EulerConverter.EulerAngles angles = EulerConverter.toEulerAnglesLeastRoll(basis);

        return new PortalCamera(Minecraft.getInstance().level, camera.getEntity(),
                newCameraPos, angles.getPitch(), angles.getYaw(), angles.getRoll(), partialTicks);
    }

    private void setupMatrixStack(MatrixStack matrixStack, ActiveRenderInfo camera) {
        if(camera instanceof PortalCamera) {
            PortalCamera portalCamera = (PortalCamera)camera;
            matrixStack.mulPose(Vector3f.ZP.rotationDegrees(portalCamera.getRoll()));
            matrixStack.mulPose(Vector3f.XP.rotationDegrees(portalCamera.getXRot()));
            matrixStack.mulPose(Vector3f.YP.rotationDegrees(portalCamera.getYRot() + 180));
        }
    }

    private void blitFBOtoFBO(Framebuffer src, Framebuffer dest) {
        ShaderInit.ACTUAL_BLIT.get().bind()
                .setMatrix("projection", Matrix4f.createScaleMatrix(1, 1, 1))
                .setInt("texture", 0);

        RenderSystem.disableBlend();
        RenderSystem.disableAlphaTest();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        RenderSystem.activeTexture(GL_TEXTURE0);
        src.bindRead();
        dest.bindWrite(true);
        blitQuad.render();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        ShaderInit.ACTUAL_BLIT.get().unbind();
    }

    private void setupSkyAndFog(ActiveRenderInfo camera, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if(mc.level != null) {
            FogRenderer.setupColor(camera, partialTicks, mc.level, mc.options.renderDistance,
                    mc.gameRenderer.getDarkenWorldAmount(partialTicks));
            float renderDistance = mc.gameRenderer.getRenderDistance();
            boolean hasFog = mc.level.effects().isFoggyAt(MathHelper.floor(camera.getBlockPosition().getX()), MathHelper.floor(camera.getBlockPosition().getY()))
                    || mc.gui.getBossOverlay().shouldCreateWorldFog();
            if(Minecraft.getInstance().options.renderDistance >= 4)
                FogRenderer.setupFog(camera, FogRenderer.FogType.FOG_SKY, renderDistance, hasFog, partialTicks);
            FogRenderer.setupFog(camera, FogRenderer.FogType.FOG_TERRAIN,
                    Math.max(renderDistance - 16, 32), hasFog, partialTicks);
        }
    }

    private void renderPortal(PortalEntity portal, ActiveRenderInfo camera, ClippingHelper clippingHelper, Matrix4f projectionMatrix, float partialTicks, boolean fabulousGraphics) {
        recursion++;
        this.portalChain.addLast(portal);
        if(PROFILE.enabled) {
            PROFILE.portalsVisited++;
            if(recursion > PROFILE.maxRecursionReached)
                PROFILE.maxRecursionReached = recursion;
        }

        if(PortalMod.DEBUG) {
            glEnable(GL43.GL_DEBUG_OUTPUT);
            glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
            GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0, "Rendering portal, recursion: " + recursion);
        }

        PROFILE.push("discardPortal");
        boolean culled = discardPortal(portal, camera, clippingHelper, projectionMatrix);
        PROFILE.pop();
        if(culled) {
            if(PROFILE.enabled)
                PROFILE.portalsCulled++;
            markPortalCulled(portal);
            finishCulledPortalEntity();
            return;
        }

        // Four-corner ray occlusion only for the outermost portal pass (recursion 1 after increment).
        // Nested renderLevel uses PortalCamera; established discard/stencil/nesting handles those.
        float[] portalRect = getPortalNdcRectCached(portal, camera, projectionMatrix);
        float portalCoverage = portalCoverageFromRect(portalRect);

        boolean openingOccluded = recursion == 1 && portalOpeningFullyOccludedCached(portal, camera, portalCoverage);
        if(openingOccluded) {
            portalsStencilSkippedOccludedRays++;
            markPortalCulled(portal);
            finishCulledPortalEntity();
            return;
        }
        markPortalRendered(portal);

        Minecraft mc = Minecraft.getInstance();
        Framebuffer mainFBO = mc.getMainRenderTarget();

        Matrix4f modelMatrix = getModelMatrix(portal, camera, portal.getWallAttachmentDistance(camera));
        Matrix4f viewMatrix = getViewMatrix(camera);

        Matrix4f modelView = viewMatrix.copy();
        modelView.multiply(modelMatrix);

        boolean canNestRender = false;
        {
            GL11.glEnable(GL_STENCIL_TEST);
            Optional<PortalEntity> otherPortalOptional = portal.getOtherPortal();

            RenderSystem.stencilMask(0x7F);
            RenderSystem.stencilFunc(GL_EQUAL, recursion - 1, 0x7F);
            RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_INCR);
            PROFILE.push("mask(INCR)");
            renderMask(portal, modelMatrix, viewMatrix, projectionMatrix);
            PROFILE.pop();

            RenderSystem.stencilMask(0);
            RenderSystem.stencilFunc(GL_EQUAL, recursion, 0x7F);
            RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            PROFILE.push("background");
            renderBackground();
            PROFILE.pop();

            canNestRender = otherPortalOptional.isPresent() && !isDeepest();

            if(canNestRender) {
                PortalEntity otherPortal = otherPortalOptional.get();
                MatrixStack matrixStack = new MatrixStack();
                ActiveRenderInfo portalCamera = setupCamera(camera, portal, partialTicks);
                setupMatrixStack(matrixStack, portalCamera);
                setupSkyAndFog(portalCamera, partialTicks);

                portalStack.push(otherPortal);

                // Narrow the screen-space clip region to this portal's silhouette
                // for the nested render. Children will be tested against this rect
                // in discardPortal, matching Source's portal-edge frustum clipping.
                float[] savedParentNdcRect = new float[]{
                        currentParentNdcRect[0], currentParentNdcRect[1],
                        currentParentNdcRect[2], currentParentNdcRect[3]};
                parentNdcRectStack.push(savedParentNdcRect);
                if(portalRect != null) {
                    float[] narrowed = intersectRect(savedParentNdcRect, portalRect);
                    if(narrowed != null) {
                        currentParentNdcRect[0] = narrowed[0];
                        currentParentNdcRect[1] = narrowed[1];
                        currentParentNdcRect[2] = narrowed[2];
                        currentParentNdcRect[3] = narrowed[3];
                    }
                }

                clipMatrix.pushPose();
                RenderUtil.setupClipPlane(clipMatrix, portal, camera, 0, false);

                currentCamera = portalCamera;

                Vec3 oldCameraPosOverrideForRenderingSelf = PMState.cameraPosOverrideForRenderingSelf;
                PMState.cameraPosOverrideForRenderingSelf = PMState.cameraPosOverrideForRenderingSelf == null ? null
                        : PMState.cameraPosOverrideForRenderingSelf.clone().transform(PortalEntity.getPortalToPortalMatrix(portal, otherPortal));

                PROFILE.push("saveRenderChunks");
                ObjectList<WorldRenderer.LocalRenderInformationContainer> renderChunks = acquireRenderChunkSnapshot();
                renderChunks.addAll(mc.levelRenderer.renderChunks);
                PROFILE.pop();

                boolean renderOutline = this.shouldRenderOutline(portalChain);
                if(PROFILE.enabled)
                    PROFILE.portalsNested++;
                NestedRenderSettings nestedSettings = buildNestedRenderSettings(portal, portalCoverage);
                nestedRenderSettingsStack.addLast(nestedSettings);

                // Shorter BFS for the nested chunk walk. In 1.16.5, setupRender's BFS grid
                // iterates +/- lastViewDistance around the camera chunk, so clamping that
                // field shrinks the seed grid proportionally to distance^2. We leave
                // options.renderDistance untouched so nothing else (fog, setViewScale, F3,
                // GUI) is disturbed; a mixin redirects only the `options.renderDistance !=
                // lastViewDistance` check at the top of setupRender so it doesn't trip
                // allChanged() during our intentional mismatch.
                LevelRendererAccessor lvlAcc = (LevelRendererAccessor)mc.levelRenderer;
                int origLastViewDistance = lvlAcc.pmGetLastViewDistance();
                // Halve the BFS radius per recursion level (capped at /32). Each
                // nested camera hop sees progressively less of the world through
                // the shrinking silhouette, so we shrink the chunk-walk seed grid
                // geometrically. At r=1: /2, r=2: /4, r=3: /8, ... — mirrors
                // Source engine's portal-aware PVS pruning without needing a PVS.
                int shift = Math.min(recursion, 5);
                // Clamp nested chunk visibility by projected portal coverage more aggressively
                // than sqrt(area), but never to zero. This preserves a live portal opening while
                // cutting far-away detail that cannot materially contribute through a tiny stencil.
                float coverageScale = Math.max((float)Math.pow(Math.max(portalCoverage, NESTED_PORTAL_MIN_COVERAGE), 0.65F), 0.12F);
                if(recursion >= 2)
                    coverageScale = Math.max(0.1F, coverageScale * 0.85F);
                if(fastCameraMotionFactor > 0.0F) {
                    float motionScale = MathHelper.lerp(fastCameraMotionFactor, 1.0F, recursion >= 2 ? 0.25F : 0.4F);
                    coverageScale = Math.max(0.06F, coverageScale * motionScale);
                }
                int recursionDistance = Math.max(2, origLastViewDistance >> shift);
                int coverageDistance = Math.max(2, Math.round(origLastViewDistance * coverageScale));
                if(fastCameraMotionFactor >= FAST_CAMERA_HARD_CLAMP_START) {
                    float t = (fastCameraMotionFactor - FAST_CAMERA_HARD_CLAMP_START) / (1.0F - FAST_CAMERA_HARD_CLAMP_START);
                    int hardClampDistance = Math.max(2, Math.round(MathHelper.lerp(t, 6.0F, recursion >= 2 ? 2.0F : 4.0F)));
                    coverageDistance = Math.min(coverageDistance, hardClampDistance);
                }
                int clampedDistance = Math.max(2, Math.min(recursionDistance, coverageDistance));
                boolean distanceClamped = clampedDistance < origLastViewDistance;
                int previousOverride = nestedBfsDistanceOverride;
                if(distanceClamped) {
                    lvlAcc.pmSetLastViewDistance(clampedDistance);
                    nestedBfsDistanceOverride = clampedDistance;
                }

                PROFILE.push("nestedRenderLevel@r" + recursion);
                try {
                    mc.levelRenderer.renderLevel(matrixStack, partialTicks, Util.getNanos(), renderOutline, portalCamera,
                            mc.gameRenderer, mc.gameRenderer.lightTexture, projectionMatrix);
                } finally {
                    nestedRenderSettingsStack.removeLast();
                    if(distanceClamped) {
                        lvlAcc.pmSetLastViewDistance(origLastViewDistance);
                        nestedBfsDistanceOverride = previousOverride;
                    }
                    PROFILE.pop();
                }

                PROFILE.push("restoreRenderChunks");
                try {
                    mc.levelRenderer.needsUpdate = true;
                    mc.levelRenderer.renderChunks.clear();
                    mc.levelRenderer.renderChunks.addAll(renderChunks);

                    TileEntityRendererDispatcher.instance.prepare(portal.level, mc.getTextureManager(), mc.font, camera, mc.hitResult);
                    mc.levelRenderer.entityRenderDispatcher.prepare(portal.level, camera, mc.crosshairPickEntity);
                } finally {
                    releaseRenderChunkSnapshot(renderChunks);
                    PROFILE.pop();
                }

                currentCamera = camera;
                PMState.cameraPosOverrideForRenderingSelf = oldCameraPosOverrideForRenderingSelf;

                clipMatrix.popPose();
                portalStack.pop();

                float[] restored = parentNdcRectStack.pop();
                currentParentNdcRect[0] = restored[0];
                currentParentNdcRect[1] = restored[1];
                currentParentNdcRect[2] = restored[2];
                currentParentNdcRect[3] = restored[3];

                if(fabulousGraphics) {
                    GL11.glEnable(GL_STENCIL_TEST);
                    RenderSystem.stencilMask(0);
                    RenderSystem.stencilFunc(GL_NOTEQUAL, recursion, 0x7F);
                    RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
                    PROFILE.push("fabulousBlit(nested)");
                    blitFBOtoFBO(tempFBO, mainFBO);
                    mainFBO.copyDepthFrom(tempFBO);
                    mainFBO.bindWrite(true);
                    PROFILE.pop();
                }
            }

            glDisable(GL_CLIP_PLANE0);
            GL11.glEnable(GL_STENCIL_TEST);
            RenderSystem.color4f(1, 1, 1, 1);

            RenderSystem.stencilMask(0x80);
            RenderSystem.stencilFunc(GL_EQUAL, recursion, 0xFF);
            RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_INVERT);
            PROFILE.push("mask(INVERT)");
            renderMask(portal, modelMatrix, viewMatrix, projectionMatrix);
            PROFILE.pop();

            RenderSystem.stencilMask(0);
            RenderSystem.stencilFunc(GL_EQUAL, recursion, 0x7F);
            RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            PROFILE.push("border");
            renderBorder(portal, modelMatrix, viewMatrix, projectionMatrix);
            PROFILE.pop();

            RenderSystem.stencilMask(0x7F);
            RenderSystem.stencilFunc(GL_EQUAL, recursion, 0x7F);
            RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_DECR);
            PROFILE.push("depth(DECR)");
            renderDepth(modelView);
            PROFILE.pop();

            if(!fabulousGraphics) {
                RenderSystem.stencilMask(0);
                RenderSystem.stencilFunc(GL_EQUAL, recursion - 1, 0x7F);
                RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
        }

        finishPortalEntity(camera, partialTicks, fabulousGraphics, canNestRender);
    }

    public boolean shouldRenderOutline(@Nullable Deque<PortalEntity> portalChain) {
        if(portalChain == null || this.outlineRenderingPortalChain == null)
            return (portalChain == null || portalChain.isEmpty())
                    && (this.outlineRenderingPortalChain == null || this.outlineRenderingPortalChain.isEmpty());

        if(portalChain.size() != this.outlineRenderingPortalChain.size())
            return false;

        Iterator<PortalEntity> iterator = this.outlineRenderingPortalChain.iterator();
        for(PortalEntity portal : portalChain)
            if(portal != iterator.next())
                return false;
        return true;
    }

    private boolean isDeepest() {
        return recursion > PortalModConfigManager.RECURSION.get();
    }

    private boolean isShallowest() {
        return recursion <= 1;
    }

    public void renderHighlights(ActiveRenderInfo camera, Matrix4f projectionMatrix) {
        ClientWorld level = Minecraft.getInstance().level;
        ClientPlayerEntity player = Minecraft.getInstance().player;
        if(level == null || player == null)
            return;

        ItemStack item = player.getMainHandItem();
        Optional<UUID> gunUUID = PortalGun.getUUID(item);
        if(!(item.getItem() instanceof PortalGun) || !gunUUID.isPresent())
            return;

        if(PortalMod.DEBUG)
            GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0, "Highlights");

        Vector3d cameraPos = currentCamera != null
                ? currentCamera.getPosition()
                : Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

        GL11.glEnable(GL_STENCIL_TEST);
        RenderSystem.stencilMask(0);
        RenderSystem.stencilFunc(GL_EQUAL, recursion, 0xFF);
        RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_GREATER);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        for(PortalEntity portal : getFramePortalEntities(level)) {
            if(!gunUUID.get().equals(portal.getGunUUID()))
                continue;

            Matrix4f model = getModelMatrix(portal, camera, portal.getWallAttachmentDistance(camera) * 3);
            Matrix4f view = getViewMatrix(camera);

            ShaderInit.PORTAL_HIGHLIGHT.get().bind()
                    .setMatrix("model", model)
                    .setMatrix("view", view)
                    .setMatrix("projection", projectionMatrix)
                    .setFloat("intensity", (float)portal.position().distanceTo(cameraPos));

            this.setupShaderClipPlane(ShaderInit.PORTAL_HIGHLIGHT.get(), this.portalStack.peekFirst());

            RenderUtil.bindTexture(ShaderInit.PORTAL_HIGHLIGHT.get(), "texture",
                    "textures/portal/highlight_" + portal.getColor() + ".png", 0);

            portalMesh.render();
        }

        RenderSystem.enableCull();
        RenderSystem.depthFunc(GL_LESS);
        RenderSystem.depthMask(true);
        RenderSystem.bindTexture(0);
        RenderSystem.disableBlend();
        unbindBuffer();
        ShaderInit.PORTAL_HIGHLIGHT.get().unbind();

        if(fabulousGraphics) {
            GL11.glDisable(GL_STENCIL_TEST);
        } else {
            RenderSystem.stencilMask(0);
            RenderSystem.stencilFunc(GL_EQUAL, recursion, 0x7F);
            RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }

        if(PortalMod.DEBUG)
            GL43.glPopDebugGroup();
    }
    
    private void unbindBuffer() {
        DefaultVertexFormats.POSITION_TEX.clearBufferState();
        VertexBuffer.unbind();
    }

    private void setupShaderClipPlane(Shader shader, @Nullable PortalEntity portal) {
        if(portal == null) {
            shader.bind().setInt("clipPlaneEnabled", 0);
            return;
        }

        Vec3 pos = new Vec3(portal.position());
        Vec3 vec = new Vec3(portal.getDirection());

        shader.bind()
                .setInt("clipPlaneEnabled", 1)
                .setFloat("clipVec", (float)vec.x, (float)vec.y, (float)vec.z)
                .setFloat("clipPos", (float)pos.x, (float)pos.y, (float)pos.z);
    }

    private Matrix4f getModelMatrix(PortalEntity portal, ActiveRenderInfo camera, float offset) {
        Vector3i portalNormal = portal.getDirection().getNormal();
        MatrixStack matrix = new MatrixStack();
        Vec3 offsetNormal = new Vec3(camera.getPosition()).sub(portal.position()).normalize().mul(offset);

        matrix.translate(offsetNormal.x, offsetNormal.y, offsetNormal.z);
        matrix.translate(portalNormal.getX() * 0.0001f, portalNormal.getY() * 0.0001f, portalNormal.getZ() * 0.0001f);
        PortalEntity.setupMatrix(matrix, portal.getDirection(), portal.getUpVector(), portal.getPivotPoint());
        return matrix.last().pose();
    }

    private Matrix4f getViewMatrix(ActiveRenderInfo camera) {
        Vector3d cameraPos = camera.getPosition();
        MatrixStack matrix = new MatrixStack();
        float roll = camera instanceof PortalCamera ? ((PortalCamera)camera).getRoll() : PMState.cameraRoll;

        matrix.mulPose(Vector3f.ZP.rotationDegrees(roll));
        matrix.mulPose(Vector3f.XP.rotationDegrees(camera.getXRot()));
        matrix.mulPose(Vector3f.YP.rotationDegrees(camera.getYRot() + 180.0F));
        matrix.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        return matrix.last().pose();
    }

    public ActiveRenderInfo getCurrentCamera() {
        if(this.currentCamera == null)
            return Minecraft.getInstance().gameRenderer.getMainCamera();
        return this.currentCamera;
    }
}
