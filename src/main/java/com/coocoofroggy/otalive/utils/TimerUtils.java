package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.GlobalObject;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimerUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimerUtils.class);
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(1);
    static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();

    public static void startLoopScheduler() {
        final Runnable scanner = TimerUtils::scanLoop;
        SCHEDULER.scheduleAtFixedRate(scanner, 0, 10, TimeUnit.SECONDS);
    }

    public static void scanLoop() {
        try {
            LinkedHashMap<String, Integer> initialTitleToDeviceCount = TssUtils.fetchTitleToDeviceCount();

            GlobalObject globalObject = MongoUtils.fetchGlobalObject();
            boolean gdmfScanResult = PallasUtils.runGdmfScanner(globalObject);
            boolean tssScanResult = TssUtils.runTssScanner(globalObject);
            // If something was signed, unsigned, or changed
            if (gdmfScanResult || tssScanResult) {
                // Keep running the scanners until everything settles
                do {
                    LOGGER.info("Running scanners again until everything settles.");
                } while (PallasUtils.runGdmfScanner(globalObject) || TssUtils.runTssScanner(globalObject));

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
}
