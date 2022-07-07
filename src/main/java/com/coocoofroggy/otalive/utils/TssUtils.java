package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.BuildIdentity;
import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.objects.SigningStatus;
import com.coocoofroggy.otalive.objects.pallas.Asset;
import com.dd.plist.*;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TssUtils {
    public static final Thread PRESENCE_THREAD = new Thread("Presence");
    private static final Logger LOGGER = LoggerFactory.getLogger(TssUtils.class);
    private static final String TSS_REQUEST_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
                <dict>
                    <key>ApECID</key>
                    <integer>1</integer>
                    <key>UniqueBuildID</key>
                    <data>{{UBID}}</data>
                    <key>ApChipID</key>
                    <string>{{CPID}}</string>
                    <key>ApBoardID</key>
                    <string>{{BDID}}</string>
                    <key>ApSecurityDomain</key>
                    <string>{{SDOM}}</string>
                    <key>ApNonce</key>
                    <data>q83vASNFZ4mrze8BI0VniavN7wE=</data>
                    <key>@ApImg4Ticket</key>
                    <true/>
                    <key>ApSecurityMode</key>
                    <true/>
                    <key>ApProductionMode</key>
                    <true/>
                    <key>SepNonce</key>
                    <data>z59YgWI9Pv3oNas53hhBJXc4S0E=</data>
                </dict>
            </plist>
            """;
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.0");

    private static final ScheduledExecutorService PRESENCE_SCHEDULER =
            Executors.newScheduledThreadPool(1);
    private static double tssProgressPercent = 0.0;

    public static BuildIdentity buildIdentityFromBm(File bm, String boardId) throws PropertyListFormatException, IOException, ParseException, ParserConfigurationException, SAXException {
        NSDictionary rootDict = (NSDictionary) PropertyListParser.parse(bm);
        NSObject[] buildIdentities = ((NSArray) rootDict.objectForKey("BuildIdentities")).getArray();
        // Loop through all the identities in BM
        for (NSObject buildIdentityObj : buildIdentities) {
            if (!buildIdentityObj.getClass().equals(NSDictionary.class)) return null;
            NSDictionary buildIdentityPlist = (NSDictionary) buildIdentityObj;
            // Get info of this current build identity
            NSDictionary info = (NSDictionary) buildIdentityPlist.objectForKey("Info");
            String currentBoardId = info.objectForKey("DeviceClass").toString();
            if (!currentBoardId.equalsIgnoreCase(boardId)) continue;
            return new BuildIdentity(((NSData) buildIdentityPlist.objectForKey("UniqueBuildID")).getBase64EncodedData())
                    .setApBoardID(buildIdentityPlist.objectForKey("ApBoardID").toString())
                    .setApChipID(buildIdentityPlist.objectForKey("ApChipID").toString())
                    .setApSecurityDomain(buildIdentityPlist.objectForKey("ApSecurityDomain").toString());
        }
        return null;
    }

    public static SigningStatus tssCheckSigned(BuildIdentity buildIdentity) throws IOException {
        String requestString = TSS_REQUEST_TEMPLATE.replaceFirst("\\{\\{UBID}}", buildIdentity.getBuildIdentityB64())
                .replaceFirst("\\{\\{CPID}}", buildIdentity.getApChipID())
                .replaceFirst("\\{\\{BDID}}", buildIdentity.getApBoardID())
                .replaceFirst("\\{\\{SDOM}}", buildIdentity.getApSecurityDomain());

        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("http://gs.apple.com/TSS/controller?action=2");
        httpPost.setEntity(new StringEntity(requestString));
        httpPost.addHeader("Content-Type", "application/xml");

        CloseableHttpResponse response = client.execute(httpPost);
        String responseString = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

        response.close();
        client.close();

        if (responseString.contains("STATUS=94&MESSAGE=This device isn't eligible for the requested build."))
            return SigningStatus.UNSIGNED;
        else if (responseString.contains("STATUS=0&MESSAGE=SUCCESS"))
            return SigningStatus.SIGNED;
        else
            return SigningStatus.UNKNOWN;
    }

    public static File downloadBmFromUrl(String url) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder("pzb", "-g", "AssetData/boot/BuildManifest.plist", url);
        Process process = processBuilder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        while (reader.readLine() != null) ;
        process.waitFor();
        return new File("BuildManifest.plist");
    }

    public static boolean runTssScanner(GlobalObject globalObject) {
        boolean somethingGotUnsigned = false;

        int attempts = 0;
        int maxAttempts = 3;

        // Every 2 seconds, update this
        try {
            PRESENCE_SCHEDULER.scheduleAtFixedRate(() -> {
                Main.jda.getPresence().setPresence(OnlineStatus.ONLINE,
                        Activity.playing(DECIMAL_FORMAT.format(tssProgressPercent) + "% checking TSS..."));
            }, 0, 5, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOGGER.debug("Rejected execution of presence for TSS. This is normal on completion.");
        }

        while (true) {
            LOGGER.debug("Starting TSS scanner...");

            List<BuildIdentity> buildIdentities = null;
            try {
                buildIdentities = MongoUtils.fetchAllSignedBuildIdentities();
            } catch (Exception e) {
                // Try again (because of while true loop) but on third try, just quit
                if (++attempts > maxAttempts) {
                    Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
                    LOGGER.error("Interrupted. Quitting, max attempts was three.");
                    throw new RuntimeException(e);
                }
                LOGGER.error("Interrupted. Trying again.");
                continue;
            }
            for (int i = 0; i < buildIdentities.size(); i++) {
                BuildIdentity buildIdentity = buildIdentities.get(i);
                Asset asset = buildIdentity.getAsset();
                LOGGER.debug("Checking if " + asset.getSupportedDevicesPretty() + " " + asset.getHumanReadableName() + " (" + asset.getBuildId() + ")" + " is unsigned...");

                tssProgressPercent = ((double) i / buildIdentities.size()) * 100;

                try {
                    SigningStatus signingStatus = tssCheckSigned(buildIdentity);
                    if (signingStatus == SigningStatus.UNSIGNED) {
                        LOGGER.info("Marking " + asset.getBuildId() + " " + asset.getSupportedDevicesPretty() + " as unsigned.");
                        MongoUtils.markBuildIdentityAsUnsigned(buildIdentity);
                        somethingGotUnsigned = true;

                        // Notify us
                        Guild guild = Main.jda.getGuildById(globalObject.getGuildId());
                        TextChannel channel = guild.getTextChannelById(globalObject.getChannelId());

                        EmbedBuilder embedBuilder = new EmbedBuilder();
                        embedBuilder.setTitle("Unsigned: " + asset.getHumanReadableName() + " — " + asset.getSupportedDevicesPretty())
                                .setColor(new Color(0xB00000))
                                .addField("Build ID", asset.getBuildId(), true)
                                .addField("OS Version", asset.getOsVersion(), true)
                                .addField("SU Documentation ID", asset.getSuDocumentationId(), true)
                                .addField("URL", asset.getFullUrl(), false);

                        channel.sendMessageEmbeds(embedBuilder.build()).queue();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    LOGGER.error("Continuing anyways.");
                }
            }
            shutdownPresenceMonitor();
            Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
            LOGGER.debug("Finished scanning TSS.");
            return somethingGotUnsigned;
        }
    }

    static void shutdownPresenceMonitor() {
        PRESENCE_SCHEDULER.shutdown();
        try {
            // TODO: Ignore this somehow
            PRESENCE_SCHEDULER.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted shutting down presence monitor, but we don't care.");
        }
    }

    public static EmbedBuilder signedFirmwareEmbed() {
        return signedFirmwareEmbed(null);
    }

    // This one compares it to the initial one
    public static EmbedBuilder signedFirmwareEmbed(LinkedHashMap<String, Integer> initialTitleToDeviceCount) {
        LinkedHashMap<String, Integer> titleToDeviceCount = fetchTitleToDeviceCount();

        StringBuilder stringBuilder = new StringBuilder();

        for (Map.Entry<String, Integer> entry : titleToDeviceCount.entrySet()) {
            // Parse spacer entries
            if (entry.getValue() <= -1) {
                // Ignore weird OTA versioning
                if (entry.getKey().startsWith("9.9.")) {
                    stringBuilder.append("\n**__").append(entry.getKey().substring(4)).append("__**\n");
                    continue;
                }
            }
            // Check for non-null — null if we're not comparing with any initial
            if (initialTitleToDeviceCount != null) {
                // If there is a new firmware added
                if (!initialTitleToDeviceCount.containsKey(entry.getKey())) {
                    stringBuilder.append("**NEW →** ");
                }
                // If it's not a new firmware, but the values are different
                else if (!initialTitleToDeviceCount.get(entry.getKey()).equals(entry.getValue())) {
                    stringBuilder.append("**CHANGED →** ");
                }
            }
            // iOS 16 Developer Beta 2 (`18A24`): 5 devices.
            stringBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append(" devices.\n");
        }
        // Check for non-null — null if we're not comparing with any initial
        if (initialTitleToDeviceCount != null) {
            // Check if anything was unsigned, and say so
            for (Map.Entry<String, Integer> entry : initialTitleToDeviceCount.entrySet()) {
                // Skip spacer entries
                if (entry.getValue() <= -1) continue;
                // If the current buildIdSignedDevicesCount doesn't have a key that was there initially, it got unsigned
                if (!titleToDeviceCount.containsKey(entry.getKey()))
                    stringBuilder.append("**UNSIGNED →** ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" devices.\n");
            }
        }

        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle("Signed OTAs")
                .setColor(new Color(0x38C700))
                .setDescription(stringBuilder.toString());

        return embedBuilder;
    }

    @NotNull
    static LinkedHashMap<String, Integer> fetchTitleToDeviceCount() {
        List<BuildIdentity> sortedBuildIdentities = fetchSortedBuildIdentities();
        // What we're populating with the loop
        LinkedHashMap<String, Integer> titleToDeviceCount = new LinkedHashMap<>();
        // Space between versions
        String previousVersion = "";
        for (BuildIdentity buildIdentity : sortedBuildIdentities) {
            // If it's another version, leave space in between
            String osVersion = buildIdentity.getAsset().getOsVersion();
            // If it's another version, leave space in between
            if (!osVersion.equals(previousVersion)) {
                titleToDeviceCount.put(osVersion, -1); // -1 means special
            }
            // iOS 16 Developer Beta 2 (`18A24`)
            String key = buildIdentity.getAsset().getHumanReadableName() + " (`" + buildIdentity.getAsset().getBuildId() + "`)";
            Integer count = titleToDeviceCount.get(key);
            if (count == null) count = 0;
            titleToDeviceCount.put(key, count + 1);
        }
        return titleToDeviceCount;
    }

    @NotNull
    private static List<BuildIdentity> fetchSortedBuildIdentities() {
        List<BuildIdentity> buildIdentities = MongoUtils.fetchAllSignedBuildIdentities();
        // Sort this by OS version
        buildIdentities.sort(Comparator.comparing(o -> o.getAsset().getOsVersion()));
        return buildIdentities; // Now sorted
    }
}
