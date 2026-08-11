package cn.codex.minecraftbridge.forge.client;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import cn.codex.minecraftbridge.client.BridgeConfig;
import cn.codex.minecraftbridge.forge.CodexNpcEntity;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CodexNpcRenderer extends MobRenderer<CodexNpcEntity, CodexCatgirlModel> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        MinecraftCodexBridge.MOD_ID,
        "textures/entity/codex_catgirl.png"
    );
    private static final ResourceLocation CUSTOM_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        MinecraftCodexBridge.MOD_ID,
        "dynamic/codex_catgirl_custom"
    );

    private final ResourceLocation texture;

    public CodexNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new CodexCatgirlModel(context.bakeLayer(CodexCatgirlModel.LAYER)), 0.5F);
        texture = loadConfiguredTexture();
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(CodexNpcEntity entity) {
        return texture;
    }

    private static ResourceLocation loadConfiguredTexture() {
        try {
            BridgeConfig config = BridgeConfig.load();
            Path configured = Path.of(config.npcSkinPath);
            Path skinPath = configured.isAbsolute()
                ? configured
                : Minecraft.getInstance().gameDirectory.toPath().resolve(configured).normalize();
            if (!Files.isRegularFile(skinPath)) return TEXTURE;
            try (InputStream input = Files.newInputStream(skinPath)) {
                NativeImage image = NativeImage.read(input);
                if (image.getWidth() != 128 || image.getHeight() != 64) {
                    image.close();
                    return TEXTURE;
                }
                Minecraft.getInstance().getTextureManager().register(CUSTOM_TEXTURE, new DynamicTexture(image));
                return CUSTOM_TEXTURE;
            }
        } catch (IOException | RuntimeException ignored) {
            return TEXTURE;
        }
    }

    @Override
    protected void scale(CodexNpcEntity entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    protected void setupRotations(CodexNpcEntity entity, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick) {
        super.setupRotations(entity, poseStack, ageInTicks, rotationYaw, partialTick);
        if (entity.isDowned()) {
            poseStack.translate(0.15, 0.05, 0);
            poseStack.mulPose(Axis.ZP.rotationDegrees(82.0F));
        }
    }
}
