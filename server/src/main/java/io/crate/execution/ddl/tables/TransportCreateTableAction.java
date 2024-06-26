/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.execution.ddl.tables;

import static io.crate.execution.ddl.tables.MappingUtil.createMapping;
import static org.elasticsearch.cluster.metadata.MetadataCreateIndexService.setIndexVersionCreatedSetting;
import static org.elasticsearch.cluster.metadata.MetadataCreateIndexService.validateSoftDeletesSetting;

import java.io.IOException;
import java.util.Collections;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.TransportCreateIndexAction;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.admin.indices.template.put.TransportPutIndexTemplateAction;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import io.crate.exceptions.RelationAlreadyExists;
import io.crate.execution.ddl.tables.MappingUtil.AllocPosition;
import io.crate.metadata.PartitionName;
import io.crate.metadata.RelationName;
import io.crate.metadata.view.ViewsMetadata;

/**
 * Action to perform creation of tables on the master but avoid race conditions with creating views.
 *
 * Regular tables are created through the creation of ES indices, see {@link TransportCreateIndexAction}.
 * Partitioned tables are created through ES templates, see {@link TransportPutIndexTemplateAction}.
 *
 * To atomically run the actions on the master, this action wraps around the ES actions and runs them
 * inside this action on the master with checking for views beforehand.
 *
 * See also: {@link io.crate.execution.ddl.views.TransportCreateViewAction}
 */
public class TransportCreateTableAction extends TransportMasterNodeAction<CreateTableRequest, CreateTableResponse> {

    public static final String NAME = "internal:crate:sql/tables/admin/create";

    private final TransportCreateIndexAction transportCreateIndexAction;
    private final TransportPutIndexTemplateAction transportPutIndexTemplateAction;


    @Inject
    public TransportCreateTableAction(TransportService transportService,
                                      ClusterService clusterService,
                                      ThreadPool threadPool,
                                      TransportCreateIndexAction transportCreateIndexAction,
                                      TransportPutIndexTemplateAction transportPutIndexTemplateAction) {
        super(
            NAME,
            transportService,
            clusterService, threadPool,
            CreateTableRequest::new
        );
        this.transportCreateIndexAction = transportCreateIndexAction;
        this.transportPutIndexTemplateAction = transportPutIndexTemplateAction;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected CreateTableResponse read(StreamInput in) throws IOException {
        return new CreateTableResponse(in);
    }

    @Override
    protected ClusterBlockException checkBlock(CreateTableRequest request, ClusterState state) {
        var relationName = request.getTableName();
        assert relationName != null : "relationName must not be null";

        var isPartitioned = request.getPutIndexTemplateRequest() != null || request.partitionedBy().isEmpty() == false;
        if (isPartitioned) {
            return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
        } else {
            return state.blocks().indexBlockedException(
                ClusterBlockLevel.METADATA_WRITE,
                relationName.indexNameOrAlias()
            );
        }
    }

    @Override
    protected void masterOperation(final CreateTableRequest createTableRequest,
                                   final ClusterState state,
                                   final ActionListener<CreateTableResponse> listener) {
        final RelationName relationName = createTableRequest.getTableName();
        if (viewsExists(relationName, state)) {
            listener.onFailure(new RelationAlreadyExists(relationName));
            return;
        }

        CreateIndexRequest createIndexRequest = null;
        PutIndexTemplateRequest putIndexTemplateRequest = null;
        if (state.nodes().getMinNodeVersion().onOrAfter(Version.V_5_4_0)) {
            if (createTableRequest.partitionedBy().isEmpty()) {
                createIndexRequest = toCreateIndexRequest(createTableRequest);
            } else {
                putIndexTemplateRequest = toPutIndexTemplateRequest(createTableRequest);
            }
        } else {
            if (createTableRequest.getCreateIndexRequest() != null) {
                createIndexRequest = createTableRequest.getCreateIndexRequest();
            } else if (createTableRequest.getPutIndexTemplateRequest() != null) {
                putIndexTemplateRequest = createTableRequest.getPutIndexTemplateRequest();
            }
        }

        assert createIndexRequest != null || putIndexTemplateRequest != null : "Unknown request type";

        if (createIndexRequest != null) {
            validateSettings(createIndexRequest.settings(), state);

            transportCreateIndexAction.masterOperation(
                createIndexRequest,
                state,
                listener.map(resp -> new CreateTableResponse(resp.isShardsAcknowledged()))
            );
        } else {
            validateSettings(putIndexTemplateRequest.settings(), state);

            transportPutIndexTemplateAction.masterOperation(
                putIndexTemplateRequest,
                state,
                listener.map(resp -> new CreateTableResponse(resp.isAcknowledged()))
            );
        }
    }


    private static PutIndexTemplateRequest toPutIndexTemplateRequest(CreateTableRequest request) {
        var relationName = request.getTableName();
        var mapping = createMapping(
            AllocPosition.forNewTable(),
            request.references(),
            request.pKeyIndices(),
            request.checkConstraints(),
            request.partitionedBy(),
            request.tableColumnPolicy(),
            request.routingColumn()
        );
        return new PutIndexTemplateRequest(PartitionName.templateName(relationName.schema(), relationName.name()))
            .mapping(mapping)
            .create(true)
            .settings(request.settings())
            .patterns(Collections.singletonList(PartitionName.templatePrefix(relationName.schema(), relationName.name())))
            .alias(new Alias(relationName.indexNameOrAlias()));
    }

    private static CreateIndexRequest toCreateIndexRequest(CreateTableRequest request) {
        var mapping = createMapping(
            AllocPosition.forNewTable(),
            request.references(),
            request.pKeyIndices(),
            request.checkConstraints(),
            request.partitionedBy(),
            request.tableColumnPolicy(),
            request.routingColumn()
        );
        return new CreateIndexRequest(
            request.getTableName().indexNameOrAlias(),
            request.settings()
        ).mapping(mapping);
    }

    private static boolean viewsExists(RelationName relationName, ClusterState state) {
        ViewsMetadata views = state.metadata().custom(ViewsMetadata.TYPE);
        return views != null && views.contains(relationName);
    }

    private static void validateSettings(Settings settings, ClusterState state) {
        var indexSettingsBuilder = Settings.builder();
        indexSettingsBuilder.put(settings);
        setIndexVersionCreatedSetting(indexSettingsBuilder, state);
        validateSoftDeletesSetting(indexSettingsBuilder.build());
    }
}
