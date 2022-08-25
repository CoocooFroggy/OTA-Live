package com.coocoofroggy.otalive.objects.gdmf;

import java.io.InputStream;

public class DocumentationBundle {
    private final String humanReadableUpdateName;
    private final InputStream prefsImage;

    public DocumentationBundle(String humanReadableUpdateName, InputStream prefsImage) {
        this.humanReadableUpdateName = humanReadableUpdateName;
        this.prefsImage = prefsImage;
    }

    public InputStream getPrefsImage() {
        return prefsImage;
    }

    public String getHumanReadableUpdateName() {
        return humanReadableUpdateName;
    }
}
