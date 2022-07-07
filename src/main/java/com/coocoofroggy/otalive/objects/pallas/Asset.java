package com.coocoofroggy.otalive.objects.pallas;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Objects;

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
    private String suDocumentationId;
    // Only exists on regular, not documentation, requests
    @SerializedName("SupportedDevices")
    private List<String> supportedDevices;

    // Our own
    private String humanReadableName;

    public Asset() {
    }

    // https://www.sitepoint.com/implement-javas-equals-method-correctly/
    @Override
    public boolean equals(Object obj) {
        // self check
        if (this == obj)
            return true;
        // null check
        if (obj == null)
            return false;
        // type check and cast
        if (getClass() != obj.getClass())
            return false;
        Asset asset = (Asset) obj;
        // field comparison
        return Objects.equals(osVersion, asset.osVersion)
                && Objects.equals(baseUrl, asset.baseUrl)
                && Objects.equals(relativePath, asset.relativePath)
                && Objects.equals(buildId, asset.buildId)
                && Objects.equals(suDocumentationId, asset.suDocumentationId)
                && Objects.equals(supportedDevices, asset.supportedDevices);
        // We don't check humanReadableName because one of the assets may not have fetched it yet
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

    public String getSuDocumentationId() {
        return suDocumentationId;
    }

    public Asset setSuDocumentationId(String suDocumentationId) {
        this.suDocumentationId = suDocumentationId;
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

    public String getHumanReadableName() {
        return humanReadableName;
    }

    public Asset setHumanReadableName(String humanReadableName) {
        this.humanReadableName = humanReadableName;
        return this;
    }
}
