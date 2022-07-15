package com.coocoofroggy.otalive.objects;

import com.coocoofroggy.otalive.objects.gdmf.Asset;

public class BuildIdentity {
    private String buildIdentityB64;
    private boolean signed;
    private String apBoardID;
    private String apChipID;
    private String apSecurityDomain;

    private Asset asset;

    public BuildIdentity() {
    }

    @Override
    public String toString() {
        return "BuildIdentity{" +
                "buildIdentityB64='" + buildIdentityB64 + '\'' +
                ", signed=" + signed +
                ", apBoardID='" + apBoardID + '\'' +
                ", apChipID='" + apChipID + '\'' +
                ", apSecurityDomain='" + apSecurityDomain + '\'' +
                ", asset=" + asset +
                '}';
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
        return apBoardID;
    }

    public BuildIdentity setApBoardID(String apBoardID) {
        this.apBoardID = apBoardID;
        return this;
    }

    public String getApChipID() {
        return apChipID;
    }

    public BuildIdentity setApChipID(String apChipID) {
        this.apChipID = apChipID;
        return this;
    }

    public String getApSecurityDomain() {
        return apSecurityDomain;
    }

    public BuildIdentity setApSecurityDomain(String apSecurityDomain) {
        this.apSecurityDomain = apSecurityDomain;
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
