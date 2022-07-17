package com.coocoofroggy.otalive.utils;

import com.azure.core.http.rest.Response;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.ProgressReceiver;
import com.azure.storage.blob.models.AccessTier;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlockBlobItem;
import com.azure.storage.blob.models.ParallelTransferOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.DecimalFormat;

public class AzureUtils {
    private static BlobContainerClient containerClient;
    private static final Logger LOGGER = LoggerFactory.getLogger(AzureUtils.class);

    public static void connectToAzure() {
        // Create a BlobServiceClient object which will be used to create a container client
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder().connectionString(System.getenv("AZURE_CONNECTION_STRING")).buildClient();
        containerClient = blobServiceClient.createBlobContainerIfNotExistsWithResponse("ota-zips", null, null).getValue();
    }

    public static Response<BlockBlobItem> uploadInputStream(InputStream inputStream, long length, String path) {
        if (!containerClient.getBlobClient(path).exists()) {
            return containerClient.getBlobClient(path).uploadWithResponse(
                    new BlobParallelUploadOptions(inputStream)
                            .setTier(AccessTier.ARCHIVE)
                            .setParallelTransferOptions(
                                    new ParallelTransferOptions().setProgressReceiver(new UploadReporter(path, length)))
                            .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*") // Don't overwrite
                            ),
                    null,
                    null);
        }
        // Otherwise it exists already
        return null;
    }

    static class UploadReporter implements ProgressReceiver {
        private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.00");

        String name;
        long length;

        public UploadReporter(String name, long length) {
            this.name = name;
            this.length = length;
        }

        @Override
        public void reportProgress(long bytesTransferred) {
            String percentage = DECIMAL_FORMAT.format((bytesTransferred / length) * 100) + "%";
            LOGGER.info("Upload progress for " + name + ": " + percentage);
        }
    }
}
