package com.coocoofroggy.otalive.objects;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.util.List;

public class QueuedDevUpload {
    ZipFile otaZip;
    ZipArchiveEntry devFile;
    String path;

    public QueuedDevUpload(ZipFile otaZip, ZipArchiveEntry devFile, String path) {
        this.otaZip = otaZip;
        this.devFile = devFile;
        this.path = path;
    }

    public ZipFile getOtaZip() {
        return otaZip;
    }

    public ZipArchiveEntry getDevFile() {
        return devFile;
    }

    public String getPath() {
        return path;
    }
}
