package com.coocoofroggy.otalive.objects;

public class BuildIdentity {
    private String buildIdentityB64;
    private boolean signed;
    private String ApBoardID;
    private String ApChipID;
    private String ApSecurityDomain;

    private Asset asset;

    public BuildIdentity() {
    }

    public BuildIdentity(String buildIdentityB64) {
        this.buildIdentityB64 = buildIdentityB64;
        this.signed = true;
    }

    public String getBuildIdentityB64() {
        return buildIdentityB64;
    }

    public BuildIdentity setBuildIdentityB64(String buildIdentityB64) {
        this.buildIdentityB64 = buildIdentityB64;
        return this;
    }

    public boolean isSigned() {
        return signed;
    }

    public BuildIdentity setSigned(boolean signed) {
        this.signed = signed;
        return this;
    }

    public String getApBoardID() {
        return ApBoardID;
    }

    public BuildIdentity setApBoardID(String apBoardID) {
        ApBoardID = apBoardID;
        return this;
    }

    public String getApChipID() {
        return ApChipID;
    }

    public BuildIdentity setApChipID(String apChipID) {
        ApChipID = apChipID;
        return this;
    }

    public String getApSecurityDomain() {
        return ApSecurityDomain;
    }

    public BuildIdentity setApSecurityDomain(String apSecurityDomain) {
        ApSecurityDomain = apSecurityDomain;
        return this;
    }

    public Asset getAsset() {
        return asset;
    }

    public BuildIdentity setAsset(Asset asset) {
        this.asset = asset;
        return this;
    }
}
