package com.coocoofroggy.otalive.objects.pallas;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class PallasResponse {
    @SerializedName("Assets")
    private List<Asset> assets;

    public PallasResponse() {
    }

    public List<Asset> getAssets() {
        return assets;
    }

    public PallasResponse setAssets(List<Asset> assets) {
        this.assets = assets;
        return this;
    }
}
