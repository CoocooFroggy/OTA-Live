package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.BuildIdentity;
import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.objects.QueuedDevUpload;
import com.coocoofroggy.otalive.objects.gdmf.Asset;
import com.coocoofroggy.otalive.objects.gdmf.GdmfResponse;
import com.dd.plist.NSDictionary;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GdmfUtils {
    public static final Pattern DEVICE_NAME_PATTERN = Pattern.compile("(.*?)\\d.*");
    public static final Pattern SPECIAL_CASE_BOARD_ID_PATTERN = Pattern.compile(".*?[.-](.*?)(?:dev|\\.)");
    private static final Gson gson = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger(GdmfUtils.class);
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
            }, TimerUtils.EXECUTOR); // In our custom thread pool for *speed*
            // Add it to a list to check if they're all completed
            completableFutures.add(future);
            // Rate-limit so GDMF isn't that mad
//            try {
//                Thread.sleep(20);
//            } catch (InterruptedException e) {
//                LOGGER.error("Interrupted sleeping for GDMF rate limit.", e);
//            }
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
    private static Runnable buildGdmfDeviceRunnable(final GlobalObject globalObject, final List<Asset> processedAssets, final String device, final String boardId, final String deviceHumanName) {
        // Define our runnable
        return () -> {
            int attempts = 0;
            int maxAttempts = 3;

            while (true) {
                try {
                    for (final String assetAudience : globalObject.getAssetAudiences()) {
                        GdmfResponse suGdmfResponse = fetchSuGdmfResponse(device, boardId, assetAudience);
                        if (suGdmfResponse == null) continue;

                        for (final Asset asset : suGdmfResponse.getAssets()) {
                            // If this is a new asset, process it
                            if (!processedAssets.contains(asset)) {
                                LOGGER.info("Queued new: " + asset);
                                newFirmwareReleased = true;
                                // Runnable for processing this asset
                                Callable<List<QueuedDevUpload>> newAssetCallable = () -> {
                                    int attempts1 = 0;
                                    int maxAttempts1 = 3;

                                    while (true) {
                                        try {
                                            LOGGER.info("Processing new: " + asset);
                                            Guild guild = Main.jda.getGuildById(globalObject.getGuildId());
                                            TextChannel channel = guild.getTextChannelById(globalObject.getChannelId());

                                            // Get device name
                                            Matcher matcher = DEVICE_NAME_PATTERN.matcher(device);
                                            // If there's no device name we messed up. Skip and continue to next asset
                                            if (!matcher.find()) {
                                                LOGGER.error("Cannot find device name for " + device);
                                                break;
                                            }

                                            GdmfResponse docGdmfResponse = fetchDocGdmfResponse(device, assetAudience, asset, matcher.group(1));
                                            if (docGdmfResponse == null) break;

                                            // Not iterating through assets because there should not be multiple documentations. Just use the first.
                                            // Get human-readable name
                                            asset.setHumanReadableName(
                                                    humanReadableFromDocUrl(docGdmfResponse.getAssets().get(0).getFullUrl()));

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
                                            // Makes a remote zip from URL
                                            URL url = new URL(asset.getFullUrl());
                                            ZipFile otaZip = new ZipFile(new HttpChannel(url), "Dev Files: " + asset.getFullUrl(), StandardCharsets.UTF_8.name(), true, true);
                                            // Parses out all the dev entries
                                            List<ZipArchiveEntry> devFiles = parseDevFiles(otaZip, boardId);
                                            // Turns that list into a String to put it the embed
                                            List<String> devFilesStrings = listDevFiles(devFiles);
                                            String collect = "None found";
                                            if (!devFilesStrings.isEmpty())
                                                collect = devFilesStrings.stream().collect(Collectors.joining("`\n`", "`", "`"));
                                            embedBuilder.setDescription("**Dev Files**\n" + collect.substring(0, Math.min(4081, collect.length())));

                                            // Close it
                                            otaZip.close();

                                            // Update the message
                                            message.editMessageEmbeds(embedBuilder.build()).queue();

                                            // Get BuildIdentity data for TSS
                                            BuildIdentity buildIdentity = TssUtils.buildIdentityFromUrl(asset.getFullUrl(), boardId);
                                            // Should never trigger. But just in case
                                            if (buildIdentity == null) {
                                                LOGGER.error("BM for " + device + " (" + asset.getSuDocumentationId() + ") is null. Skipping this asset—not adding it to Build Identities collection.");
                                                channel.sendMessage("<@353561670934855681> couldn't parse data from BM 🚨.").queue();
                                                message.editMessage("Failed to parse BuildManifest—you may see the embed below duplicated at a later point in time.").queue();
                                                break;
                                            }
                                            // Mark this asset as processed in memory
                                            processedAssets.add(asset);
                                            // Mark this asset as processed in DB and add to TSS queue
                                            buildIdentity.setAsset(asset);
                                            MongoUtils.insertBuildIdentity(buildIdentity);

                                            // Queues all the uploads for dev files to Azure
                                            return buildQueueUploadDevFiles(otaZip, devFiles, url.getPath());
                                        } catch (Exception e) {
                                            // Try again (because of while true loop) but on third try, just quit
                                            if (++attempts1 > maxAttempts1) {
                                                Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
                                                LOGGER.error("Caught exception. Quitting, max attempts was three.");
                                                throw new RuntimeException(e);
                                            }
                                            LOGGER.error("Caught exception. Trying again.", e);
                                        }
                                    }
                                    return null;
                                };
                                NewAssetUtils.QUEUED_NEW_ASSET_CALLABLES.add(newAssetCallable);
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
    private static GdmfResponse fetchDocGdmfResponse(String device, String assetAudience, Asset asset, String deviceName) throws IOException, InterruptedException {
        GdmfResponse docGdmfResponse;
        int docAttempts = 0;
        while (true) {
            // Get Documentation from GDMF
            String docResponseString = docRequest(assetAudience, asset.getSuDocumentationId(), deviceName);
            docGdmfResponse = parseJwt(docResponseString);
            // If there are no assets, retry
            if (docGdmfResponse == null || docGdmfResponse.getAssets() == null || docGdmfResponse.getAssets().isEmpty()) {
                docAttempts++;
                // If we hit max attempts, just go on to next asset
                if (docAttempts >= 3) {
                    LOGGER.error("Skipping asset for " + device + " because documentation (" + asset.getSuDocumentationId() + ") is null or empty.");
                    return null;
                }
                Thread.sleep(1000);
                continue;
            }
            // Otherwise break out of loop: we have the asset
            break;
        }
        return docGdmfResponse;
    }

    @Nullable
    private static GdmfResponse fetchSuGdmfResponse(String device, String boardId, String assetAudience) throws IOException, InterruptedException {
        GdmfResponse suGdmfResponse;
        int suAttempts = 0;
        while (true) {
            // Get SU from GDMF
            String suResponseString = suRequest(device, boardId, assetAudience);
            try {
                suGdmfResponse = parseJwt(suResponseString);
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
            if (suGdmfResponse == null || suGdmfResponse.getAssets() == null) {
                suAttempts++;
                // If we hit max attempts, just go on to next asset audience for this device
                if (suAttempts >= 3) {
                    LOGGER.error("Skipping asset audience (" + assetAudience + ") for " + device + " because JSON deserialization was null.");
                    return null;
                }
                Thread.sleep(1000);
                continue;
            }
            // Otherwise break out of loop: we have the assets
            break;
        }
        return suGdmfResponse;
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

    public static GdmfResponse parseJwt(String encodedString) throws JsonSyntaxException {
        // The part between the two dots is what we need
        String meat = encodedString.split("\\.")[1];
        String decoded = new String(Base64.decodeBase64(meat), StandardCharsets.UTF_8);
        return gson.fromJson(decoded, GdmfResponse.class);
    }

    //region Dev Files
    private static List<ZipArchiveEntry> parseDevFiles(ZipFile otaZip, String boardId) {
        List<ZipArchiveEntry> devFiles = new ArrayList<>();
        // Loop through all files and add them to devFiles
        entryLabel:
        for (Iterator<ZipArchiveEntry> it = otaZip.getEntries().asIterator(); it.hasNext(); ) {
            ZipArchiveEntry entry = it.next();
            String fileName = Paths.get(entry.getName()).getFileName().toString();
            for (String keyword : DEV_KEYWORDS) {
                // If it's a dev file
                if (fileName.toLowerCase().contains(keyword)) {
                    // Check for special case
                    for (String s : SPECIAL_CASE) {
                        if (fileName.startsWith(s)) {
                            // Extract the board ID snippet from file
                            Matcher matcher = SPECIAL_CASE_BOARD_ID_PATTERN.matcher(fileName.toLowerCase());
                            if (matcher.find()) {
                                // Remove the AP from the end of the board ID
                                String boardIdNoAp = boardId.substring(0, boardId.length() - 2).toLowerCase();
                                // Only add it to the list of dev files if it matches our board ID
                                if (boardIdNoAp.equals(matcher.group(1))) {
                                    devFiles.add(entry);
                                }
                            }
                            // It will only match one special case—no need to check others
                            continue entryLabel;
                        }
                    }
                    // if it's a plist file we usually don't care
                    if (fileName.endsWith(".plist") && !fileName.contains("device_map")) continue entryLabel;
                    // It's not a special case if we reach here
                    devFiles.add(entry);
                    // Go to next entry—we already found match
                    continue entryLabel;
                }
            }
        }
        return devFiles;
    }

    private static List<String> listDevFiles(List<ZipArchiveEntry> devFiles) {
        // Turn this list into a list of Strings
        List<String> devFilesStrings = new ArrayList<>();
        for (ZipArchiveEntry zipArchiveEntry : devFiles) {
            devFilesStrings.add(zipArchiveEntry.getName());
        }
        return devFilesStrings;
    }

    private static List<QueuedDevUpload> buildQueueUploadDevFiles(ZipFile otaZip, List<ZipArchiveEntry> devFiles, String path) {
        List<QueuedDevUpload> toReturn = new ArrayList<>();
        for (ZipArchiveEntry devFile : devFiles) {
            // If it starts with /, remove it
            String newPath = path.startsWith("/") ? path.substring(1) : path;
            // If it ends with zip, remove it
            newPath = newPath.endsWith(".zip") ? newPath.substring(0, newPath.length() - 4) : newPath;
            // Add a / if it doesn't end with one now
            newPath = !newPath.endsWith("/") ? newPath + "/" : newPath;
            // Now add the specific file to the path
            newPath += devFile.getName();
            toReturn.add(new QueuedDevUpload(
                    otaZip, devFile, newPath));
        }
        return toReturn;
    }
    //endregion

    public static String humanReadableFromDocUrl(String urlString) throws Exception {
        URL url = new URL(urlString);
        ZipFile otaZip = new ZipFile(new HttpChannel(url), "Documentation: " + urlString, StandardCharsets.UTF_8.name(), true, true);
        ZipArchiveEntry documentationEntry = otaZip.getEntry("AssetData/en.lproj/documentation.strings");
        InputStream inputStream = otaZip.getInputStream(documentationEntry);

        NSDictionary rootDict = (NSDictionary) PropertyListParser.parse(inputStream);
        otaZip.close();
        return rootDict.objectForKey("HumanReadableUpdateName").toString();
    }

}
