/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.store.IndexInput;
import org.opensearch.Version;
import org.opensearch.action.LatchedActionListener;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.common.CheckedRunnable;
import org.opensearch.common.blobstore.AsyncMultiStreamBlobContainer;
import org.opensearch.common.blobstore.BlobContainer;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.blobstore.stream.write.WritePriority;
import org.opensearch.common.blobstore.transfer.RemoteTransferContainer;
import org.opensearch.common.blobstore.transfer.stream.OffsetRangeIndexInputStream;
import org.opensearch.common.io.stream.BytesStreamOutput;

import org.opensearch.common.blobstore.BlobContainer;
import org.opensearch.common.lucene.store.ByteArrayIndexInput;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.io.IOUtils;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.index.Index;
import org.opensearch.gateway.remote.ClusterMetadataManifest;
import org.opensearch.gateway.remote.RemoteClusterStateService;
import org.opensearch.gateway.remote.RemoteClusterStateUtils;
import org.opensearch.gateway.remote.routingtable.IndexRoutingTableInputStream;
import org.opensearch.gateway.remote.routingtable.IndexRoutingTableInputStreamReader;
import org.opensearch.index.remote.RemoteStoreUtils;
import org.opensearch.index.store.exception.ChecksumCombinationException;
import org.opensearch.node.Node;
import org.opensearch.node.remotestore.RemoteStoreNodeAttribute;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.repositories.Repository;
import org.opensearch.repositories.blobstore.BlobStoreRepository;

import java.io.*;
import java.io.Closeable;
import java.io.IOException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.opensearch.common.blobstore.transfer.RemoteTransferContainer.checksumOfChecksum;
import static org.opensearch.gateway.remote.RemoteClusterStateUtils.FORMAT_PARAMS;
import static org.opensearch.gateway.remote.RemoteClusterStateUtils.getCusterMetadataBasePath;
import static org.opensearch.node.remotestore.RemoteStoreNodeAttribute.isRemoteRoutingTableEnabled;

/**
 * A Service which provides APIs to upload and download routing table from remote store.
 *
 * @opensearch.internal
 */
public class RemoteRoutingTableService implements Closeable {

    /**
     * Cluster setting to specify if routing table should be published to remote store
     */
    public static final Setting<Boolean> REMOTE_ROUTING_TABLE_ENABLED_SETTING = Setting.boolSetting(
        "cluster.remote_store.routing.enabled",
        false,
        Setting.Property.NodeScope,
        Setting.Property.Final
    );
    public static final String INDEX_ROUTING_PATH_TOKEN = "index-routing";
    public static final String DELIMITER = "__";

    private static final Logger logger = LogManager.getLogger(RemoteRoutingTableService.class);
    private final Settings settings;
    private final Supplier<RepositoriesService> repositoriesService;
    private final ClusterSettings clusterSettings;
    private BlobStoreRepository blobStoreRepository;

    public RemoteRoutingTableService(Supplier<RepositoriesService> repositoriesService,
                                     Settings settings,
                                     ClusterSettings clusterSettings) {
        assert isRemoteRoutingTableEnabled(settings) : "Remote routing table is not enabled";
        this.repositoriesService = repositoriesService;
        this.settings = settings;
        this.clusterSettings = clusterSettings;
    }

    public List<ClusterMetadataManifest.UploadedIndexMetadata> writeFullRoutingTable(ClusterState clusterState, String previousClusterUUID) {


        //batch index count and parallelize
        RoutingTable currentRoutingTable = clusterState.getRoutingTable();
        List<ClusterMetadataManifest.UploadedIndexMetadata> uploadedIndices = new ArrayList<>();
        BlobPath custerMetadataBasePath = getCusterMetadataBasePath(blobStoreRepository, clusterState.getClusterName().value(),
            clusterState.metadata().clusterUUID());
        for (IndexRoutingTable indexRouting : currentRoutingTable.getIndicesRouting().values()) {
            uploadedIndices.add(uploadIndex(indexRouting, clusterState.getRoutingTable().version(), custerMetadataBasePath));
        }
        logger.info("uploadedIndices {}", uploadedIndices);

        return uploadedIndices;
    }

    public List<IndexRoutingTable> getChangedIndicesRouting( ClusterState previousClusterState,
                                   ClusterState clusterState) {
        Map<String, IndexRoutingTable> previousIndexRoutingTable = previousClusterState.getRoutingTable().getIndicesRouting();

        List<IndexRoutingTable> changedIndicesRouting = new ArrayList<>();

        for (IndexRoutingTable indexRouting : clusterState.getRoutingTable().getIndicesRouting().values()) {
            if (!(previousIndexRoutingTable.containsKey(indexRouting.getIndex().getName()) && indexRouting.equals(previousIndexRoutingTable.get(indexRouting.getIndex().getName())))) {
                changedIndicesRouting.add(indexRouting);
                logger.info("changedIndicesRouting {}", indexRouting.prettyPrint());

            }
        }

        return changedIndicesRouting;

    }

