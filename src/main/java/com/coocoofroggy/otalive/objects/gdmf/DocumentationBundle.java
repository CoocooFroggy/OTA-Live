package com.coocoofroggy.otalive.objects.gdmf;

import java.io.InputStream;

public class DocumentationBundle {
    private final String humanReadableUpdateName;
    private final InputStream prefsImage;
    private final String readMeSummary;

    public DocumentationBundle(String humanReadableUpdateName, InputStream prefsImage, String readMeSummary) {
        this.humanReadableUpdateName = humanReadableUpdateName;
        this.prefsImage = prefsImage;
        this.readMeSummary = readMeSummary;
    }

    public InputStream getPrefsImage() {
        return prefsImage;
    }

    public String getHumanReadableUpdateName() {
        return humanReadableUpdateName;
    }

    public String getReadMeSummary() {
        return readMeSummary;
    }
}
