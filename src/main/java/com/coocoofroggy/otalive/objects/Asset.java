package com.coocoofroggy.otalive.objects;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class Asset {
    @SerializedName("OSVersion")
    private String osVersion;
    @SerializedName("__BaseURL")
    private String baseUrl;
    @SerializedName("__RelativePath")
    private String relativePath;
    @SerializedName("Build")
    private String buildId;
    @SerializedName("SUDocumentationID")
    private String longName;
    @SerializedName("SupportedDevices")
    private List<String> supportedDevices;

    public Asset() {
    }

    public String getOsVersion() {
        return osVersion;
    }

    public Asset setOsVersion(String osVersion) {
        this.osVersion = osVersion;
        return this;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Asset setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public Asset setRelativePath(String relativePath) {
        this.relativePath = relativePath;
        return this;
    }

    public String getFullUrl() {
        return baseUrl + relativePath;
    }

    public String getBuildId() {
        return buildId;
    }

    public Asset setBuildId(String buildId) {
        this.buildId = buildId;
        return this;
    }

    public String getLongName() {
        return longName;
    }

    public Asset setLongName(String longName) {
        this.longName = longName;
        return this;
    }

    public List<String> getSupportedDevices() {
        return supportedDevices;
    }

    public Asset setSupportedDevices(List<String> supportedDevices) {
        this.supportedDevices = supportedDevices;
        return this;
    }

    public String getSupportedDevicesPretty() {
        return String.join(", ", supportedDevices);
    }

    public String uniqueComboString() {
        return buildId + ";" + getSupportedDevicesPretty();
    }
}
