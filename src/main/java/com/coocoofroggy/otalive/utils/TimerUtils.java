package com.coocoofroggy.otalive.utils;

import com.azure.core.http.rest.Response;
import com.azure.storage.blob.models.BlockBlobItem;
import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.objects.QueuedDevUpload;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.TextChannel;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class TimerUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimerUtils.class);
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(1);
    // This thread pool allows infinity threads to run, until we tell it to slow down
    // Will be shutdown and changed later to do that
    // Used for GDMF and TSS
    static ThreadPoolExecutor EXECUTOR = (ThreadPoolExecutor) Executors.newCachedThreadPool();
    // This counts the number of Azure uploadEverythingQueued() going on.
    // If it reaches 0, then when an uploader finishes, it can set the above executor back to normal speed.
    static int runningUploaders = 0;

    public static void startLoopScheduler() {
        final Runnable scanner = TimerUtils::scanLoop;
        SCHEDULER.scheduleAtFixedRate(scanner, 0, 10, TimeUnit.SECONDS);
    }

    /**
     * This method should be run as often as possible to stay up to date. It does the following:<br>
     * <ul>
     *     <li>Runs GDMF scanner, finding out what new assets have been released</li>
     *     <ul>
     *         <li>New assets are then processed and scanned for dev files</li>
     *         <li>Dev files are uploaded to Azure asynchronously, as the scan loop continues.
     *         The scan loop is slowed down to 2 threads (from ∞) while uploads are happening.</li>
     *     </ul>
     *     <li>Runs TSS scanner, finding out what BuildIdentities have been marked as unsigned</li>
     * </ul>
     *
     */
    public static void scanLoop() {
        try {
            boolean sendChangedEmbed = false;
            // Fetch the initial software -> device count before anything is fetched
            LinkedHashMap<String, Integer> initialTitleToDeviceCount = TssUtils.fetchTitleToDeviceCount();
            // Fetch the global object for Discord channels
            GlobalObject globalObject = MongoUtils.fetchGlobalObject();
            Guild guild = Main.jda.getGuildById(globalObject.getGuildId());
            if (guild == null) {
                LOGGER.error("Guild not found—unable to send GDMF/TSS messages.");
                return;
            }
            TextChannel channel = guild.getTextChannelById(globalObject.getChannelId());
            if (channel == null) {
                LOGGER.error("Channel not found—unable to send GDMF/TSS messages.");
                return;
            }
            // Run GDMF scanner
            boolean newAsset = GdmfUtils.runGdmfScanner(globalObject);
            // Process new assets: prepare the list of queued uploads.
            // We upload them after everything is done (at the bottom of this method)
            List<QueuedDevUpload> queuedDevUploads = NewAssetUtils.processQueuedNewAssets();

            // If there's a new asset
            if (newAsset) {
                sendChangedEmbed = true;
                // Keep running the GDMF scanner until everything settles
                do {
                    channel.sendMessage("Running GDMF scanner again until everything settles.").queue();
                    LOGGER.info("Running GDMF scanner again until everything settles.");
                    // Run GDMF scanner
                    newAsset = GdmfUtils.runGdmfScanner(globalObject);
                    // Process new assets: add to the list of queued uploads.
                    // We upload them after everything is done (at the bottom of this method)
                    queuedDevUploads.addAll(NewAssetUtils.processQueuedNewAssets());
                } while (newAsset);
            }
            // Run TSS scanner
            boolean tssChanged = TssUtils.runTssScanner(globalObject);
            // If something was unsigned
            if (tssChanged) {
                sendChangedEmbed = true;
                // Keep running the TSS scanner until everything settles
                do {
                    channel.sendMessage("Running TSS scanner again until everything settles.").queue();
                    LOGGER.info("Running TSS scanner again until everything settles.");
                    // Run TSS scanner
                    tssChanged = TssUtils.runTssScanner(globalObject);
                } while (tssChanged);
            }

            // If there's a new asset or something was unsigned
            if (sendChangedEmbed) {
                // Run the GDMF scanner one more time.
                // It's really fast, and during TSS checking, new assets could have appeared
                do {
                    channel.sendMessage("Running GDMF scanner post-TSS until everything settles.").queue();
                    LOGGER.info("Running GDMF scanner post-TSS until everything settles.");
                    // Run GDMF scanner
                    newAsset = GdmfUtils.runGdmfScanner(globalObject);
                    // Process new assets: add to the list of queued uploads.
                    // We upload them after everything is done (at the bottom of this method)
                    queuedDevUploads.addAll(NewAssetUtils.processQueuedNewAssets());
                } while (newAsset);

                // Once everything is settled, send the embed with the changes
                // All embeds (could be > 10)
                List<EmbedBuilder> firmwareEmbeds = TssUtils.firmwareEmbeds(initialTitleToDeviceCount);
                // Queued embeds to send in groups of 10
                List<MessageEmbed> queuedEmbeds = new ArrayList<>();
                for (int i = 0; i < firmwareEmbeds.size(); i++) {
                    if (i % 10 == 0 && i != 0) {
                        channel.sendMessageEmbeds(queuedEmbeds).queue();
                        queuedEmbeds = new ArrayList<>();
                    }
                    queuedEmbeds.add(firmwareEmbeds.get(i).build());
                }
                if (!queuedEmbeds.isEmpty())
                    channel.sendMessageEmbeds(queuedEmbeds).queue();
            }

            // Upload every new dev file queued, asynchronously
            new Thread(() -> {
                try {
                    uploadEverythingQueued(queuedDevUploads);
                } catch (InterruptedException e) {
                    LOGGER.error("Interrupted, unable to upload everything.", e);
                }
            }).start();
        } catch (Exception e) {
            LOGGER.error("Caught an exception. Finishing loop.", e);
            GdmfUtils.shutdownPresenceMonitor();
            TssUtils.shutdownPresenceMonitor();
        }
    }

    private static void uploadEverythingQueued(List<QueuedDevUpload> queuedDevUploads) throws InterruptedException {
        // Return if nothing to do
        if (queuedDevUploads.isEmpty()) return;

        // Add 1 to this
        runningUploaders++;

        // Finish all these threads
        EXECUTOR.shutdown();
        // Slow down GDMF / TSS to only x at a time
        EXECUTOR = new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

        LOGGER.info("Uploading queued uploads.");

        // List of everything running, so we can wait for it to finish later
        ArrayList<Callable<Object>> callables = new ArrayList<>();
        for (final QueuedDevUpload queuedDevUpload : queuedDevUploads) {
            Callable<Object> callable = Executors.callable(buildDevUploadRunnable(queuedDevUpload));
            callables.add(callable);
        }

        // Doesn't need to be static, we're barely calling it
        final ThreadPoolExecutor azureExecutor =
                new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        // This will run them all (two by two because of thread pool) and block until they're done
        azureExecutor.invokeAll(callables);
        azureExecutor.shutdown();

        runningUploaders--;

        // Basically, if this is the last uploader running, set the speed back to normal
        if (runningUploaders <= 0) {
            LOGGER.info("Setting speed back to normal.");
            // Return GDMF / TSS to infinite Threads
            EXECUTOR.shutdown();
            EXECUTOR = (ThreadPoolExecutor) Executors.newCachedThreadPool();
        }
    }

    private static Runnable buildDevUploadRunnable(QueuedDevUpload queuedDevUpload) {
        return () -> {
            ZipFile otaZip = queuedDevUpload.getOtaZip();
            ZipArchiveEntry devFile = queuedDevUpload.getDevFile();
            int attempt = 0;
            int maxAttempts = 3;
            while (true) {
                Response<BlockBlobItem> response;
                try {
                    String path = queuedDevUpload.getPath();
                    LOGGER.info("Starting Azure upload of " + path + ".");
                    response = AzureUtils.uploadInputStream(otaZip.getInputStream(devFile), devFile.getSize(), path);
                    LOGGER.info("Finished uploading " + path + ".");
                } catch (IOException e) {
                    if (attempt < maxAttempts) {
                        attempt++;
                        continue;
                    }
                    LOGGER.error("Caught exception in uploading dev runnable. Exiting.", e);
                    break;
                }
                // If it already exists, don't check response
                if (response != null) {
                    // Check if status code is 2xx
                    int statusCode = response.getStatusCode();
                    if ((statusCode / 100) != 2) {
                        LOGGER.error("Response code " + statusCode + " for " + queuedDevUpload.getPath() + " Azure upload.");
                        if (attempt < maxAttempts) {
                            attempt++;
                            continue;
                        }
                    }
                }
                break;
            }
        };
    }
}
