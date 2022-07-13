package com.coocoofroggy.otalive.utils;

import com.azure.core.http.rest.Response;
import com.azure.storage.blob.models.BlockBlobItem;
import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.objects.QueuedDevUpload;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimerUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimerUtils.class);
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(1);
    static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();
    private static final List<QueuedDevUpload> QUEUED_DEV_UPLOADS = new ArrayList<>();

    public static void startLoopScheduler() {
        final Runnable scanner = TimerUtils::scanLoop;
        SCHEDULER.scheduleAtFixedRate(scanner, 0, 10, TimeUnit.SECONDS);
    }

    public static void scanLoop() {
        try {
            LinkedHashMap<String, Integer> initialTitleToDeviceCount = TssUtils.fetchTitleToDeviceCount();

            GlobalObject globalObject = MongoUtils.fetchGlobalObject();
            boolean gdmfScanResult = PallasUtils.runGdmfScanner(globalObject);
            // TODO: Async
            // Upload everything queued
            uploadEverythingQueued();
            boolean tssScanResult = TssUtils.runTssScanner(globalObject);
            // If something was signed, unsigned, or changed
            if (gdmfScanResult || tssScanResult) {
                // Keep running the scanners until everything settles
                do {
                    LOGGER.info("Running scanners again until everything settles.");
                    gdmfScanResult = PallasUtils.runGdmfScanner(globalObject);
                    uploadEverythingQueued();
                    tssScanResult = TssUtils.runTssScanner(globalObject);
                } while (gdmfScanResult || tssScanResult);

                // Once everything is settled, send the embed with changes
                Guild guild = Main.jda.getGuildById(globalObject.getGuildId());
                TextChannel channel = guild.getTextChannelById(globalObject.getChannelId());
                channel.sendMessageEmbeds(TssUtils.signedFirmwareEmbed(initialTitleToDeviceCount).build()).queue();
            }
        } catch (Exception e) {
            LOGGER.error("Caught an exception. Finishing loop.", e);
            PallasUtils.shutdownPresenceMonitor();
            TssUtils.shutdownPresenceMonitor();
        }
    }

    private static void uploadEverythingQueued() throws IOException {
        // TODO: Discord status for this
        LOGGER.info("Uploading queued uploads.");
        for (QueuedDevUpload queuedDevUpload : QUEUED_DEV_UPLOADS) {
            ZipFile otaZip = queuedDevUpload.getOtaZip();
            for (ZipArchiveEntry devFile : queuedDevUpload.getDevFiles()) {
                int attempt = 0;
                int maxAttempts = 3;
                while (true) {
                    Response<BlockBlobItem> response = AzureUtils.uploadInputStream(otaZip.getInputStream(devFile), devFile.getSize(), queuedDevUpload.getPath());
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
            }
            QUEUED_DEV_UPLOADS.remove(queuedDevUpload);
        }
    }

    public static List<QueuedDevUpload> getQueuedDevUploads() {
        return QUEUED_DEV_UPLOADS;
    }
}
