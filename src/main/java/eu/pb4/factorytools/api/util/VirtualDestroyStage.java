package eu.pb4.factorytools.api.util;

import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.factorytools.impl.ModInit;
import eu.pb4.factorytools.impl.ServerPlayNetExtF;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.extras.api.ResourcePackExtras;
import eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.BasicItemModel;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

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

        self.setModels(marker.getMiningStageModels(state, pos, player));

        var vecPos = Vec3.atCenterOf(pos);

        if (self.getAttachment() == null || !self.getAttachment().getPos().equals(vecPos)) {
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
                    "all": "minecraft:block/destroy_stage_|ID|"
                  }
                }
                """;

        PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT.register(x -> {
            x.addPreFinishTask(MiningStageModel::addToResourcePack);
        });

        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(x -> {
            for (var i = 0; i < MODELS.length; i++) {
                x.addData("", new ItemAsset(new BasicItemModel(Identifier.fromNamespaceAndPath(ModInit.ID, "block/special/destroy_stage_" + i)), ItemAsset.Properties.DEFAULT).toJson().getBytes(StandardCharsets.UTF_8));
                x.addData("assets/factorytools/models/block/special/destroy_stage_" + i + ".json", model.replace("|ID|", "" + i).getBytes(StandardCharsets.UTF_8));
            }
        });
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
