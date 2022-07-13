package com.coocoofroggy.otalive.objects;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.util.List;

public class QueuedDevUpload {
    ZipFile otaZip;
    List<ZipArchiveEntry> devFiles;
    String path;

    public QueuedDevUpload(ZipFile otaZip, List<ZipArchiveEntry> devFiles, String path) {
        this.otaZip = otaZip;
        this.devFiles = devFiles;
        this.path = path;
    }

    public ZipFile getOtaZip() {
        return otaZip;
    }

    public List<ZipArchiveEntry> getDevFiles() {
        return devFiles;
    }

    public String getPath() {
        return path;
    }
}
