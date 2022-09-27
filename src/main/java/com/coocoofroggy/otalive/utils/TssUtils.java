package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.BuildIdentity;
import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.objects.SigningStatus;
import com.coocoofroggy.otalive.objects.gdmf.Asset;
import com.dd.plist.*;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.IOUtils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TssUtils {
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

    private static ScheduledExecutorService presenceScheduler;
    private static double tssProgressCurrent = 0.0;
    private static double tssProgressTotal = 1.0;
    static boolean somethingGotUnsigned = false;

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

    public static BuildIdentity buildIdentityFromUrl(String urlString, String boardId) throws Exception {
        URL url = new URL(urlString);
        ZipFile otaZip = new ZipFile(new HttpChannel(url), "BM: " + urlString, StandardCharsets.UTF_8.name(), true, true);
        ZipArchiveEntry buildManifestEntry = otaZip.getEntry("AssetData/boot/BuildManifest.plist");
        InputStream buildManifestInputStream = otaZip.getInputStream(buildManifestEntry);

        NSDictionary rootDict = (NSDictionary) PropertyListParser.parse(buildManifestInputStream);
        NSObject[] buildIdentities = ((NSArray) rootDict.objectForKey("BuildIdentities")).getArray();
        // Loop through all the identities in BM
        for (NSObject buildIdentityObj : buildIdentities) {
            if (!buildIdentityObj.getClass().equals(NSDictionary.class)) return null;
            NSDictionary buildIdentityPlist = (NSDictionary) buildIdentityObj;
            // Get info of this current build identity
            NSDictionary info = (NSDictionary) buildIdentityPlist.objectForKey("Info");
            String currentBoardId = info.objectForKey("DeviceClass").toString();
            if (!currentBoardId.equalsIgnoreCase(boardId)) continue;
            BuildIdentity buildIdentity = new BuildIdentity(((NSData) buildIdentityPlist.objectForKey("UniqueBuildID")).getBase64EncodedData())
                    .setApBoardID(buildIdentityPlist.objectForKey("ApBoardID").toString())
                    .setApChipID(buildIdentityPlist.objectForKey("ApChipID").toString())
                    .setApSecurityDomain(buildIdentityPlist.objectForKey("ApSecurityDomain").toString());
            otaZip.close();
            return buildIdentity;
        }
        otaZip.close();
        return null;
    }

    public static boolean runTssScanner(GlobalObject globalObject) {
        somethingGotUnsigned = false;

        // Update presence every so often
        presenceScheduler = Executors.newScheduledThreadPool(1);
        presenceScheduler.scheduleAtFixedRate(() -> Main.jda.getPresence().setPresence(OnlineStatus.ONLINE,
                        Activity.playing("Checking TSS... " +
                                DECIMAL_FORMAT.format((tssProgressCurrent / tssProgressTotal) * 100) + "%")),
                0, 5, TimeUnit.SECONDS);

        LOGGER.debug("Starting TSS scanner...");

        List<BuildIdentity> buildIdentities = MongoUtils.fetchAllSignedBuildIdentities();

        tssProgressTotal = buildIdentities.size();

        ArrayList<CompletableFuture<Void>> completableFutures = new ArrayList<>();

        for (BuildIdentity buildIdentity : buildIdentities) {
            // Make the async code
            Runnable runnable = buildTssRunnable(globalObject, buildIdentity);
            // Queue it to run
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    runnable.run();
                } finally {
                    // Increment the progress upon completion
                    tssProgressCurrent++;
                }
            }, TimerUtils.EXECUTOR); // In our custom thread pool for *speed*
            // Add it to a list to check if they're all completed
            completableFutures.add(future);
            // Rate-limit so TSS isn't that mad
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted sleeping for TSS rate limit.", e);
            }
        }

        // Wait for them all to complete
        CompletableFuture<Void> c = CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0]));
        c.join();

        shutdownPresenceMonitor();
        Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
        LOGGER.debug("Finished scanning TSS.");
        return somethingGotUnsigned;
    }

    private static Runnable buildTssRunnable(GlobalObject globalObject, BuildIdentity buildIdentity) {
        return () -> {
            int attempts = 0;
            int maxAttempts = 3;

            while (true) {
                Asset asset = buildIdentity.getAsset();
                LOGGER.debug("Checking if " + buildIdentity + " is signed...");

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
                    } else if (signingStatus == SigningStatus.UNKNOWN) {
                        Thread.sleep(500);
                        // Try again (because of while true loop) but on third try, just quit
                        if (++attempts > maxAttempts) {
                            Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
                            LOGGER.error("Unknown signing status for " + buildIdentity + ". Quitting, max attempts was three.");
                            return;
                        }
                        LOGGER.debug("Unknown signing status for " + buildIdentity + ". Likely soft rate-limited. Trying again.");
                        continue;
                    }
                } catch (IOException | InterruptedException e) {
                    // Try again (because of while true loop) but on third try, just quit
                    if (++attempts > maxAttempts) {
                        Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
                        LOGGER.error("Caught exception. Quitting, max attempts was three.");
                        throw new RuntimeException(e);
                    }
                    LOGGER.error("Caught exception. Trying again.", e);
                    continue;
                }
                return;
            }
        };
    }

    static void shutdownPresenceMonitor() {
        if (presenceScheduler != null) {
            presenceScheduler.shutdown();
            tssProgressCurrent = 0.0;
            tssProgressTotal = 1.0;
            try {
                //noinspection ResultOfMethodCallIgnored
                presenceScheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted shutting down presence monitor, but we don't care.");
            }
        }
    }

    public static List<EmbedBuilder> firmwareEmbeds() {
        return firmwareEmbeds(null);
    }

    // This one compares it to the initial one
    public static List<EmbedBuilder> firmwareEmbeds(LinkedHashMap<String, Integer> initialTitleToDeviceCount) {
        LinkedHashMap<String, Integer> titleToDeviceCount = fetchTitleToDeviceCount();

        List<EmbedBuilder> embedsToSend = new ArrayList<>();

        StringBuilder stringBuilder = new StringBuilder();
        EmbedBuilder embedBuilder = new EmbedBuilder()
                .setColor(new Color(0x38C700));

        boolean initialRun = true;
        Iterator<Map.Entry<String, Integer>> iterator = titleToDeviceCount.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            // Parse spacer entries
            if (entry.getValue() == -1) {
                // If it's ≥ the 2nd embed, finish up the old one and make a new one
                if (!initialRun) {
                    // Tidy up and add to list
                    embedBuilder.setDescription(stringBuilder);
                    embedsToSend.add(embedBuilder);
                    // Reset variables for next one
                    stringBuilder = new StringBuilder();
                    embedBuilder = new EmbedBuilder().setColor(new Color(0x38C700));
                } else {
                    initialRun = false;
                }

                // Ignore weird OTA versioning
                embedBuilder.setTitle(entry.getKey().replaceFirst("^9\\.9\\.", ""));
                continue;
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

            // After the last entry, finish up this embed
            if (!iterator.hasNext()) {
                embedBuilder.setDescription(stringBuilder);
                embedsToSend.add(embedBuilder);
            }
        }

        // Check for non-null — null if we're not comparing with any initial
        if (initialTitleToDeviceCount != null) {
            stringBuilder = new StringBuilder();
            // Check if anything was unsigned, and say so
            boolean somethingWasUnsigned = false;
            for (Map.Entry<String, Integer> entry : initialTitleToDeviceCount.entrySet()) {
                // Skip spacer entries
                if (entry.getValue() <= -1) continue;
                // If the current titleToDeviceCount doesn't have a key that was there initially, it got unsigned
                if (!titleToDeviceCount.containsKey(entry.getKey())) {
                    somethingWasUnsigned = true;
                    stringBuilder.append("**UNSIGNED →** ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" devices.\n");
                }
            }
            if (somethingWasUnsigned) {
                embedBuilder = new EmbedBuilder()
                        .setTitle("Unsigned")
                        .setColor(new Color(0xB00000))
                        .setDescription(stringBuilder);
                embedsToSend.add(embedBuilder);
            }
        }

        return embedsToSend;
    }

    @NotNull
    static LinkedHashMap<String, Integer> fetchTitleToDeviceCount() {
        List<BuildIdentity> sortedBuildIdentities = fetchSortedBuildIdentities();
        // What we're populating with the loop
        LinkedHashMap<String, Integer> titleToDeviceCount = new LinkedHashMap<>();
        // Key: CommonVersion (16.0)
        // Value: List of BuildIdentities
        TreeMap<String, List<BuildIdentity>> versionCategories = new TreeMap<>();
        for (BuildIdentity buildIdentity : sortedBuildIdentities) {
            String commonVersion = buildIdentity.getAsset().getOsVersion().replaceFirst("^9\\.9\\.", "");
            // Shortcut to add into the List in the HashMap (versionCategories)
            // https://stackoverflow.com/a/3019388/13668740
            versionCategories.computeIfAbsent(commonVersion, k -> new ArrayList<>()).add(buildIdentity);
        }
        // Loop through these version categories and create
        // the asset descriptions and device counts
        for (Map.Entry<String, List<BuildIdentity>> entry : versionCategories.entrySet()) {
            String version = entry.getKey();
            // Spacerπ
            titleToDeviceCount.put(version, -1); // -1 means spacer

            List<BuildIdentity> buildIdentities = entry.getValue();
            for (BuildIdentity buildIdentity : buildIdentities) {
                // iOS 16 Developer Beta 2 (`18A24`)
                String key = buildIdentity.getAsset().getHumanReadableName() + " (`" + buildIdentity.getAsset().getBuildId() + "`)";
                Integer count = titleToDeviceCount.get(key);
                if (count == null) count = 0;
                titleToDeviceCount.put(key, count + 1);
            }
        }
        return titleToDeviceCount;
    }

    @NotNull
    private static List<BuildIdentity> fetchSortedBuildIdentities() {
        List<BuildIdentity> buildIdentities = MongoUtils.fetchAllSignedBuildIdentities();
        // Sort this by Build ID
        buildIdentities.sort(Comparator.comparing(o -> o.getAsset().getBuildId()));
        return buildIdentities; // Now sorted
    }
}