    public CheckedRunnable<IOException> getIndexRoutingAsyncAction(
        ClusterState clusterState,
        IndexRoutingTable indexRouting,
        LatchedActionListener<ClusterMetadataManifest.UploadedMetadata> latchedActionListener
    ) throws IOException {

        //TODO: Integrate with optimized S3 prefix for index routing file path.
        BlobPath custerMetadataBasePath = getCusterMetadataBasePath(blobStoreRepository, clusterState.getClusterName().value(),
            clusterState.metadata().clusterUUID());
        logger.info("custerMetadataBasePath {}", custerMetadataBasePath);

        final BlobContainer blobContainer = blobStoreRepository.blobStore().blobContainer(custerMetadataBasePath.add(INDEX_ROUTING_PATH_TOKEN).add(indexRouting.getIndex().getUUID()));
        logger.info("full path  {}", blobContainer.path());

        final String fileName = getIndexRoutingFileName();
        logger.info("fileName {}", fileName);

        ActionListener<Void> completionListener = ActionListener.wrap(
            resp -> latchedActionListener.onResponse(
                new ClusterMetadataManifest.UploadedIndexMetadata(

                    indexRouting.getIndex().getName(),
                    indexRouting.getIndex().getUUID(),
                    blobContainer.path().buildAsString() + fileName,
                    "indexRouting--"
                )
            ),
            ex -> latchedActionListener.onFailure(new RemoteClusterStateUtils.RemoteStateTransferException(indexRouting.getIndex().toString(), ex))
        );

        if (blobContainer instanceof AsyncMultiStreamBlobContainer == false) {
            logger.info("TRYING FILE UPLOAD");

            return () -> {
                logger.info("Going to upload {}", indexRouting.prettyPrint());
                uploadIndex(indexRouting, clusterState.getRoutingTable().version(), custerMetadataBasePath);
                logger.info("upload done {}", indexRouting.prettyPrint());

                completionListener.onResponse(null);
                logger.info("response done {}", indexRouting.prettyPrint());

            };
        }

//        try (
//            InputStream indexRoutingStream = new IndexRoutingTableInputStream(indexRouting);
//            IndexInput input = new ByteArrayIndexInput("indexrouting", indexRoutingStream.readAllBytes())) {
////            long expectedChecksum;
////            try {
////                expectedChecksum = checksumOfChecksum(input.clone(), 8);
////            } catch (Exception e) {
////                throw e;
////            }
//            try (
//
//                RemoteTransferContainer remoteTransferContainer = new RemoteTransferContainer(
//                    fileName,
//                    fileName,
//                    input.length(),
//                    true,
//                    WritePriority.URGENT,
//                    (size, position) -> new OffsetRangeIndexInputStream(input, size, position),
//                    null,
//                    false
//                )
//            ) {
//                return () -> ((AsyncMultiStreamBlobContainer) blobContainer).asyncBlobUpload(remoteTransferContainer.createWriteContext(), completionListener);
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
        logger.info("TRYING S3 UPLOAD");
        InputStream indexRoutingStream = new IndexRoutingTableInputStream(indexRouting);
        logger.info("Going to upload {}", indexRouting.prettyPrint());

        //return  () -> ((AsyncMultiStreamBlobContainer) blobContainer).asyncStreamUpload(fileName, indexRoutingStream, completionListener);
        return  () -> ((AsyncMultiStreamBlobContainer) blobContainer).asyncWriteBlob(fileName, indexRoutingStream, false, null, WritePriority.URGENT, completionListener );
    }

    public List<ClusterMetadataManifest.UploadedIndexMetadata> writeIncrementalRoutingTable(
        ClusterState previousClusterState,
        ClusterState clusterState,
        ClusterMetadataManifest previousManifest) {

        final Map<String, ClusterMetadataManifest.UploadedIndexMetadata> allUploadedIndicesRouting = previousManifest.getIndicesRouting()
            .stream()
            .collect(Collectors.toMap(ClusterMetadataManifest.UploadedIndexMetadata::getIndexName, Function.identity()));
        logger.info("allUploadedIndicesRouting ROUTING {}", allUploadedIndicesRouting);

        Map<String, IndexRoutingTable> previousIndexRoutingTable = previousClusterState.getRoutingTable().getIndicesRouting();
        List<ClusterMetadataManifest.UploadedIndexMetadata> uploadedIndices = new ArrayList<>();
        BlobPath custerMetadataBasePath = getCusterMetadataBasePath(blobStoreRepository, clusterState.getClusterName().value(),
            clusterState.metadata().clusterUUID());
        for (IndexRoutingTable indexRouting : clusterState.getRoutingTable().getIndicesRouting().values()) {
            if (previousIndexRoutingTable.containsKey(indexRouting.getIndex().getName()) && indexRouting.equals(previousIndexRoutingTable.get(indexRouting.getIndex().getName()))) {
                logger.info("index exists {}", indexRouting.getIndex().getName());
                //existing index with no shard change.
                uploadedIndices.add(allUploadedIndicesRouting.get(indexRouting.getIndex().getName()));
            } else {
                // new index or shards changed, in both cases we upload new index file.
                uploadedIndices.add(uploadIndex(indexRouting, clusterState.getRoutingTable().version(), custerMetadataBasePath));
            }
        }
        return uploadedIndices;
    }

