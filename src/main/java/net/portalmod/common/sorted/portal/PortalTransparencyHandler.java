package net.portalmod.common.sorted.portal;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.portalmod.core.math.Vec3;
import net.portalmod.mixins.accessors.ChunkRenderDispatcherAccessor;
import net.portalmod.mixins.accessors.CompiledChunkAccessor;

import java.util.*;

public class PortalTransparencyHandler {
    private static final double CAMERA_SORT_POS_DELTA_SQR = 0.09;
    private static final float CAMERA_SORT_ROT_DELTA = 2.0F;
    private static final int CAMERA_SORT_MAX_SKIP_FRAMES = 6;

    public static final RenderType.State PORTAL_TRANSLUCENT_STATE = RenderType.State.builder()
            .setShadeModelState(new RenderState.ShadeModelState(true))
            .setLightmapState(new RenderState.LightmapState(true))
            .setTextureState(new RenderState.TextureState(AtlasTexture.LOCATION_BLOCKS, false, true))
            .setLineState(new RenderState.LineState(OptionalDouble.of(1.1)))
            .setTransparencyState(new RenderState.TransparencyState("translucent_transparency", () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            }, () -> {
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            }))
            .setOutputState(new RenderState.TargetState("translucent_target", () -> {
                if (Minecraft.useShaderTransparency()) {
                    Minecraft.getInstance().levelRenderer.getTranslucentTarget().bindWrite(false);
                }

            }, () -> {
                if (Minecraft.useShaderTransparency()) {
                    Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                }

            }))
            .createCompositeState(true);

    public static final RenderType PORTAL_TRANSLUCENT = RenderType.create("portal_translucent", DefaultVertexFormats.BLOCK, 7, 262144, true, true, PORTAL_TRANSLUCENT_STATE);

    private static final BufferBuilder bufferBuilder = new BufferBuilder(262144);
    private static Vector3d lastPortalSortCameraPos;
    private static float lastPortalSortXRot;
    private static float lastPortalSortYRot;
    private static int portalSortFrameCounter;
    private static int lastPortalSortFrame = Integer.MIN_VALUE;
    private static Vec3 lastMainSortCameraPos;
    private static float lastMainSortXRot;
    private static float lastMainSortYRot;
    private static int mainSortFrameCounter;
    private static int lastMainSortFrame = Integer.MIN_VALUE;

    private static boolean shouldResortPortalTransparency(ActiveRenderInfo portalCamera) {
        Vector3d currentPos = portalCamera.getPosition();
        int frame = ++portalSortFrameCounter;

        if(lastPortalSortCameraPos == null) {
            lastPortalSortCameraPos = currentPos;
            lastPortalSortXRot = portalCamera.getXRot();
            lastPortalSortYRot = portalCamera.getYRot();
            lastPortalSortFrame = frame;
            return true;
        }

        boolean moved = lastPortalSortCameraPos.distanceToSqr(currentPos) > CAMERA_SORT_POS_DELTA_SQR;
        boolean rotated = Math.abs(MathHelper.wrapDegrees(lastPortalSortXRot - portalCamera.getXRot())) > CAMERA_SORT_ROT_DELTA
                || Math.abs(MathHelper.wrapDegrees(lastPortalSortYRot - portalCamera.getYRot())) > CAMERA_SORT_ROT_DELTA;
        boolean frameExpired = frame - lastPortalSortFrame >= CAMERA_SORT_MAX_SKIP_FRAMES;
        if(!(moved || rotated || frameExpired))
            return false;

        lastPortalSortCameraPos = currentPos;
        lastPortalSortXRot = portalCamera.getXRot();
        lastPortalSortYRot = portalCamera.getYRot();
        lastPortalSortFrame = frame;
        return true;
    }

    private static boolean shouldResortMainTransparency(ActiveRenderInfo camera) {
        Vec3 currentPos = new Vec3(camera.getPosition());
        int frame = ++mainSortFrameCounter;

        if(lastMainSortCameraPos == null) {
            lastMainSortCameraPos = currentPos;
            lastMainSortXRot = camera.getXRot();
            lastMainSortYRot = camera.getYRot();
            lastMainSortFrame = frame;
            return true;
        }

        boolean moved = lastMainSortCameraPos.to3d().distanceToSqr(currentPos.to3d()) > CAMERA_SORT_POS_DELTA_SQR;
        boolean rotated = Math.abs(MathHelper.wrapDegrees(lastMainSortXRot - camera.getXRot())) > CAMERA_SORT_ROT_DELTA
                || Math.abs(MathHelper.wrapDegrees(lastMainSortYRot - camera.getYRot())) > CAMERA_SORT_ROT_DELTA;
        boolean frameExpired = frame - lastMainSortFrame >= CAMERA_SORT_MAX_SKIP_FRAMES;
        if(!(moved || rotated || frameExpired))
            return false;

        lastMainSortCameraPos = currentPos;
        lastMainSortXRot = camera.getXRot();
        lastMainSortYRot = camera.getYRot();
        lastMainSortFrame = frame;
        return true;
    }

    private static boolean isChunkNearSortCamera(WorldRenderer.LocalRenderInformationContainer lric, float x, float y, float z) {
        Minecraft mc = Minecraft.getInstance();
        double radius = Math.max(32.0, mc.options.renderDistance * 16.0);
        double radiusSqr = radius * radius;
        double centerX = lric.chunk.getOrigin().getX() + 8.0;
        double centerY = lric.chunk.getOrigin().getY() + 8.0;
        double centerZ = lric.chunk.getOrigin().getZ() + 8.0;
        double dx = centerX - x;
        double dy = centerY - y;
        double dz = centerZ - z;
        return dx * dx + dy * dy + dz * dz <= radiusSqr;
    }

    public static void resortTransparency(ActiveRenderInfo portalCamera) {
        if(!shouldResortPortalTransparency(portalCamera))
            return;

        WorldRenderer lr = Minecraft.getInstance().levelRenderer;
        Queue<RegionRenderCacheBuilder> freeBuffers = ((ChunkRenderDispatcherAccessor)lr.chunkRenderDispatcher).pmGetFreeBuffers();
        if(!freeBuffers.isEmpty()) {
            for(WorldRenderer.LocalRenderInformationContainer lric : lr.renderChunks) {
                Vector3d vector3d = portalCamera.getPosition();
                float x = (float)vector3d.x;
                float y = (float)vector3d.y;
                float z = (float)vector3d.z;
                if(!isChunkNearSortCamera(lric, x, y, z))
                    continue;
                CompiledChunkAccessor cca = ((CompiledChunkAccessor)lric.chunk.compiled.get());
                BufferBuilder.State bufferbuilder$state = cca.pmGetTransparencyState();
                if(bufferbuilder$state != null && cca.pmGetHasBlocks().contains(RenderType.translucent())) {
                    bufferBuilder.begin(7, DefaultVertexFormats.BLOCK);
                    bufferBuilder.restoreState(bufferbuilder$state);
                    bufferBuilder.sortQuads(x - (float) lric.chunk.getOrigin().getX(), y - (float) lric.chunk.getOrigin().getY(), z - (float) lric.chunk.getOrigin().getZ());
                    bufferBuilder.end();
                    lric.chunk.getBuffer(PortalTransparencyHandler.PORTAL_TRANSLUCENT).upload(bufferBuilder);
                }
            }
        }
    }

    public static void resortMainTransparency(ActiveRenderInfo camera) {
        if(!shouldResortMainTransparency(camera))
            return;

        Vec3 position = new Vec3(camera.getPosition());
        WorldRenderer lr = Minecraft.getInstance().levelRenderer;
        Queue<RegionRenderCacheBuilder> freeBuffers = ((ChunkRenderDispatcherAccessor)lr.chunkRenderDispatcher).pmGetFreeBuffers();
        if(!freeBuffers.isEmpty()) {
            for(WorldRenderer.LocalRenderInformationContainer lric : lr.renderChunks) {
                float x = (float)position.x;
                float y = (float)position.y;
                float z = (float)position.z;
                if(!isChunkNearSortCamera(lric, x, y, z))
                    continue;
                CompiledChunkAccessor cca = ((CompiledChunkAccessor)lric.chunk.compiled.get());
                BufferBuilder.State bufferbuilder$state = cca.pmGetTransparencyState();
                if(bufferbuilder$state != null && cca.pmGetHasBlocks().contains(RenderType.translucent())) {
                    bufferBuilder.begin(7, DefaultVertexFormats.BLOCK);
                    bufferBuilder.restoreState(bufferbuilder$state);
                    bufferBuilder.sortQuads(x - (float) lric.chunk.getOrigin().getX(), y - (float) lric.chunk.getOrigin().getY(), z - (float) lric.chunk.getOrigin().getZ());
                    bufferBuilder.end();
                    lric.chunk.getBuffer(RenderType.translucent()).upload(bufferBuilder);
                }
            }
        }
    }
}
