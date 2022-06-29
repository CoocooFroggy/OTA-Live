package com.coocoofroggy.otalive.utils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MINUTES;

public class TimerUtils {
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    public static void runScannerEvery10Minutes() {
        final Runnable beeper = PallasUtils::runScanner;
        final ScheduledFuture<?> handle =
                scheduler.scheduleAtFixedRate(beeper, 0, 10, MINUTES);
    }
}
