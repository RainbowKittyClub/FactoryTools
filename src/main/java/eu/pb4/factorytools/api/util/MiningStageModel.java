package eu.pb4.factorytools.api.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.factorytools.api.resourcepack.ModelModifiers;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.factorytools.impl.ModInit;
import eu.pb4.polymer.resourcepack.api.AssetPaths;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.extras.api.ResourcePackExtras;
import eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.BasicItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mining stage (crack) model, drawn by {@link VirtualDestroyStage} instead of its full cube.
 *
 * @param stages         one model for each of the 10 destroy stages
 * @param transformation display transformation, relative to the center of the block
 */
public record MiningStageModel(List<LazyItemStack> stages, Matrix4fc transformation) {
    private static final Logger LOGGER = LoggerFactory.getLogger(MiningStageModel.class);
    // Keeps the cracks just outside the faces they copy, like the cube's 1.01 scale
    private static final Vec3 INFLATION = new Vec3(0.08, 0.08, 0.08);
    private static final Map<Identifier, List<LazyItemStack>> STAGES = new TreeMap<>();

    public MiningStageModel {
        if (stages.size() != VirtualDestroyStage.MODELS.length) {
            throw new IllegalArgumentException("Expected " + VirtualDestroyStage.MODELS.length + " stages, got " + stages.size());
        }
        stages = List.copyOf(stages);
        transformation = new Matrix4f(transformation);
    }

    public static MiningStageModel of(Identifier blockModel, Matrix4fc transformation) {
        return new MiningStageModel(stagesOf(blockModel), transformation);
    }

    public static MiningStageModel of(Identifier blockModel) {
        return of(blockModel, new Matrix4f());
    }

    /**
     * Crack models with the geometry of a block model and every texture replaced by the stage's crack texture.
     * They are generated into the resource pack, so request them before it's built.
     */
    public static List<LazyItemStack> stagesOf(Identifier blockModel) {
        var source = geometrySource(blockModel);
        synchronized (STAGES) {
            return STAGES.computeIfAbsent(source, id -> {
                var list = new ArrayList<LazyItemStack>(VirtualDestroyStage.MODELS.length);
                for (int i = 0; i < VirtualDestroyStage.MODELS.length; i++) {
                    list.add(ItemDisplayElementUtil.getModel(stageModelId(id, i)));
                }
                return List.copyOf(list);
            });
        }
    }

    public ItemStack stage(int stage) {
        return this.stages.get(Math.clamp(stage, 0, this.stages.size() - 1)).get();
    }

