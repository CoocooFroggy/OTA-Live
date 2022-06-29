package com.coocoofroggy.otalive.objects;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

public class GlobalObject {
    @BsonId
    private ObjectId id;
    private List<String> assetAudiences;
    private List<String> processedBuildIdDeviceCombo;

    private String guildId;
    private String channelId;

    public GlobalObject() {
    }

    public ObjectId getId() {
        return id;
    }

    public GlobalObject setId(ObjectId id) {
        this.id = id;
        return this;
    }

    public List<String> getAssetAudiences() {
        if (assetAudiences == null)
            assetAudiences = new ArrayList<>();
        return assetAudiences;
    }

    public GlobalObject setAssetAudiences(List<String> assetAudiences) {
        this.assetAudiences = assetAudiences;
        return this;
    }

    public List<String> getProcessedBuildIdDeviceCombo() {
        if (processedBuildIdDeviceCombo == null)
            processedBuildIdDeviceCombo = new ArrayList<>();
        return processedBuildIdDeviceCombo;
    }

    public GlobalObject setProcessedBuildIdDeviceCombo(List<String> processedBuildIdDeviceCombo) {
        this.processedBuildIdDeviceCombo = processedBuildIdDeviceCombo;
        return this;
    }

    public String getGuildId() {
        return guildId;
    }

    public GlobalObject setGuildId(String guildId) {
        this.guildId = guildId;
        return this;
    }

    public String getChannelId() {
        return channelId;
    }

    public GlobalObject setChannelId(String channelId) {
        this.channelId = channelId;
        return this;
    }
}
