package net.portalmod.mixins.renderer;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.BlockState;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.IBlockReader;
import net.portalmod.common.sorted.fizzler.FizzlerFieldBlock;
import net.portalmod.common.sorted.portal.*;
import net.portalmod.core.config.PortalModConfigManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(WorldRenderer.class)
public class LevelRendererMixin {
    private static boolean pmShouldUseNestedRenderPolicy() {
        return PortalRenderer.getInstance().currentlyRenderingPortals
                && PortalRenderer.getInstance().recursion > 0
                && PortalRenderer.getInstance().getCurrentNestedRenderSettings() != null;
    }

    @Redirect(method = "levelEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockState;isAir(Lnet/minecraft/world/IBlockReader;Lnet/minecraft/util/math/BlockPos;)Z"))
    public boolean fizzlerIsAirToo(BlockState instance, IBlockReader blockReader, BlockPos pos) {
        return instance.isAir(blockReader, pos) || instance.getBlock() instanceof FizzlerFieldBlock;
    }

    // BEWARE: PORTAL RENDERING
    @Redirect(
                        method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"
            )
    )
    private void pmClear(int buffers, boolean onOsx) {
        if(PortalRenderer.getInstance().recursion == 0) {
            GL11.glStencilMask(~0);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT, onOsx);
        }
    }

    @Redirect(
                        method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/FogRenderer;setupColor(Lnet/minecraft/client/renderer/ActiveRenderInfo;FLnet/minecraft/client/world/ClientWorld;IF)V"
            )
    )
    private void pmSetupColor(ActiveRenderInfo camera, float partialTicks, ClientWorld level, int renderDistance, float darken) {
        ActiveRenderInfo activeCamera = PortalRenderer.getInstance().currentlyRenderingPortals
                ? PortalRenderer.getInstance().getCurrentCamera()
                : camera;
        FogRenderer.setupColor(activeCamera, partialTicks, level, renderDistance, darken);
    }

    @Redirect(
                        method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/FogRenderer;setupFog(Lnet/minecraft/client/renderer/ActiveRenderInfo;Lnet/minecraft/client/renderer/FogRenderer$FogType;FZF)V"
            )
    )
    private void pmSetupFog(ActiveRenderInfo camera, FogRenderer.FogType type, float renderDistance, boolean b, float partialTicks) {
        ActiveRenderInfo activeCamera = PortalRenderer.getInstance().currentlyRenderingPortals
                ? PortalRenderer.getInstance().getCurrentCamera()
                : camera;
        FogRenderer.setupFog(activeCamera, type, renderDistance, b, partialTicks);
    }

    // BEWARE: PORTAL RENDERING
    @Inject(
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void pmPreparePortalFramebuffer(MatrixStack matrixStack, float partialTicks, long l, boolean b, ActiveRenderInfo camera, GameRenderer gr, LightTexture lt, Matrix4f matrix, CallbackInfo info) {
        PortalRenderer.getInstance().prepareMainFramebufferForPortalRendering(Minecraft.getInstance().level);
    }

    @Inject(
                        method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/world/DimensionRenderInfo;constantAmbientLight()Z"
            )
    )
    private void pmRenderPortals(MatrixStack matrixStack, float partialTicks, long l, boolean b, ActiveRenderInfo camera, GameRenderer gr, LightTexture lt, Matrix4f matrix, CallbackInfo info) {
        ClientWorld level = Minecraft.getInstance().level;

        Vector3d vector3d = camera.getPosition();
        double x = vector3d.x();
        double y = vector3d.y();
        double z = vector3d.z();
        Matrix4f matrix4f = matrixStack.last().pose();
        ClippingHelper clippinghelper = new ClippingHelper(matrix4f, matrix);
        clippinghelper.prepare(x, y, z);

        PortalRenderer.getInstance().renderPortals(level, camera, clippinghelper, matrix, partialTicks);
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WorldRenderer;renderSky(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V"
            )
    )
    private void pmGateNestedSky(WorldRenderer instance, MatrixStack matrixStack, float partialTicks) {
        if(pmShouldUseNestedRenderPolicy() && !PortalRenderer.getInstance().shouldRenderNestedSky())
            return;
        instance.renderSky(matrixStack, partialTicks);
    }

    // BEWARE: PORTAL RENDERING
    @ModifyArgs(
                        method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WorldRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/matrix/MatrixStack;DDD)V"
            )
    )
    private void pmSwitchToPortalTransparency(Args args) {
        RenderType renderType = args.get(0);
        if(renderType == RenderType.translucent() && PortalRenderer.getInstance().currentlyRenderingPortals)
            args.set(0, PortalTransparencyHandler.PORTAL_TRANSLUCENT);
    }

    // BEWARE: PORTAL RENDERING
    @Redirect(
                        method = "renderChunkLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;translucent()Lnet/minecraft/client/renderer/RenderType;",
                    ordinal = 1
            )
    )
    private RenderType pmRenderPortalTransparencyBackwards() {
        return PortalRenderer.getInstance().currentlyRenderingPortals ? PortalTransparencyHandler.PORTAL_TRANSLUCENT : RenderType.translucent();
    }

    // BEWARE: PORTAL RENDERING
    @Redirect(
                        method = "renderChunkLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$CompiledChunk;isEmpty(Lnet/minecraft/client/renderer/RenderType;)Z"
            )
    )
    private boolean pmIsPortalTransparencyEmpty(ChunkRenderDispatcher.CompiledChunk compiledChunk, RenderType renderType) {
        return compiledChunk.isEmpty(renderType == PortalTransparencyHandler.PORTAL_TRANSLUCENT ? RenderType.translucent() : renderType);
    }

    // BEWARE: PORTAL RENDERING
    @Inject(
                        method = "renderChunkLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;setupRenderState()V",
                    shift = At.Shift.AFTER
            )
    )
    private void pmResortPortalTransparency(RenderType renderType, MatrixStack matrixStack, double x, double y, double z, CallbackInfo ci) {
        // todo probably optimize a bit
        // todo do asynchronously
        if(renderType == PortalTransparencyHandler.PORTAL_TRANSLUCENT) {
            Minecraft.getInstance().getProfiler().push("pm_translucent_sort");
            PortalTransparencyHandler.resortTransparency(PortalRenderer.getInstance().currentCamera);
            Minecraft.getInstance().getProfiler().pop();
        }
    }

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void pmSkipSecondaryNestedTranslucent(RenderType renderType, MatrixStack matrixStack, double x, double y, double z, CallbackInfo ci) {
        if(pmShouldUseNestedRenderPolicy()
                && (renderType == RenderType.translucent() || renderType == RenderType.tripwire() || renderType == PortalTransparencyHandler.PORTAL_TRANSLUCENT)
                && !PortalRenderer.getInstance().shouldRenderNestedTranslucent()) {
            ci.cancel();
        }
    }

    // BEWARE: PORTAL RENDERING
    @Redirect(
                        method = "setupRender",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;setCamera(Lnet/minecraft/util/math/vector/Vector3d;)V"
            )
    )
    private void pmUseMainCameraForRebuilding(ChunkRenderDispatcher instance, Vector3d pos) {
        instance.setCamera(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }

    /**
     * Mask the {@code if (options.renderDistance != lastViewDistance) allChanged();} guard
     * at the top of {@code setupRender} while a nested portal render has intentionally
     * clamped {@code lastViewDistance}. ordinal = 0 targets exactly this GETFIELD: the
     * second access of {@code renderDistance} in the same method (which feeds
     * {@code Entity.setViewScale}) is left alone so entity view scale stays consistent
     * with the player's real render distance setting.
     */
    @Redirect(
            method = "setupRender",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/GameSettings;renderDistance:I",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD,
                    ordinal = 0
            )
    )
    private int pmMaskRenderDistanceForAllChangedGuard(GameSettings instance) {
        int override = PortalRenderer.getInstance().nestedBfsDistanceOverride;
        return override > 0 ? override : instance.renderDistance;
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WorldRenderer;compileChunksUntil(J)V"
            )
    )
    private void pmSkipNestedChunkCompilation(WorldRenderer instance, long deadlineNs) {
        if(pmShouldUseNestedRenderPolicy() && !PortalRenderer.getInstance().shouldCompileNestedChunks())
            return;
        instance.compileChunksUntil(deadlineNs);
    }

    // BEWARE: PORTAL RENDERING
    @Redirect(
                        method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;shouldEntityAppearGlowing(Lnet/minecraft/entity/Entity;)Z"
            )
    )
    private boolean pmAvoidRenderingOutlineInFakeEntities(Minecraft instance, Entity entity) {
        if(PortalRenderer.getInstance().recursion > 0)
            return false;
        return instance.shouldEntityAppearGlowing(entity);
    }

    // BEWARE: PORTAL RENDERING
    @Redirect(
                        method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ActiveRenderInfo;isDetached()Z"
            )
    )
    private boolean pmRenderSelf(ActiveRenderInfo instance) {
        if(!PortalModConfigManager.RENDER_SELF.get())
            return instance.isDetached();
        return true;
    }

    // BEWARE: PORTAL RENDERING
    @Redirect(
                        method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRendererManager;shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ClippingHelper;DDD)Z"
            )
    )
    private boolean pmShouldRenderSelf(EntityRendererManager erm, Entity entity, ClippingHelper clippingHelper, double camX, double camY, double camZ) {
        if(!PortalModConfigManager.RENDER_SELF.get() || entity != Minecraft.getInstance().cameraEntity)
            return erm.shouldRender(entity, clippingHelper, camX, camY, camZ);
        return DuplicateEntityRenderer.shouldRenderSelf(entity, clippingHelper);
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WorldRenderer;renderEntity(Lnet/minecraft/entity/Entity;DDDFLcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/renderer/IRenderTypeBuffer;)V"
            )
    )
    private void pmGateNestedEntities(WorldRenderer instance, Entity entity, double camX, double camY, double camZ, float partialTicks, MatrixStack matrixStack, IRenderTypeBuffer buffer) {
        if(pmShouldUseNestedRenderPolicy() && !PortalRenderer.getInstance().shouldRenderNestedEntities())
            return;
        instance.renderEntity(entity, camX, camY, camZ, partialTicks, matrixStack, buffer);
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;render(Lnet/minecraft/tileentity/TileEntity;FLcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/renderer/IRenderTypeBuffer;)V"
            )
    )
    private void pmGateNestedBlockEntities(TileEntityRendererDispatcher instance, TileEntity tileEntity, float partialTicks, MatrixStack matrixStack, IRenderTypeBuffer buffer) {
        if(pmShouldUseNestedRenderPolicy() && !PortalRenderer.getInstance().shouldRenderNestedBlockEntities())
            return;
        instance.render(tileEntity, partialTicks, matrixStack, buffer);
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleManager;renderParticles(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/renderer/IRenderTypeBuffer$Impl;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/renderer/ActiveRenderInfo;FLnet/minecraft/client/renderer/culling/ClippingHelper;)V"
            )
    )
    private void pmGateNestedParticles(ParticleManager instance, MatrixStack matrixStack, IRenderTypeBuffer.Impl buffer, LightTexture lightTexture, ActiveRenderInfo camera, float partialTicks, ClippingHelper clippingHelper) {
        if(pmShouldUseNestedRenderPolicy() && !PortalRenderer.getInstance().shouldRenderNestedParticles())
            return;
        instance.renderParticles(matrixStack, buffer, lightTexture, camera, partialTicks, clippingHelper);
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WorldRenderer;renderClouds(Lcom/mojang/blaze3d/matrix/MatrixStack;FDDD)V"
            )
    )
    private void pmGateNestedClouds(WorldRenderer instance, MatrixStack matrixStack, float partialTicks, double x, double y, double z) {
        if(pmShouldUseNestedRenderPolicy() && !PortalRenderer.getInstance().shouldRenderNestedClouds())
            return;
        instance.renderClouds(matrixStack, partialTicks, x, y, z);
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WorldRenderer;renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V"
            )
    )
    private void pmGateNestedWeather(WorldRenderer instance, LightTexture lightTexture, float partialTicks, double x, double y, double z) {
        if(pmShouldUseNestedRenderPolicy() && !PortalRenderer.getInstance().shouldRenderNestedWeather())
            return;
        instance.renderSnowAndRain(lightTexture, partialTicks, x, y, z);
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WorldRenderer;renderWorldBounds(Lnet/minecraft/client/renderer/ActiveRenderInfo;)V"
            )
    )
    private void pmGateNestedWorldBounds(WorldRenderer instance, ActiveRenderInfo camera) {
        if(pmShouldUseNestedRenderPolicy() && !PortalRenderer.getInstance().shouldRenderNestedWorldBounds())
            return;
        instance.renderWorldBounds(camera);
    }

    // BEWARE: PORTAL RENDERING
    @Inject(
                        method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/profiler/IProfiler;popPush(Ljava/lang/String;)V",
                    ordinal = 10
            )
    )
    private void pmRenderDuplicateEntities(MatrixStack matrixStack, float partialTicks, long l, boolean b, ActiveRenderInfo camera, GameRenderer gr, LightTexture lt, Matrix4f projectionMatrix, CallbackInfo info) {
        if(!DuplicateEntityRenderer.shouldRenderDuplicatePass(camera, Minecraft.getInstance().level))
            return;

        Vector3d vector3d = camera.getPosition();
        double camX = vector3d.x();
        double camY = vector3d.y();
        double camZ = vector3d.z();

        ClippingHelper clippinghelper = new ClippingHelper(matrixStack.last().pose(), projectionMatrix);
        clippinghelper.prepare(camX, camY, camZ);

        DuplicateEntityRenderer.renderDuplicateEntities(clippinghelper, camX, camY, camZ, partialTicks, matrixStack, camera);
    }
}
