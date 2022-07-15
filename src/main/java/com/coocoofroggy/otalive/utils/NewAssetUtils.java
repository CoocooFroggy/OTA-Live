package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.QueuedDevUpload;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class NewAssetUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(NewAssetUtils.class);
    public static final List<Callable<List<QueuedDevUpload>>> QUEUED_NEW_ASSET_CALLABLES = new ArrayList<>();
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.0");
    // This executor only runs two at a time—partial zip is sometimes slow and can timeout,
    // so this keeps it from being overrun.

    private static ScheduledExecutorService presenceScheduler;
    private static double progressCurrent = 0.0;
    private static double progressTotal = 1.0;

    public static List<QueuedDevUpload> processQueuedNewAssets() {
        // Update presence every so often
        presenceScheduler = Executors.newScheduledThreadPool(1);
        presenceScheduler.scheduleAtFixedRate(() -> Main.jda.getPresence().setPresence(OnlineStatus.ONLINE,
                        Activity.playing("Processing... " +
                                DECIMAL_FORMAT.format((progressCurrent / progressTotal) * 100) + "%")),
                0, 5, TimeUnit.SECONDS);

        LOGGER.debug("Processing new assets...");

        progressTotal = QUEUED_NEW_ASSET_CALLABLES.size();

        // We'll return this list for uploading
        List<QueuedDevUpload> queuedDevUploads = new ArrayList<>();

        ArrayList<CompletableFuture<Void>> completableFutures = new ArrayList<>();

        // We clone and clear the global list to prevent concurrent modifications.
        List<Callable<List<QueuedDevUpload>>> queuedNewAssetCallables = new ArrayList<>(QUEUED_NEW_ASSET_CALLABLES);
        QUEUED_NEW_ASSET_CALLABLES.clear();

        final ThreadPoolExecutor NEW_ASSET_EXECUTOR =
                new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        // Loop through every queued runnable.
        for (Callable<List<QueuedDevUpload>> callable : queuedNewAssetCallables) {
            // Queue it to run
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    queuedDevUploads.addAll(callable.call());
                } catch (Exception e) {
                    LOGGER.error("Exception while processing assets.", e);
                } finally {
                    // Increment the progress upon completion
                    progressCurrent++;
                }
            }, NEW_ASSET_EXECUTOR); // In our custom thread pool
            // Add it to a list to check if they're all completed
            completableFutures.add(future);
        }

        NEW_ASSET_EXECUTOR.shutdown();
        // Wait for them all to complete
        CompletableFuture<Void> c = CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0]));
        c.join();

        shutdownPresenceMonitor();
        Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);

        LOGGER.debug("New assets finished processing.");
        return queuedDevUploads;
    }

    static void shutdownPresenceMonitor() {
        if (presenceScheduler != null) {
            presenceScheduler.shutdown();
            progressCurrent = 0.0;
            progressTotal = 1.0;
            try {
                //noinspection ResultOfMethodCallIgnored
                presenceScheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted shutting down presence monitor, but we don't care.");
            }
        }
    }
}
