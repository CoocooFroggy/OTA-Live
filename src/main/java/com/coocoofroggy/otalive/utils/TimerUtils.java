package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.GlobalObject;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class TimerUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimerUtils.class);
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    public static void startLoopScheduler() {
        final Runnable scanner = TimerUtils::scanLoop;
        // DEBUG
//        scheduler.scheduleAtFixedRate(scanner, 0, 1, TimeUnit.MINUTES);
    }

    public static void scanLoop() {
        try {
            HashMap<String, Integer> initialBuildIdSignedDevicesCount = TssUtils.getBuildIdSignedDevicesCount();

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
                channel.sendMessageEmbeds(TssUtils.signedFirmwareEmbed(initialBuildIdSignedDevicesCount).build()).queue();
            }
        } catch (Exception e) {
            LOGGER.error("Caught an exception. Finishing loop.", e);
        }
    }
}
