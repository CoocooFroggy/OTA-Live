package com.coocoofroggy.otalive.utils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MINUTES;

public class TimerUtils {
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    public static void runScannerEvery10Minutes() {
        final Runnable scanner = PallasUtils::runScanner;
        scheduler.scheduleAtFixedRate(scanner, 0, 1, MINUTES);
    }
}
