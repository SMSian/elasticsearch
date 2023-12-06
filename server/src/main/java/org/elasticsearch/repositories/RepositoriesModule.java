/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.repositories;

import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.IndexVersions;
import org.elasticsearch.indices.recovery.RecoverySettings;
import org.elasticsearch.plugins.RepositoryPlugin;
import org.elasticsearch.repositories.fs.FsRepository;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotRestoreException;
import org.elasticsearch.telemetry.TelemetryProvider;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.NamedXContentRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Sets up classes for Snapshot/Restore.
 */
public final class RepositoriesModule {

    public static final String METRIC_REQUESTS_COUNT = "es.repositories.requests.count";
    public static final String METRIC_EXCEPTIONS_COUNT = "es.repositories.exceptions.count";
    public static final String METRIC_THROTTLES_COUNT = "es.repositories.throttles.count";
    public static final String METRIC_OPERATIONS_COUNT = "es.repositories.operations.count";
    public static final String METRIC_UNSUCCESSFUL_OPERATIONS_COUNT = "es.repositories.operations.unsuccessful.count";
    public static final String METRIC_EXCEPTIONS_HISTOGRAM = "es.repositories.exceptions.histogram";
    public static final String METRIC_THROTTLES_HISTOGRAM = "es.repositories.throttles.histogram";

    private final RepositoriesService repositoriesService;

    public RepositoriesModule(
        Environment env,
        List<RepositoryPlugin> repoPlugins,
        TransportService transportService,
        ClusterService clusterService,
        BigArrays bigArrays,
        NamedXContentRegistry namedXContentRegistry,
        RecoverySettings recoverySettings,
        TelemetryProvider telemetryProvider
    ) {
        telemetryProvider.getMeterRegistry().registerLongCounter(METRIC_REQUESTS_COUNT, "repository request counter", "unit");
        telemetryProvider.getMeterRegistry().registerLongCounter(METRIC_EXCEPTIONS_COUNT, "repository request exception counter", "unit");
        telemetryProvider.getMeterRegistry().registerLongCounter(METRIC_THROTTLES_COUNT, "repository operation counter", "unit");
        telemetryProvider.getMeterRegistry()
            .registerLongCounter(METRIC_OPERATIONS_COUNT, "repository unsuccessful operation counter", "unit");
        telemetryProvider.getMeterRegistry()
            .registerLongCounter(METRIC_UNSUCCESSFUL_OPERATIONS_COUNT, "repository request throttle counter", "unit");
        telemetryProvider.getMeterRegistry()
            .registerLongHistogram(METRIC_EXCEPTIONS_HISTOGRAM, "repository request exception histogram", "unit");
        telemetryProvider.getMeterRegistry()
            .registerLongHistogram(METRIC_THROTTLES_HISTOGRAM, "repository request throttle histogram", "unit");
        Map<String, Repository.Factory> factories = new HashMap<>();
        factories.put(
            FsRepository.TYPE,
            metadata -> new FsRepository(metadata, env, namedXContentRegistry, clusterService, bigArrays, recoverySettings)
        );

        for (RepositoryPlugin repoPlugin : repoPlugins) {
            Map<String, Repository.Factory> newRepoTypes = repoPlugin.getRepositories(
                env,
                namedXContentRegistry,
                clusterService,
                bigArrays,
                recoverySettings
            );
            for (Map.Entry<String, Repository.Factory> entry : newRepoTypes.entrySet()) {
                if (factories.put(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalArgumentException("Repository type [" + entry.getKey() + "] is already registered");
                }
            }
        }

        Map<String, Repository.Factory> internalFactories = new HashMap<>();
        for (RepositoryPlugin repoPlugin : repoPlugins) {
            Map<String, Repository.Factory> newRepoTypes = repoPlugin.getInternalRepositories(
                env,
                namedXContentRegistry,
                clusterService,
                recoverySettings
            );
            for (Map.Entry<String, Repository.Factory> entry : newRepoTypes.entrySet()) {
                if (internalFactories.put(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalArgumentException("Internal repository type [" + entry.getKey() + "] is already registered");
                }
                if (factories.put(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalArgumentException(
                        "Internal repository type [" + entry.getKey() + "] is already registered as a " + "non-internal repository"
                    );
                }
            }
        }

        List<BiConsumer<Snapshot, IndexVersion>> preRestoreChecks = new ArrayList<>();
        for (RepositoryPlugin repoPlugin : repoPlugins) {
            BiConsumer<Snapshot, IndexVersion> preRestoreCheck = repoPlugin.addPreRestoreVersionCheck();
            if (preRestoreCheck != null) {
                preRestoreChecks.add(preRestoreCheck);
            }
        }
        if (preRestoreChecks.isEmpty()) {
            preRestoreChecks.add((snapshot, version) -> {
                if (version.isLegacyIndexVersion()) {
                    throw new SnapshotRestoreException(
                        snapshot,
                        "the snapshot was created with Elasticsearch version ["
                            + version
                            + "] which is below the current versions minimum index compatibility version ["
                            + IndexVersions.MINIMUM_COMPATIBLE
                            + "]"
                    );
                }
            });
        }

        Settings settings = env.settings();
        Map<String, Repository.Factory> repositoryTypes = Collections.unmodifiableMap(factories);
        Map<String, Repository.Factory> internalRepositoryTypes = Collections.unmodifiableMap(internalFactories);
        repositoriesService = new RepositoriesService(
            settings,
            clusterService,
            transportService,
            repositoryTypes,
            internalRepositoryTypes,
            transportService.getThreadPool(),
            preRestoreChecks
        );
    }

    public RepositoriesService getRepositoryService() {
        return repositoriesService;
    }
}
