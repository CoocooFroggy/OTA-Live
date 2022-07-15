package com.coocoofroggy.otalive.objects.gdmf;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class GdmfResponse {
    @SerializedName("Assets")
    private List<Asset> assets;

    public GdmfResponse() {
    }

    public List<Asset> getAssets() {
        return assets;
    }

    public GdmfResponse setAssets(List<Asset> assets) {
        this.assets = assets;
        return this;
    }
}
