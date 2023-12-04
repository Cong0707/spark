package me.lucko.spark.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import me.lucko.spark.proto.SparkProtos;

public class Data {
    private final String name;
    private final String uniqueId;

    public Data(String name, String uniqueId) {
        this.name = name;
        this.uniqueId = uniqueId;
    }

    public String getName() {
        return this.name;
    }

    public String getUniqueId() {
        return this.uniqueId;
    }

    public boolean isPlayer() {
        return this.uniqueId != null;
    }

    public JsonObject serialize() {
        JsonObject user = new JsonObject();
        user.add("type", new JsonPrimitive(isPlayer() ? "player" : "other"));
        user.add("name", new JsonPrimitive(this.name));
        if (this.uniqueId != null) {
            user.add("uniqueId", new JsonPrimitive(this.uniqueId));
        }
        return user;
    }

    public SparkProtos.CommandSenderMetadata toProto() {
        SparkProtos.CommandSenderMetadata.Builder proto = SparkProtos.CommandSenderMetadata.newBuilder()
                .setType(isPlayer() ? SparkProtos.CommandSenderMetadata.Type.PLAYER : SparkProtos.CommandSenderMetadata.Type.OTHER)
                .setName(this.name);

        if (this.uniqueId != null) {
            proto.setUniqueId(this.uniqueId);
        }

        return proto.build();
    }

    public static Data deserialize(JsonElement element) {
        JsonObject userObject = element.getAsJsonObject();
        String user = userObject.get("name").getAsJsonPrimitive().getAsString();
        String uuid = userObject.get("uniqueId").getAsJsonPrimitive().getAsString();
        return new Data(user, uuid);
    }
}
