package eu.pb4.factorytools.api.util;

import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.factorytools.impl.ModInit;
import eu.pb4.factorytools.impl.ServerPlayNetExtF;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.extras.api.ResourcePackExtras;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class VirtualDestroyStage extends ElementHolder {
    public static final LazyItemStack[] MODELS = new LazyItemStack[10];
    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualDestroyStage.class);
    private static final Transformation CUBE_TRANSFORMATION = new Transformation(null, null, new Vector3f(1.01f), null);
    private final ItemDisplayElement main = new ItemDisplayElement();
    private final List<ItemDisplayElement> extraParts = new ArrayList<>();
    private List<MiningStageModel> models = List.of();
    private int state;


    public VirtualDestroyStage() {
        this.main.setItem(MODELS[0].get());
        this.main.setScale(new Vector3f(1.01f));
        this.addElement(this.main);
    }

    @SuppressWarnings("SameReturnValue")
    public static boolean updateState(ServerPlayer player, BlockPos pos, BlockState state, int i) {
        var self = ((ServerPlayNetExtF) player.connection).factorytools$getVirtualDestroyStage();
        var marker = i == -1 ? null : getMarker(state, pos, player);

        if (marker == null) {
            self.setState(-1);
            if (self.getAttachment() != null) {
                self.destroy();
            }
            return true;
        }

        var vecPos = Vec3.atCenterOf(pos);

        // Respawned rather than moved, so the new block's shape never shows at the old one
        if (self.getAttachment() != null && !self.getAttachment().getPos().equals(vecPos)) {
            self.destroy();
        }

        self.setModels(marker.getMiningStageModels(state, pos, player));

        if (self.getAttachment() == null) {
            ChunkAttachment.of(self, player.level(), vecPos);
        }

        self.setState(i);

        return true;
    }

    @Nullable
    private static Marker getMarker(BlockState state, BlockPos pos, ServerPlayer player) {
        if (state.getBlock() instanceof Marker marker && marker.showCustomMiningStageMarker(state, pos, player)) {
            return marker;
        }
        if (PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, state.getBlock()) instanceof Marker marker && marker.showCustomMiningStageMarker(state, pos, player)) {
            return marker;
        }
        return null;
    }

    public static void destroy(@Nullable ServerPlayer player) {
        if (player != null && player.connection != null) {
            ((ServerPlayNetExtF) player.connection).factorytools$getVirtualDestroyStage().destroy();
        }
    }

    @Override
    public void setAttachment(@Nullable HolderAttachment attachment) {
        if (attachment != null && this.getAttachment() == null) {
            // Otherwise a reattached holder spawns at the block it was last at
            this.currentPos = Vec3.ZERO;
        }
        super.setAttachment(attachment);
    }

    public void setState(int i) {
        if (this.state == i) {
            return;
        }

        this.state = i;
        this.updateItems();
        this.tick();
    }

    /**
     * Sets the models drawn instead of the full cube, one display each. Null or empty brings the cube back.
     */
    public void setModels(@Nullable List<MiningStageModel> models) {
        models = models != null ? List.copyOf(models) : List.of();
        if (this.models.equals(models)) {
            return;
        }

        this.models = models;
        if (models.isEmpty()) {
            this.main.setTransformation(CUBE_TRANSFORMATION);
        } else {
            this.main.setTransformation(models.getFirst().transformation());
        }

        while (this.extraParts.size() > Math.max(models.size() - 1, 0)) {
            this.removeElement(this.extraParts.removeLast());
        }
        for (int i = 1; i < models.size(); i++) {
            if (i > this.extraParts.size()) {
                var element = new ItemDisplayElement();
                element.setItem(this.itemFor(i));
                element.setTransformation(models.get(i).transformation());
                this.extraParts.add(element);
                this.addElement(element);
            } else {
                this.extraParts.get(i - 1).setTransformation(models.get(i).transformation());
            }
        }

        this.updateItems();
        this.tick();
    }

    private void updateItems() {
        this.main.setItem(this.itemFor(0));
        for (int i = 0; i < this.extraParts.size(); i++) {
            this.extraParts.get(i).setItem(this.itemFor(i + 1));
        }
    }

    private ItemStack itemFor(int part) {
        if (this.state < 0) {
            return ItemStack.EMPTY;
        }
        return this.models.isEmpty() ? MODELS[Math.min(this.state, MODELS.length - 1)].get() : this.models.get(part).stage(this.state);
    }


    static {
        ResourcePackExtras.forDefault().addBridgedModelsFolder(Identifier.fromNamespaceAndPath(ModInit.ID, "block/special"));

        for (int i = 0; i < MODELS.length; i++) {
            MODELS[i] = ItemDisplayElementUtil.getModel(Identifier.fromNamespaceAndPath(ModInit.ID, "block/special/destroy_stage_" + i));
        }

        var model =  """
                {
                  "parent": "factorytools:block/special/destroy_stage_base",
                  "textures": {
                    "all": "factorytools:block/special/crack_|ID|"
                  }
                }
                """;

        PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT.register(x -> {
            x.addPreFinishTask(VirtualDestroyStage::addCrackTextures);
            x.addPreFinishTask(MiningStageModel::addToResourcePack);
        });

        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(x -> {
            for (var i = 0; i < MODELS.length; i++) {
                x.addData(cubeModelPath(i), model.replace("|ID|", "" + i).getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    /**
     * Stage texture for crack models: vanilla's destroy_stage_N, made to blend like vanilla's breaking overlay
     * when drawn as an item model.
     */
    public static Identifier crackTexture(int stage) {
        return Identifier.fromNamespaceAndPath(ModInit.ID, "block/special/crack_" + stage);
    }

    // The crack texture, or vanilla's own if it couldn't be made
    static Identifier stageTexture(ResourcePackBuilder builder, int stage) {
        return builder.getResource(crackTexturePath(stage)) != null ? crackTexture(stage) : vanillaTexture(stage);
    }

    private static Identifier vanillaTexture(int stage) {
        return Identifier.withDefaultNamespace("block/destroy_stage_" + stage);
    }

    private static String crackTexturePath(int stage) {
        return "assets/" + ModInit.ID + "/textures/block/special/crack_" + stage + ".png";
    }

    private static String cubeModelPath(int stage) {
        return "assets/" + ModInit.ID + "/models/block/special/destroy_stage_" + stage + ".json";
    }

    // Vanilla multiplies the block by twice the crack's grey; an item model can only alpha-blend, so darker
    // pixels become translucent black and lighter ones translucent white, which gives nearly the same result
    private static void addCrackTextures(ResourcePackBuilder builder) {
        for (int i = 0; i < MODELS.length; i++) {
            var path = "assets/minecraft/textures/block/destroy_stage_" + i + ".png";
            var vanilla = builder.getDataOrSource(path);
            if (vanilla == null) {
                LOGGER.warn("Couldn't read '{}', stage {} will use it unblended", path, i);
                builder.addData(cubeModelPath(i), ModelAsset.builder().parent(Identifier.fromNamespaceAndPath(ModInit.ID, "block/special/destroy_stage_base"))
                        .texture("all", vanillaTexture(i)).build());
                continue;
            }
            var target = crackTexturePath(i);
            try {
                var image = ImageIO.read(new ByteArrayInputStream(vanilla));
                if (image == null) {
                    throw new IOException("Unsupported image format");
                }
                var out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                // getRGB treats grey PNGs as linear and brightens them; the shader samples the raw grey
                boolean grey = image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY;
                var raster = image.getRaster();
                double max = (1 << raster.getSampleModel().getSampleSize(0)) - 1;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        double value;
                        double alpha;
                        if (grey) {
                            value = raster.getSample(x, y, 0) / max;
                            alpha = raster.getNumBands() > 1 ? raster.getSample(x, y, 1) / max : 1;
                        } else {
                            int argb = image.getRGB(x, y);
                            value = (((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF)) / (3 * 255.0);
                            alpha = (argb >>> 24) / 255.0;
                        }
                        // Vanilla's crumbling shader discards pixels below 0.1 alpha
                        if (alpha < 0.1) {
                            continue;
                        }
                        double factor = 2 * value;
                        int overlay = (int) Math.round(Math.min(1, Math.abs(1 - factor)) * 255);
                        out.setRGB(x, y, (overlay << 24) | (factor < 1 ? 0x000000 : 0xFFFFFF));
                    }
                }
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(out, "png", bytes);
                builder.addData(target, bytes.toByteArray());
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Couldn't convert '{}', using it unblended", path, e);
                builder.addData(target, vanilla);
            }
        }
    }

    public interface Marker {
        default boolean showCustomMiningStageMarker(BlockState state, BlockPos pos, ServerPlayer player) {
            return true;
        }

        /**
         * Models shaped like the block, drawn in place of the full cube. Null keeps the cube.
         */
        @Nullable
        default List<MiningStageModel> getMiningStageModels(BlockState state, BlockPos pos, ServerPlayer player) {
            return null;
        }
    }
}
