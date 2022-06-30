package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.GlobalObject;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimerUtils {
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    public static void runScannerEvery10Minutes() {
        final Runnable scanner = () -> {
            boolean gdmfScanResult = PallasUtils.runGdmfScanner();
            boolean tssScanResult = TssUtils.runTssScanner();
            if (gdmfScanResult || tssScanResult) {
                GlobalObject globalObject = MongoUtils.fetchGlobalObject();
                Guild guild = Main.jda.getGuildById(globalObject.getGuildId());
                TextChannel channel = guild.getTextChannelById(globalObject.getChannelId());
                channel.sendMessageEmbeds(TssUtils.signedFirmwareEmbed().build()).queue();
            }
        };
        scheduler.scheduleAtFixedRate(scanner, 0, 1, TimeUnit.MINUTES);
    }
}
