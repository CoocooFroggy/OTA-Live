package com.coocoofroggy.otalive.objects.gdmf;

import java.io.InputStream;

public class DocumentationBundle {
    private final String humanReadableUpdateName;
    private final InputStream prefsImage;
    // ReadMeSummary.html converted to Markdown
    private final String readMeSummaryMd;
    // ReadMe.html In HTML
    private final String readMeFullHtml;

    public DocumentationBundle(String humanReadableUpdateName, InputStream prefsImage, String readMeSummaryMd, String readMeFullHtml) {
        this.humanReadableUpdateName = humanReadableUpdateName;
        this.prefsImage = prefsImage;
        this.readMeSummaryMd = readMeSummaryMd;
        this.readMeFullHtml = readMeFullHtml;
    }

    public InputStream getPrefsImage() {
        return prefsImage;
    }

    public String getHumanReadableUpdateName() {
        return humanReadableUpdateName;
    }

    public String getReadMeSummaryMd() {
        return readMeSummaryMd;
    }

    public String getReadMeFullHtml() {
        return readMeFullHtml;
    }
}
