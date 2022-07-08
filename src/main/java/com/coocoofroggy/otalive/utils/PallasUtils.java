package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.BuildIdentity;
import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.objects.pallas.Asset;
import com.coocoofroggy.otalive.objects.pallas.PallasResponse;
import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PallasUtils {
    public static final Pattern DEVICE_NAME_PATTERN = Pattern.compile("(.*?)\\d.*");
    private static final Gson gson = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger(PallasUtils.class);
    private static final String[] DEV_KEYWORDS = new String[]{
            "development", "kasan", "debug", "diag", "factory", "device_map", "dev.im4p"
    };
    private static final String[] SPECIAL_CASE = new String[]{
            "DeviceTree", "iBoot", "LLB", "sep-firmware", "iBEC", "iBSS", "diag"
    };
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.0");

    private static ScheduledExecutorService presenceScheduler;
    private static double scanProgressCurrent = 0.0;
    private static double scanProgressTotal = 1.0;
    static boolean newFirmwareReleased = false;

    public static boolean runGdmfScanner(GlobalObject globalObject) {
        newFirmwareReleased = false;

        // Update presence every so often
        presenceScheduler = Executors.newScheduledThreadPool(1);
        presenceScheduler.scheduleAtFixedRate(() -> Main.jda.getPresence().setPresence(OnlineStatus.ONLINE,
                        Activity.playing("Scanning... " +
                                DECIMAL_FORMAT.format((scanProgressCurrent / scanProgressTotal) * 100) + "%")),
                0, 5, TimeUnit.SECONDS);

        LOGGER.debug("Starting scanner...");
        // Processed assets to skip
        List<Asset> processedAssets = MongoUtils.fetchAllProcessedAssets();

        List<String> lines;
        try {
            lines = FileUtils.readLines(new File("devices.txt"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Couldn't read devices.txt lines.");
            throw new RuntimeException(e);
        }

        scanProgressTotal = lines.size();

        ArrayList<CompletableFuture<Void>> completableFutures = new ArrayList<>();

        // Loop through all devices
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("//")) {
                scanProgressCurrent++;
                continue;
            }
            String[] split = line.split(";");
            String device = split[0];
            String boardId = split[1];
            String deviceHumanName = split[2];

            // Make the async code
            Runnable runnable = buildGdmfDeviceRunnable(globalObject, processedAssets, device, boardId, deviceHumanName);
            // Queue it to run
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    runnable.run();
                } finally {
                    // Increment the progress upon completion
                    scanProgressCurrent++;
                }
            }, TimerUtils.EXECUTOR_SERVICE); // In our custom thread pool for *speed*
            // Add it to a list to check if they're all completed
            completableFutures.add(future);
        }

        // Wait for them all to complete
        CompletableFuture<Void> c = CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0]));
        c.join();

        shutdownPresenceMonitor();
        Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);

        LOGGER.debug("Scanner finished.");
        return newFirmwareReleased;
    }

    @NotNull
    private static Runnable buildGdmfDeviceRunnable(GlobalObject globalObject, List<Asset> processedAssets, String device, String boardId, String deviceHumanName) {
        // Define our runnable
        return () -> {
            int attempts = 0;
            int maxAttempts = 3;

            while (true) {
                try {
                    for (String assetAudience : globalObject.getAssetAudiences()) {
                        PallasResponse suPallasResponse = fetchSuPallasResponse(device, boardId, assetAudience);
                        if (suPallasResponse == null) continue;

                        for (Asset asset : suPallasResponse.getAssets()) {
                            // If this is a new asset, process it
                            if (!processedAssets.contains(asset)) {
                                LOGGER.info("NEW: " + asset);
                                newFirmwareReleased = true;
                                Guild guild = Main.jda.getGuildById(globalObject.getGuildId());
                                TextChannel channel = guild.getTextChannelById(globalObject.getChannelId());

                                // Get device name
                                Matcher matcher = DEVICE_NAME_PATTERN.matcher(device);
                                // If there's no device name we messed up. But continue
                                if (!matcher.find()) {
                                    LOGGER.error("Cannot find device name for " + device);
                                    continue;
                                }

                                PallasResponse docPallasResponse = fetchDocPallasResponse(device, assetAudience, asset, matcher);
                                if (docPallasResponse == null) continue;

                                // Not iterating through assets because there should not be multiple documentations. Just use the first.
                                // Get human-readable name
                                asset.setHumanReadableName(
                                        humanReadableFromDocUrl(docPallasResponse.getAssets().get(0).getFullUrl()));

                                EmbedBuilder embedBuilder = new EmbedBuilder();
                                // iOS16Beta2 — iPhone11,8
                                embedBuilder.setTitle("Released: " + asset.getHumanReadableName() + " — " + asset.getSupportedDevicesPretty())
                                        .addField("Build ID", asset.getBuildId(), true)
                                        .addField("OS Version", asset.getOsVersion(), true)
                                        .addField("SU Documentation ID", asset.getSuDocumentationId(), true)
                                        .addField("Device Name", deviceHumanName, true)
                                        .addField("URL", asset.getFullUrl(), false);

                                // Send initial message
                                Message message = channel.sendMessageEmbeds(embedBuilder.build()).complete();

                                // Scan for dev files
                                List<String> devFiles = listDevFiles(asset.getFullUrl(), boardId);
                                String collect = "None found";
                                if (!devFiles.isEmpty())
                                    collect = devFiles.stream().collect(Collectors.joining("`\n`", "`", "`"));
                                embedBuilder.setDescription("**Dev Files**\n" + collect.substring(0, Math.min(4081, collect.length())));

                                // Update the message
                                message.editMessageEmbeds(embedBuilder.build()).queue();

                                // Get BuildIdentity data for TSS
                                BuildIdentity buildIdentity = TssUtils.buildIdentityFromUrl(asset.getFullUrl(), boardId);
                                // Should never trigger. But just in case
                                if (buildIdentity == null) {
                                    LOGGER.error("BM for " + device + " (" + asset.getSuDocumentationId() + ") is null. Skipping this asset—not adding it to Build Identities collection.");
                                    channel.sendMessage("<@353561670934855681> couldn't parse data from BM 🚨.").queue();
                                    message.editMessage("Failed to parse BuildManifest—you may see the embed below duplicated at a later point in time.").queue();
                                    continue;
                                }
                                // Mark this asset as processed in memory
                                processedAssets.add(asset);
                                // Mark this asset as processed in DB and add to TSS queue
                                buildIdentity.setAsset(asset);
                                MongoUtils.insertBuildIdentity(buildIdentity);
                            } else {
                                LOGGER.debug("Old: " + asset);
                            }
                        }
                    }
                    break;
                } catch (Exception e) {
                    // Try again (because of while true loop) but on third try, just quit
                    if (++attempts > maxAttempts) {
                        Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
                        LOGGER.error("Caught exception. Quitting, max attempts was three.");
                        throw new RuntimeException(e);
                    }
                    LOGGER.error("Caught exception. Trying again.", e);
                }
            }
        };
    }

    static void shutdownPresenceMonitor() {
        if (presenceScheduler != null) {
            presenceScheduler.shutdown();
            scanProgressCurrent = 0.0;
            scanProgressTotal = 1.0;
            try {
                //noinspection ResultOfMethodCallIgnored
                presenceScheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted shutting down presence monitor, but we don't care.");
            }
        }
    }

    @Nullable
    private static PallasResponse fetchDocPallasResponse(String device, String assetAudience, Asset asset, Matcher matcher) throws IOException {
        PallasResponse docPallasResponse;
        int docAttempts = 0;
        while (true) {
            // Get Documentation from GDMF
            String docResponseString = docRequest(assetAudience, asset.getSuDocumentationId(), matcher.group(1));
            docPallasResponse = parseJwt(docResponseString);
            // If there are no assets, retry
            if (docPallasResponse == null || docPallasResponse.getAssets() == null || docPallasResponse.getAssets().isEmpty()) {
                docAttempts++;
                // If we hit max attempts, just go on to next asset
                if (docAttempts >= 3) {
                    LOGGER.error("Skipping asset for " + device + " because documentation (" + asset.getSuDocumentationId() + ") is null or empty.");
                    return null;
                }
                continue;
            }
            // Otherwise break out of loop: we have the asset
            break;
        }
        return docPallasResponse;
    }

    @Nullable
    private static PallasResponse fetchSuPallasResponse(String device, String boardId, String assetAudience) throws IOException {
        PallasResponse suPallasResponse;
        int suAttempts = 0;
        while (true) {
            // Get SU from GDMF
            String suResponseString = suRequest(device, boardId, assetAudience);
            try {
                suPallasResponse = parseJwt(suResponseString);
            } catch (JsonSyntaxException e) {
                suAttempts++;
                // If we hit max attempts, just go on to next asset audience for this device
                if (suAttempts >= 3) {
                    LOGGER.error("Skipping asset audience (" + assetAudience + ") for " + device + ":", e);
                    return null;
                }
                continue;
            }
            // If the response gives us null, retry
            if (suPallasResponse == null || suPallasResponse.getAssets() == null || suPallasResponse.getAssets().isEmpty()) {
                suAttempts++;
                // If we hit max attempts, just go on to next asset audience for this device
                if (suAttempts >= 3) {
                    LOGGER.error("Skipping asset audience (" + assetAudience + ") for " + device + " because JSON deserialization was null.");
                    return null;
                }
                continue;
            }
            // Otherwise break out of loop: we have the assets
            break;
        }
        return suPallasResponse;
    }

    public static String suRequest(String device, String boardId, String assetAudience) throws IOException {
        Map<String, Object> requestMap = Map.of(
                "ClientVersion", 2,
                "AssetType", "com.apple.MobileAsset.SoftwareUpdate",
                "AssetAudience", assetAudience,
                "ProductType", device,
                "HWModelStr", boardId,
                // These two are to not get deltas
                "ProductVersion", "0",
                "BuildVersion", "0",
                "CompatibilityVersion", 20
        );
        String s = gson.toJson(requestMap);

        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("https://gdmf.apple.com/v2/assets");
        httpPost.setEntity(new StringEntity(s));
        httpPost.addHeader("Content-Type", "application/json");

        CloseableHttpResponse response = client.execute(httpPost);
        String responseString = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

        response.close();
        client.close();

        return responseString;
    }

    public static String docRequest(String assetAudience, String suDocumentationId, String deviceName) throws IOException {
        Map<String, Object> requestMap = Map.of(
                "ClientVersion", 2,
                "AssetType", "com.apple.MobileAsset.SoftwareUpdateDocumentation",
                "AssetAudience", assetAudience,
                "SUDocumentationID", suDocumentationId,
                "DeviceName", deviceName // iPhone, iPad, iPod
        );
        String s = gson.toJson(requestMap);

        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("https://gdmf.apple.com/v2/assets");
        httpPost.setEntity(new StringEntity(s));
        httpPost.addHeader("Content-Type", "application/json");

        CloseableHttpResponse response = client.execute(httpPost);
        String responseString = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

        response.close();
        client.close();

        return responseString;
    }

    public static PallasResponse parseJwt(String encodedString) throws JsonSyntaxException {
        // The part between the two dots is what we need
        String meat = encodedString.split("\\.")[1];
        String decoded = new String(Base64.decodeBase64(meat), StandardCharsets.UTF_8);
        return gson.fromJson(decoded, PallasResponse.class);
    }

    private static List<String> listDevFiles(String urlString, String boardId) throws IOException {
        URL url = new URL(urlString);
        ZipFile otaZip = new ZipFile(new HttpChannel(url), "Dev Files: " + urlString, StandardCharsets.UTF_8.name(), true, true);

        List<String> devFiles = new ArrayList<>();
        // Loop through all files and add them to devFiles
        entryLabel:
        for (Iterator<ZipArchiveEntry> it = otaZip.getEntries().asIterator(); it.hasNext(); ) {
            ZipArchiveEntry entry = it.next();
            String fileName = entry.getName();
            for (String keyword : DEV_KEYWORDS) {
                // If it's a dev file
                if (fileName.toLowerCase().contains(keyword)) {
                    // Check for special case
                    for (String s : SPECIAL_CASE) {
                        if (fileName.contains(s)) {
                            // Only add it to the list of dev files if it matches our board ID
                            if (fileName.toLowerCase().contains(boardId.substring(0, 4).toLowerCase()))
                                devFiles.add(fileName);
                            // It will only match one special case—no need to check others
                            continue entryLabel;
                        }
                    }
                    // if it's a plist file we usually don't care
                    if (fileName.endsWith(".plist") && !fileName.contains("device_map")) continue entryLabel;
                    // It's not a special case if we reach here
                    devFiles.add(fileName);
                    // Go to next entry—we already found match
                    continue entryLabel;
                }
            }
        }
        return devFiles;
    }

    public static String humanReadableFromDocUrl(String urlString) throws Exception {
        InputStream documentationStringsInputStream = documentationStringsInputStreamFromUrl(urlString);

        NSDictionary rootDict = (NSDictionary) PropertyListParser.parse(documentationStringsInputStream);
        return rootDict.objectForKey("HumanReadableUpdateName").toString();
    }

    private static InputStream documentationStringsInputStreamFromUrl(String urlString) throws IOException {
        URL url = new URL(urlString);
        ZipFile otaZip = new ZipFile(new HttpChannel(url), "Documentation: " + urlString, StandardCharsets.UTF_8.name(), true, true);
        ZipArchiveEntry documentationEntry = otaZip.getEntry("AssetData/en.lproj/documentation.strings");
        return otaZip.getInputStream(documentationEntry);
    }
}
