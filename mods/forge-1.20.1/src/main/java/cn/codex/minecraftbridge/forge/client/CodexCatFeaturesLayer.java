package cn.codex.minecraftbridge.forge.client;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import cn.codex.minecraftbridge.forge.CodexNpcEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class CodexCatFeaturesLayer extends RenderLayer<CodexNpcEntity, CodexCatgirlModel> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(MinecraftCodexBridge.MOD_ID, "codex_cat_features"),
        "main"
    );
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        MinecraftCodexBridge.MOD_ID,
        "textures/entity/codex_cat_features.png"
    );
    private static final ResourceLocation HAIR_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        MinecraftCodexBridge.MOD_ID,
        "textures/entity/codex_cat_hair.png"
    );

    private final CatFeaturesModel model;

    public CodexCatFeaturesLayer(RenderLayerParent<CodexNpcEntity, CodexCatgirlModel> parent, EntityModelSet models) {
        super(parent);
        model = new CatFeaturesModel(models.bakeLayer(LAYER));
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition ears = root.addOrReplaceChild("ears", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition leftEar = ears.addOrReplaceChild(
            "left_ear",
            CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, -2.5F, -1.25F, 4.0F, 2.5F, 2.5F),
            PartPose.offsetAndRotation(3.2F, -7.6F, 0.0F, -0.08F, 0.0F, 0.20F)
        );
        leftEar.addOrReplaceChild(
            "tip",
            CubeListBuilder.create().texOffs(0, 5).addBox(-1.25F, -3.0F, -1.0F, 2.5F, 3.0F, 2.0F),
            PartPose.offset(0.0F, -2.3F, 0.0F)
        );
        PartDefinition rightEar = ears.addOrReplaceChild(
            "right_ear",
            CubeListBuilder.create().texOffs(10, 0).addBox(-2.0F, -2.5F, -1.25F, 4.0F, 2.5F, 2.5F),
            PartPose.offsetAndRotation(-3.2F, -7.6F, 0.0F, -0.08F, 0.0F, -0.20F)
        );
        rightEar.addOrReplaceChild(
            "tip",
            CubeListBuilder.create().texOffs(10, 5).addBox(-1.25F, -3.0F, -1.0F, 2.5F, 3.0F, 2.0F),
            PartPose.offset(0.0F, -2.3F, 0.0F)
        );

        PartDefinition tail = root.addOrReplaceChild(
            "tail",
            CubeListBuilder.create().texOffs(0, 8).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 6.0F, 3.0F),
            PartPose.offsetAndRotation(0.0F, 9.0F, 2.2F, 0.75F, 0.0F, 0.0F)
        );
        PartDefinition middle = tail.addOrReplaceChild(
            "middle",
            CubeListBuilder.create().texOffs(12, 8).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 6.0F, 2.8F),
            PartPose.offsetAndRotation(0.0F, 5.3F, 0.0F, 0.38F, 0.0F, 0.0F)
        );
        middle.addOrReplaceChild(
            "tip",
            CubeListBuilder.create().texOffs(24, 8).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 6.0F, 2.5F),
            PartPose.offsetAndRotation(0.0F, 5.2F, 0.0F, 0.34F, 0.0F, 0.0F)
        );

        PartDefinition headHair = root.addOrReplaceChild(
            "head_hair",
            CubeListBuilder.create()
                .texOffs(0, 0).addBox(-4.25F, -8.35F, -4.25F, 8.5F, 1.0F, 8.5F)
                .texOffs(0, 12).addBox(-4.25F, -7.5F, 3.65F, 8.5F, 7.75F, 0.75F)
                .texOffs(22, 12).addBox(-4.25F, -7.5F, -3.5F, 0.75F, 7.75F, 7.2F)
                .texOffs(40, 12).addBox(3.5F, -7.5F, -3.5F, 0.75F, 7.75F, 7.2F)
                .texOffs(36, 0).addBox(-3.9F, -7.9F, -4.55F, 7.8F, 2.2F, 0.65F),
            PartPose.ZERO
        );
        headHair.addOrReplaceChild(
            "left_bang",
            CubeListBuilder.create().texOffs(36, 4).addBox(-1.1F, 0.0F, -0.35F, 2.2F, 5.2F, 0.7F),
            PartPose.offsetAndRotation(2.65F, -6.0F, -4.25F, -0.04F, 0.0F, -0.10F)
        );
        headHair.addOrReplaceChild(
            "right_bang",
            CubeListBuilder.create().texOffs(43, 4).addBox(-1.1F, 0.0F, -0.35F, 2.2F, 4.2F, 0.7F),
            PartPose.offsetAndRotation(-2.55F, -6.0F, -4.25F, -0.04F, 0.0F, 0.10F)
        );

        PartDefinition longHair = root.addOrReplaceChild(
            "long_hair",
            CubeListBuilder.create()
                .texOffs(0, 20).addBox(-4.25F, -0.5F, 2.15F, 8.5F, 12.5F, 1.5F)
                .texOffs(22, 20).addBox(-5.0F, -0.2F, 1.45F, 1.5F, 12.2F, 2.0F)
                .texOffs(29, 20).addBox(3.5F, -0.2F, 1.45F, 1.5F, 12.2F, 2.0F),
            PartPose.ZERO
        );
        PartDefinition lowerHair = longHair.addOrReplaceChild(
            "lower",
            CubeListBuilder.create()
                .texOffs(0, 36).addBox(-3.8F, 0.0F, 0.0F, 7.6F, 7.5F, 1.4F)
                .texOffs(20, 36).addBox(-4.5F, 0.0F, -0.45F, 1.4F, 8.3F, 1.8F)
                .texOffs(27, 36).addBox(3.1F, 0.0F, -0.45F, 1.4F, 8.3F, 1.8F),
            PartPose.offset(0.0F, 11.3F, 2.25F)
        );
        lowerHair.addOrReplaceChild(
            "tips",
            CubeListBuilder.create()
                .texOffs(36, 20).addBox(-3.65F, 0.0F, 0.0F, 2.0F, 2.4F, 1.2F)
                .texOffs(43, 20).addBox(-1.0F, 0.0F, 0.0F, 2.0F, 3.1F, 1.2F)
                .texOffs(50, 20).addBox(1.65F, 0.0F, 0.0F, 2.0F, 2.4F, 1.2F),
            PartPose.offset(0.0F, 7.0F, 0.1F)
        );
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void render(
        PoseStack poseStack,
        MultiBufferSource buffers,
        int packedLight,
        CodexNpcEntity entity,
        float limbSwing,
        float limbSwingAmount,
        float partialTick,
        float ageInTicks,
        float netHeadYaw,
        float headPitch
    ) {
        model.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        VertexConsumer hairConsumer = buffers.getBuffer(RenderType.entityCutoutNoCull(HAIR_TEXTURE));

        poseStack.pushPose();
        getParentModel().head.translateAndRotate(poseStack);
        model.renderEars(poseStack, consumer, packedLight);
        model.renderHeadHair(poseStack, hairConsumer, packedLight);
        poseStack.popPose();

        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack);
        model.renderTail(poseStack, consumer, packedLight);
        model.renderLongHair(poseStack, hairConsumer, packedLight);
        poseStack.popPose();
    }

    private static final class CatFeaturesModel extends EntityModel<CodexNpcEntity> {
        private final ModelPart ears;
        private final ModelPart leftEar;
        private final ModelPart rightEar;
        private final ModelPart tail;
        private final ModelPart tailMiddle;
        private final ModelPart tailTip;
        private final ModelPart headHair;
        private final ModelPart longHair;
        private final ModelPart lowerHair;

        private CatFeaturesModel(ModelPart root) {
            ears = root.getChild("ears");
            leftEar = ears.getChild("left_ear");
            rightEar = ears.getChild("right_ear");
            tail = root.getChild("tail");
            tailMiddle = tail.getChild("middle");
            tailTip = tailMiddle.getChild("tip");
            headHair = root.getChild("head_hair");
            longHair = root.getChild("long_hair");
            lowerHair = longHair.getChild("lower");
        }

        @Override
        public void setupAnim(CodexNpcEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            float walk = Mth.cos(limbSwing * 0.6662F) * 0.35F * Math.min(1.0F, limbSwingAmount);
            float idle = Mth.sin(ageInTicks * 0.08F) * 0.18F;
            float speech = entity.clientSpeechTicks() > 0 ? Mth.sin(ageInTicks * 0.75F) * 0.12F : 0.0F;
            float hurt = entity.hurtTime > 0 ? 0.35F : 0.0F;
            leftEar.zRot = 0.20F + speech + hurt;
            rightEar.zRot = -0.20F - speech - hurt;
            leftEar.xRot = -0.08F + Mth.sin(ageInTicks * 0.17F) * 0.025F;
            rightEar.xRot = leftEar.xRot;

            float movement = Math.min(1.0F, limbSwingAmount);
            longHair.xRot = 0.02F + movement * 0.08F;
            longHair.yRot = idle * 0.12F;
            longHair.zRot = walk * 0.05F;
            lowerHair.xRot = -0.04F + movement * 0.16F + Mth.sin(ageInTicks * 0.07F) * 0.025F;
            lowerHair.yRot = idle * 0.25F;

            if (entity.isDowned()) {
                tail.xRot = 1.35F;
                tail.yRot = 0.0F;
                tailMiddle.xRot = 0.65F;
                tailTip.xRot = 0.55F;
            } else {
                tail.xRot = 0.72F + Math.abs(walk) * 0.30F;
                tail.yRot = idle + walk * 0.55F;
                tailMiddle.xRot = 0.35F + walk * 0.25F;
                tailMiddle.yRot = idle * 0.7F;
                tailTip.xRot = 0.32F - walk * 0.20F;
                tailTip.yRot = idle * 0.9F;
            }
        }

        private void renderEars(PoseStack poseStack, VertexConsumer consumer, int light) {
            ears.render(poseStack, consumer, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        }

        private void renderTail(PoseStack poseStack, VertexConsumer consumer, int light) {
            tail.render(poseStack, consumer, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        }

        private void renderHeadHair(PoseStack poseStack, VertexConsumer consumer, int light) {
            headHair.render(poseStack, consumer, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        }

        private void renderLongHair(PoseStack poseStack, VertexConsumer consumer, int light) {
            longHair.render(poseStack, consumer, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer consumer, int light, int overlay, float red, float green, float blue, float alpha) {
        }
    }
}