    public List<ClusterMetadataManifest.UploadedIndexMetadata> getAllUploadedIndicesRouting(ClusterMetadataManifest previousManifest, List<ClusterMetadataManifest.UploadedIndexMetadata> indicesRoutingToUpload) {
        final Map<String, ClusterMetadataManifest.UploadedIndexMetadata> allUploadedIndicesRouting = previousManifest.getIndicesRouting()
            .stream()
            .collect(Collectors.toMap(ClusterMetadataManifest.UploadedIndexMetadata::getIndexName, Function.identity()));
        indicesRoutingToUpload.forEach(
            uploadedIndexMetadata -> allUploadedIndicesRouting.put(uploadedIndexMetadata.getIndexName(), uploadedIndexMetadata)
        );

        logger.info("allUploadedIndicesRouting ROUTING {}", allUploadedIndicesRouting);

        return new ArrayList<>(allUploadedIndicesRouting.values());
    }

    private ClusterMetadataManifest.UploadedIndexMetadata uploadIndex(IndexRoutingTable indexRouting, long routingTableVersion, BlobPath custerMetadataBasePath) {
        try {
            InputStream indexRoutingStream = new IndexRoutingTableInputStream(indexRouting);
            BlobContainer container = blobStoreRepository.blobStore().blobContainer(custerMetadataBasePath.add(INDEX_ROUTING_PATH_TOKEN).add(indexRouting.getIndex().getUUID()));

            container.writeBlob(getIndexRoutingFileName(), indexRoutingStream, 4096, true);
            return new ClusterMetadataManifest.UploadedIndexMetadata(indexRouting.getIndex().getName(), indexRouting.getIndex().getUUID(), container.path().buildAsString() + getIndexRoutingFileName());

        } catch (IOException e) {
            logger.error("Failed to write {}", e);
        }
        logger.info("SUccessful write");
        return null;
    }

    private String getIndexRoutingFileName() {
        return String.join(
            DELIMITER,
            //RemoteStoreUtils.invertLong(indexMetadata.getVersion()),
            RemoteStoreUtils.invertLong(System.currentTimeMillis()),
            String.valueOf("CODEC1") // Keep the codec version at last place only, during read we reads last
            // place to determine codec version.
        );

    }
    public RoutingTable getLatestRoutingTable(String clusterName, String clusterUUID) {
        return null;
    }

    public RoutingTable getIncrementalRoutingTable(ClusterState previousClusterState, ClusterMetadataManifest previousManifest, String clusterName, String clusterUUID) {
        return null;
    }

    public RoutingTable getLatestRoutingTable(long routingTableVersion, List<ClusterMetadataManifest.UploadedIndexMetadata> indicesRoutingMetaData) throws IOException {
        Map<String, IndexRoutingTable> indicesRouting = new HashMap<>();

        for(ClusterMetadataManifest.UploadedIndexMetadata indexRoutingMetaData: indicesRoutingMetaData) {
            logger.debug("Starting the read for first indexRoutingMetaData: {}", indexRoutingMetaData);
            String filePath = indexRoutingMetaData.getUploadedFilePath();
            BlobContainer container = blobStoreRepository.blobStore().blobContainer(blobStoreRepository.basePath().add(filePath));
            InputStream inputStream = container.readBlob(indexRoutingMetaData.getIndexName());
            IndexRoutingTableInputStreamReader indexRoutingTableInputStreamReader = new IndexRoutingTableInputStreamReader(inputStream);
            Index index = new Index(indexRoutingMetaData.getIndexName(), indexRoutingMetaData.getIndexUUID());
            IndexRoutingTable indexRouting = indexRoutingTableInputStreamReader.readIndexRoutingTable(index);
            indicesRouting.put(indexRoutingMetaData.getIndexName(), indexRouting);
            logger.debug("IndexRouting {}", indexRouting);
        }
        return new RoutingTable(routingTableVersion, indicesRouting);
    }

    private void deleteStaleRoutingTable(String clusterName, String clusterUUID, int manifestsToRetain) {
    }

    @Override
    public void close() throws IOException {
        if (blobStoreRepository != null) {
            IOUtils.close(blobStoreRepository);
        }
    }

    public void start() {
        assert isRemoteRoutingTableEnabled(settings) == true : "Remote routing table is not enabled";
        final String remoteStoreRepo = settings.get(
            Node.NODE_ATTRIBUTES.getKey() + RemoteStoreNodeAttribute.REMOTE_STORE_ROUTING_TABLE_REPOSITORY_NAME_ATTRIBUTE_KEY
        );
        assert remoteStoreRepo != null : "Remote routing table repository is not configured";
        final Repository repository = repositoriesService.get().repository(remoteStoreRepo);
        assert repository instanceof BlobStoreRepository : "Repository should be instance of BlobStoreRepository";
        blobStoreRepository = (BlobStoreRepository) repository;
    }


}
