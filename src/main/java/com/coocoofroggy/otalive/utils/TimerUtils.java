package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.GlobalObject;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;

import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimerUtils {
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    public static void runScannerEvery10Minutes() {
        final Runnable scanner = TimerUtils::scanLoop;
        scheduler.scheduleAtFixedRate(scanner, 0, 1, TimeUnit.MINUTES);
    }

    public static void scanLoop() {
        HashMap<String, Integer> initialBuildIdSignedDevicesCount = TssUtils.getBuildIdSignedDevicesCount();

        GlobalObject globalObject = MongoUtils.fetchGlobalObject();
        boolean gdmfScanResult = PallasUtils.runGdmfScanner(globalObject);
        boolean tssScanResult = TssUtils.runTssScanner(globalObject);
        if (gdmfScanResult || tssScanResult) {
            Guild guild = Main.jda.getGuildById(globalObject.getGuildId());
            TextChannel channel = guild.getTextChannelById(globalObject.getChannelId());
            channel.sendMessageEmbeds(TssUtils.signedFirmwareEmbed(initialBuildIdSignedDevicesCount).build()).queue();
        }
    }
}