    static void addToResourcePack(ResourcePackBuilder builder) {
        List<Identifier> sources;
        synchronized (STAGES) {
            sources = List.copyOf(STAGES.keySet());
        }

        var textures = new Identifier[VirtualDestroyStage.MODELS.length];
        for (int i = 0; i < textures.length; i++) {
            textures[i] = VirtualDestroyStage.stageTexture(builder, i);
        }

        for (var source : sources) {
            JsonArray cracked = null;
            try {
                cracked = crackElements(builder, source);
                if (cracked == null) {
                    LOGGER.warn("Couldn't find the elements of model '{}', its mining stages will be a full cube", source);
                }
            } catch (Exception e) {
                LOGGER.warn("Couldn't read the elements of model '{}', its mining stages will be a full cube", source, e);
            }
            if (cracked == null) {
                cracked = new JsonArray();
                cracked.add(crackElement(cubeElement()));
            }

            var base = stageModelId(source, -1);
            var baseModel = new JsonObject();
            baseModel.add("elements", cracked);
            builder.addData(AssetPaths.model(base) + ".json", baseModel.toString().getBytes(StandardCharsets.UTF_8));

            for (int i = 0; i < VirtualDestroyStage.MODELS.length; i++) {
                var model = stageModelId(source, i);
                var texture = textures[i];
                builder.addData(AssetPaths.model(model) + ".json", ModelAsset.builder().parent(base)
                        .texture("all", texture).texture("particle", texture).build());
                builder.addData(AssetPaths.itemAsset(ResourcePackExtras.bridgeModel(model)),
                        new ItemAsset(new BasicItemModel(model), ItemAsset.Properties.DEFAULT).toJson().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static Identifier stageModelId(Identifier source, int stage) {
        return Identifier.fromNamespaceAndPath(ModInit.ID, "mining_stage/" + source.getNamespace() + "/" + source.getPath() + "/" + (stage < 0 ? "base" : stage));
    }

    // Models without elements of their own share their parent's crack models
    private static Identifier geometrySource(Identifier model) {
        for (int depth = 0; depth < 32; depth++) {
            var json = readJarModel(model);
            if (json == null || json.has("elements") || !json.has("parent")) {
                return model;
            }
            model = Identifier.parse(json.get("parent").getAsString());
        }
        return model;
    }

    @Nullable
    private static JsonObject readJarModel(Identifier model) {
        try {
            var data = ResourceUtils.getJarData("assets/" + model.getNamespace() + "/models/" + model.getPath() + ".json");
            return data != null ? JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    private static JsonArray crackElements(ResourcePackBuilder builder, Identifier source) {
        var elements = resolveElements(builder, source);
        if (elements == null) {
            return null;
        }
        var cracked = new JsonArray();
        for (var element : elements) {
            var crack = crackElement(element.getAsJsonObject());
            if (crack != null) {
                cracked.add(crack);
            }
        }
        return cracked;
    }

    // Elements are copied as json, since a codec round trip drops values vanilla requires, like a rotation's origin
    @Nullable
    private static JsonArray resolveElements(ResourcePackBuilder builder, Identifier model) {
        for (int depth = 0; depth < 32; depth++) {
            var data = builder.getDataOrSource(AssetPaths.model(model) + ".json");
            if (data == null) {
                return null;
            }
            JsonObject json;
            try {
                json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Throwable e) {
                LOGGER.warn("Failed to read model '{}'", model, e);
                return null;
            }
            if (json.has("elements")) {
                return json.getAsJsonArray("elements");
            } else if (!json.has("parent")) {
                return null;
            }
            model = Identifier.parse(json.get("parent").getAsString());
        }
        return null;
    }

    // Same faces, slightly inflated, with position based uvs like vanilla's breaking overlay
    @Nullable
    private static JsonObject crackElement(JsonObject element) {
        var faces = element.getAsJsonObject("faces");
        if (faces == null || faces.isEmpty()) {
            return null;
        }
        var from = vec(element.getAsJsonArray("from"));
        var to = vec(element.getAsJsonArray("to"));

        var crackFaces = new JsonObject();
        for (var key : faces.keySet()) {
            var direction = Direction.byName(key);
            if (direction != null) {
                var uv = new JsonArray();
                defaultUv(from, to, direction).forEach(uv::add);
                var face = new JsonObject();
                face.add("uv", uv);
                face.addProperty("texture", "#all");
                crackFaces.add(key, face);
            }
        }

        var crack = new JsonObject();
        crack.add("from", array(withinModelBounds(from.subtract(INFLATION))));
        crack.add("to", array(withinModelBounds(to.add(INFLATION))));
        if (element.has("rotation")) {
            crack.add("rotation", element.get("rotation").deepCopy());
        }
        if (element.has("shade")) {
            crack.add("shade", element.get("shade").deepCopy());
        }
        crack.add("faces", crackFaces);
        return crack;
    }

    private static JsonObject cubeElement() {
        var element = new JsonObject();
        element.add("from", array(Vec3.ZERO));
        element.add("to", array(new Vec3(16, 16, 16)));
        var faces = new JsonObject();
        for (var direction : Direction.values()) {
            faces.add(direction.getSerializedName(), new JsonObject());
        }
        element.add("faces", faces);
        return element;
    }

    // Vanilla rejects a model with an element reaching past -16 or 32
    private static Vec3 withinModelBounds(Vec3 vec) {
        return new Vec3(Math.clamp(vec.x, -16, 32), Math.clamp(vec.y, -16, 32), Math.clamp(vec.z, -16, 32));
    }

    private static Vec3 vec(JsonArray array) {
        return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }

    private static JsonArray array(Vec3 vec) {
        var array = new JsonArray();
        array.add(vec.x);
        array.add(vec.y);
        array.add(vec.z);
        return array;
    }

    // Wraps a face lying outside the block back onto the texture before clamping it
    private static List<Float> defaultUv(Vec3 from, Vec3 to, Direction direction) {
        var uv = new ArrayList<>(ModelModifiers.getDefaultUV(from, to, direction));
        for (int axis = 0; axis < 2; axis++) {
            float min = Math.min(uv.get(axis), uv.get(axis + 2));
            float max = Math.max(uv.get(axis), uv.get(axis + 2));
            float shift = (float) Math.floor(min / 16) * 16;
            if (shift != 0 && max - shift <= 16) {
                uv.set(axis, uv.get(axis) - shift);
                uv.set(axis + 2, uv.get(axis + 2) - shift);
            }
            uv.set(axis, Math.clamp(uv.get(axis), 0, 16));
            uv.set(axis + 2, Math.clamp(uv.get(axis + 2), 0, 16));
        }
        return uv;
    }
}
