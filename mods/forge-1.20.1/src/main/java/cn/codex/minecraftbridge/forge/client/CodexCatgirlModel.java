package cn.codex.minecraftbridge.forge.client;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import cn.codex.minecraftbridge.forge.CodexNpcEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.UseAnim;

/**
 * Vanilla renderer port of the CC0 Queen Cats & Dogs queen-cat model.
 *
 * <p>The original model uses GeckoLib Bedrock geometry. This port keeps its
 * 128x64 UV layout and silhouette while retaining Minecraft held-item poses.</p>
 */
public final class CodexCatgirlModel extends HumanoidModel<CodexNpcEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(MinecraftCodexBridge.MOD_ID, "codex_catgirl"),
        "main"
    );

    private final ModelPart leftEar;
    private final ModelPart rightEar;
    private final ModelPart tailBase;
    private final ModelPart tailTip;

    public CodexCatgirlModel(ModelPart root) {
        super(root);
        leftEar = head.getChild("left_ear");
        rightEar = head.getChild("right_ear");
        tailBase = body.getChild("tail_base");
        tailTip = tailBase.getChild("tail_tip");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition head = root.addOrReplaceChild(
            "head",
            CubeListBuilder.create()
                .texOffs(0, 16)
                .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F),
            PartPose.ZERO
        );
        head.addOrReplaceChild(
            "left_ear",
            CubeListBuilder.create()
                .texOffs(26, 17)
                .addBox(-1.0F, -2.0F, -0.5F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.45F)),
            PartPose.offsetAndRotation(3.0F, -8.0F, 0.0F, -0.08F, 0.0F, 0.08F)
        );
        head.addOrReplaceChild(
            "right_ear",
            CubeListBuilder.create()
                .texOffs(26, 17)
                .mirror()
                .addBox(-1.0F, -2.0F, -0.5F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.45F)),
            PartPose.offsetAndRotation(-3.0F, -8.0F, 0.0F, -0.08F, 0.0F, -0.08F)
        );
        root.addOrReplaceChild(
            "hat",
            CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.5F)),
            PartPose.ZERO
        );

        PartDefinition body = root.addOrReplaceChild(
            "body",
            CubeListBuilder.create()
                .texOffs(37, 44)
                .addBox(-3.0F, 0.0F, -2.0F, 6.0F, 12.0F, 4.0F)
                .texOffs(36, 25)
                .addBox(-3.0F, 5.0F, -1.2F, 6.0F, 1.05F, 3.0F, new CubeDeformation(0.5F))
                .texOffs(37, 24)
                .addBox(-3.1F, 4.5F, -2.5F, 6.3F, 2.0F, 3.0F)
                .texOffs(35, 25)
                .addBox(-3.0F, 11.3F, -2.5F, 6.0F, 1.0F, 5.0F)
                .texOffs(16, 16)
                .addBox(-1.0F, -3.5F, -1.0F, 2.0F, 4.0F, 2.0F),
            PartPose.ZERO
        );
        body.addOrReplaceChild(
            "right_bust",
            CubeListBuilder.create()
                .texOffs(36, 49)
                .addBox(-3.0F, -2.0F, -2.3F, 3.0F, 4.0F, 3.0F),
            PartPose.offsetAndRotation(0.0F, 3.5F, -1.0F, -0.42F, 0.0F, 0.08F)
        );
        body.addOrReplaceChild(
            "left_bust",
            CubeListBuilder.create()
                .texOffs(37, 53)
                .mirror()
                .addBox(0.0F, -2.0F, -2.3F, 3.0F, 4.0F, 3.0F),
            PartPose.offsetAndRotation(0.0F, 3.5F, -1.0F, -0.42F, 0.0F, -0.08F)
        );
        PartDefinition tailBase = body.addOrReplaceChild(
            "tail_base",
            CubeListBuilder.create()
                .texOffs(43, 23)
                .addBox(-1.0F, 0.0F, 0.0F, 2.0F, 1.0F, 6.0F),
            PartPose.offsetAndRotation(0.0F, 11.5F, 2.0F, -0.26F, 0.0F, 0.0F)
        );
        tailBase.addOrReplaceChild(
            "tail_tip",
            CubeListBuilder.create()
                .texOffs(41, 22)
                .addBox(-1.0F, 0.0F, 0.0F, 2.0F, 1.0F, 7.0F),
            PartPose.offsetAndRotation(0.0F, 1.0F, 6.0F, 1.13F, 0.0F, 0.0F)
        );

        root.addOrReplaceChild(
            "right_arm",
            CubeListBuilder.create()
                .texOffs(36, 44)
                .addBox(-1.0F, -2.0F, -1.0F, 2.0F, 12.0F, 2.0F)
                .texOffs(34, 24)
                .addBox(-2.0F, 5.0F, -2.0F, 4.0F, 2.0F, 4.0F)
                .texOffs(36, 20)
                .addBox(-3.0F, 7.0F, -3.0F, 6.0F, 5.0F, 6.0F),
            PartPose.offset(-4.0F, 2.0F, 0.0F)
        );
        root.addOrReplaceChild(
            "left_arm",
            CubeListBuilder.create()
                .texOffs(36, 45)
                .mirror()
                .addBox(-1.0F, -2.0F, -1.0F, 2.0F, 12.0F, 2.0F)
                .texOffs(35, 24)
                .addBox(-2.0F, 5.0F, -2.0F, 4.0F, 2.0F, 4.0F)
                .texOffs(36, 20)
                .addBox(-3.0F, 7.0F, -3.0F, 6.0F, 5.0F, 6.0F),
            PartPose.offset(4.0F, 2.0F, 0.0F)
        );

        PartDefinition rightLeg = root.addOrReplaceChild(
            "right_leg",
            CubeListBuilder.create()
                .texOffs(34, 43)
                .addBox(-1.5F, 0.0F, -2.0F, 3.0F, 12.0F, 4.0F),
            PartPose.offset(-1.55F, 12.0F, 0.0F)
        );
        rightLeg.addOrReplaceChild(
            "outer_skirt",
            CubeListBuilder.create()
                .texOffs(34, 21)
                .addBox(-1.0F, -0.5F, -2.5F, 1.0F, 3.25F, 5.5F, new CubeDeformation(0.35F)),
            PartPose.rotation(0.0F, 0.0F, 0.09F)
        );
        rightLeg.addOrReplaceChild(
            "inner_skirt",
            CubeListBuilder.create()
                .texOffs(35, 23)
                .addBox(0.0F, -0.5F, -2.5F, 1.0F, 2.5F, 5.5F, new CubeDeformation(0.35F)),
            PartPose.rotation(0.0F, 0.0F, 0.09F)
        );

        PartDefinition leftLeg = root.addOrReplaceChild(
            "left_leg",
            CubeListBuilder.create()
                .texOffs(35, 42)
                .mirror()
                .addBox(-1.5F, 0.0F, -2.0F, 3.0F, 12.0F, 4.0F),
            PartPose.offset(1.55F, 12.0F, 0.0F)
        );
        leftLeg.addOrReplaceChild(
            "outer_skirt",
            CubeListBuilder.create()
                .texOffs(37, 23)
                .mirror()
                .addBox(0.0F, -0.5F, -2.5F, 1.0F, 3.25F, 5.5F, new CubeDeformation(0.35F)),
            PartPose.rotation(0.0F, 0.0F, -0.09F)
        );
        leftLeg.addOrReplaceChild(
            "inner_skirt",
            CubeListBuilder.create()
                .texOffs(36, 21)
                .mirror()
                .addBox(-1.0F, -0.5F, -2.5F, 1.0F, 2.5F, 5.5F, new CubeDeformation(0.35F)),
            PartPose.rotation(0.0F, 0.0F, -0.09F)
        );

        return LayerDefinition.create(mesh, 128, 64);
    }

    @Override
    public void setupAnim(
        CodexNpcEntity entity,
        float limbSwing,
        float limbSwingAmount,
        float ageInTicks,
        float netHeadYaw,
        float headPitch
    ) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        UseAnim useAnimation = entity.getUseItem().getUseAnimation();
        if (entity.isUsingItem() && (useAnimation == UseAnim.EAT || useAnimation == UseAnim.DRINK)) {
            boolean mainHand = entity.getUsedItemHand() == InteractionHand.MAIN_HAND;
            HumanoidArm usedArm = mainHand ? entity.getMainArm() : entity.getMainArm().getOpposite();
            ModelPart arm = usedArm == HumanoidArm.RIGHT ? rightArm : leftArm;
            float bite = Mth.abs(Mth.cos(ageInTicks * 0.55F)) * 0.16F;
            arm.xRot = -1.35F + bite;
            arm.yRot = usedArm == HumanoidArm.RIGHT ? -0.20F : 0.20F;
            arm.zRot = usedArm == HumanoidArm.RIGHT ? 0.08F : -0.08F;
            head.xRot += Mth.sin(ageInTicks * 0.55F) * 0.035F;
        }

        float speech = entity.clientSpeechTicks() > 0 ? Mth.sin(ageInTicks * 0.7F) * 0.10F : 0.0F;
        float hurt = entity.hurtTime > 0 ? 0.28F : 0.0F;
        leftEar.zRot = 0.08F + speech + hurt;
        rightEar.zRot = -0.08F - speech - hurt;
        leftEar.xRot = -0.08F + Mth.sin(ageInTicks * 0.13F) * 0.025F;
        rightEar.xRot = leftEar.xRot;

        float tailSwing = Mth.sin(ageInTicks * (limbSwingAmount > 0.05F ? 0.30F : 0.13F));
        tailBase.xRot = -0.26F;
        tailBase.yRot = tailSwing * (limbSwingAmount > 0.05F ? 0.44F : 0.22F);
        tailTip.xRot = 1.13F;
        tailTip.yRot = tailSwing * 0.18F;

        // These angles are part of the original queen-cat silhouette.
        if (!entity.isUsingItem()) {
            rightArm.zRot += 0.26F;
            leftArm.zRot -= 0.26F;
        }
    }
}
