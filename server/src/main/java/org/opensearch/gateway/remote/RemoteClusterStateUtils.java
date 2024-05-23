/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.gateway.remote;

import java.util.Locale;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.common.blobstore.BlobContainer;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.repositories.blobstore.BlobStoreRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RemoteClusterStateUtils {
    public static final String METADATA_NAME_FORMAT = "%s.dat";
    public static final String METADATA_NAME_PLAIN_FORMAT = "%s";
    public static final String METADATA_FILE_PREFIX = "metadata";
    public static final String CLUSTER_STATE_PATH_TOKEN = "cluster-state";
    public static final String DELIMITER = "__";
    public static final String PATH_DELIMITER = "/";

    // ToXContent Params with gateway mode.
    // We are using gateway context mode to persist all custom metadata.
    public static final ToXContent.Params FORMAT_PARAMS = new ToXContent.MapParams(Map.of(Metadata.CONTEXT_MODE_PARAM, Metadata.CONTEXT_MODE_GATEWAY));

    public static BlobPath getCusterMetadataBasePath(BlobStoreRepository blobStoreRepository, String clusterName, String clusterUUID) {
        return blobStoreRepository.basePath().add(encodeString(clusterName)).add(CLUSTER_STATE_PATH_TOKEN).add(clusterUUID);
    }

    public static String encodeString(String content) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String getFormattedFileName(String fileName, int codecVersion) {
        if (codecVersion < ClusterMetadataManifest.CODEC_V3) {
            return String.format(Locale.ROOT, METADATA_NAME_FORMAT, fileName);
        }
        return fileName;
    }

    static BlobContainer clusterUUIDContainer(BlobStoreRepository blobStoreRepository, String clusterName) {
        return blobStoreRepository.blobStore()
            .blobContainer(
                blobStoreRepository.basePath()
                    .add(Base64.getUrlEncoder().withoutPadding().encodeToString(clusterName.getBytes(StandardCharsets.UTF_8)))
                    .add(CLUSTER_STATE_PATH_TOKEN)
            );
    }

    /**
     * Exception for Remote state transfer.
     */
    public static class RemoteStateTransferException extends RuntimeException {

        public RemoteStateTransferException(String errorDesc) {
            super(errorDesc);
        }

        public RemoteStateTransferException(String errorDesc, Throwable cause) {
            super(errorDesc, cause);
        }
    }

    public static class UploadedMetadataResults {
        List<ClusterMetadataManifest.UploadedIndexMetadata> uploadedIndexMetadata;
        Map<String, ClusterMetadataManifest.UploadedMetadataAttribute> uploadedCustomMetadataMap;
        ClusterMetadataManifest.UploadedMetadataAttribute uploadedCoordinationMetadata;
        ClusterMetadataManifest.UploadedMetadataAttribute uploadedSettingsMetadata;
        ClusterMetadataManifest.UploadedMetadataAttribute uploadedTemplatesMetadata;
        ClusterMetadataManifest.UploadedMetadataAttribute uploadedDiscoveryNodes;
        ClusterMetadataManifest.UploadedMetadataAttribute uploadedClusterBlocks;
        List<ClusterMetadataManifest.UploadedIndexMetadata> uploadedIndicesRoutingMetadata;

        public UploadedMetadataResults(
            List<ClusterMetadataManifest.UploadedIndexMetadata> uploadedIndexMetadata,
            Map<String, ClusterMetadataManifest.UploadedMetadataAttribute> uploadedCustomMetadataMap,
            ClusterMetadataManifest.UploadedMetadataAttribute uploadedCoordinationMetadata,
            ClusterMetadataManifest.UploadedMetadataAttribute uploadedSettingsMetadata,
            ClusterMetadataManifest.UploadedMetadataAttribute uploadedTemplatesMetadata,
            ClusterMetadataManifest.UploadedMetadataAttribute uploadedDiscoveryNodes,
            ClusterMetadataManifest.UploadedMetadataAttribute uploadedClusterBlocks,
            List<ClusterMetadataManifest.UploadedIndexMetadata> uploadedIndicesRoutingMetadata
        ) {
            this.uploadedIndexMetadata = uploadedIndexMetadata;
            this.uploadedCustomMetadataMap = uploadedCustomMetadataMap;
            this.uploadedCoordinationMetadata = uploadedCoordinationMetadata;
            this.uploadedSettingsMetadata = uploadedSettingsMetadata;
            this.uploadedTemplatesMetadata = uploadedTemplatesMetadata;
            this.uploadedDiscoveryNodes = uploadedDiscoveryNodes;
            this.uploadedClusterBlocks = uploadedClusterBlocks;
            this.uploadedIndicesRoutingMetadata = uploadedIndicesRoutingMetadata;
        }

        public UploadedMetadataResults() {
            this.uploadedIndexMetadata = new ArrayList<>();
            this.uploadedCustomMetadataMap = new HashMap<>();
            this.uploadedCoordinationMetadata = null;
            this.uploadedSettingsMetadata = null;
            this.uploadedTemplatesMetadata = null;
            this.uploadedDiscoveryNodes = null;
            this.uploadedClusterBlocks = null;
            this.uploadedIndicesRoutingMetadata = new ArrayList<>();
        }
    }

    public static class RemoteReadResult {
        ToXContent obj;
        String component;
        String componentName;

        public RemoteReadResult(ToXContent obj, String component, String componentName) {
            this.obj = obj;
            this.component = component;
            this.componentName = componentName;
        }

        public ToXContent getObj() {
            return obj;
        }

        public String getComponent() {
            return component;
        }

        public String getComponentName() {
            return componentName;
        }
    }
}
