/*
 * Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;
import org.slf4j.bridge.SLF4JBridgeHandler;

import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Version;
import org.glowroot.server.storage.AgentDao;
import org.glowroot.server.storage.AggregateDao;
import org.glowroot.server.storage.AlertConfigDao;
import org.glowroot.server.storage.ConfigRepositoryImpl;
import org.glowroot.server.storage.GaugeValueDao;
import org.glowroot.server.storage.ServerConfigDao;
import org.glowroot.server.storage.TraceDao;
import org.glowroot.server.storage.TransactionTypeDao;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;

public class ServerModule {

    private final Cluster cluster;
    private final Session session;
    private final GrpcServer server;
    private final UiModule uiModule;

    ServerModule() throws Exception {
        // install jul-to-slf4j bridge for protobuf which logs to jul
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        Clock clock = Clock.systemClock();
        String version = Version.getVersion(Bootstrap.class);

        ServerConfiguration serverConfig = getCassandraContactPoints();
        cluster = Cluster.builder()
                .addContactPoints(serverConfig.cassandraContactPoint().toArray(new String[0]))
                .build();
        session = cluster.connect();
        session.execute("create keyspace if not exists " + serverConfig.cassandraKeyspace()
                + " with replication = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("use " + serverConfig.cassandraKeyspace());

        AgentDao agentDao = new AgentDao(session);
        TransactionTypeDao transactionTypeDao = new TransactionTypeDao(session);

        ServerConfigDao serverConfigDao = new ServerConfigDao(session);
        AlertConfigDao alertConfigDao = new AlertConfigDao(session);
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(agentDao, serverConfigDao, alertConfigDao);

        AggregateRepository aggregateRepository =
                new AggregateDao(session, agentDao, transactionTypeDao, configRepository);
        TraceRepository traceRepository = new TraceDao(session, agentDao, transactionTypeDao);
        GaugeValueRepository gaugeValueRepository =
                new GaugeValueDao(session, agentDao, configRepository);

        server = new GrpcServer(serverConfig.grpcPort(), agentDao, aggregateRepository,
                gaugeValueRepository, traceRepository);
        configRepository.setDownstreamService(server.getDownstreamService());

        RollupLevelService rollupLevelService = new RollupLevelService(configRepository, clock);

        uiModule = new CreateUiModuleBuilder()
                .fat(false)
                .clock(clock)
                .logDir(new File("."))
                .liveJvmService(new LiveJvmServiceImpl(server.getDownstreamService()))
                .configRepository(configRepository)
                .agentRepository(agentDao)
                .transactionTypeRepository(transactionTypeDao)
                .traceRepository(traceRepository)
                .aggregateRepository(aggregateRepository)
                .gaugeValueRepository(gaugeValueRepository)
                .repoAdmin(new NopRepoAdmin())
                .rollupLevelService(rollupLevelService)
                .liveTraceRepository(new LiveTraceRepositoryImpl(server.getDownstreamService()))
                .liveWeavingService(new LiveWeavingServiceImpl(server.getDownstreamService()))
                .bindAddress("0.0.0.0")
                .numWorkerThreads(50)
                .version(version)
                .build();
    }

    void close() throws InterruptedException {
        uiModule.close();
        server.close();
        session.close();
        cluster.close();
    }

    private static ServerConfiguration getCassandraContactPoints() throws IOException {
        ImmutableServerConfiguration.Builder builder = ImmutableServerConfiguration.builder();
        File propFile = new File("glowroot-server.properties");
        if (!propFile.exists()) {
            return builder.build();
        }
        Properties props = new Properties();
        InputStream in = new FileInputStream(propFile);
        try {
            props.load(in);
        } finally {
            in.close();
        }
        String cassandraContactPoints = props.getProperty("cassandra.contact.points");
        if (!Strings.isNullOrEmpty(cassandraContactPoints)) {
            builder.cassandraContactPoint(Splitter.on(',').trimResults().omitEmptyStrings()
                    .splitToList(cassandraContactPoints));
        }
        String cassandraKeyspace = props.getProperty("cassandra.keyspace");
        if (!Strings.isNullOrEmpty(cassandraKeyspace)) {
            builder.cassandraKeyspace(cassandraKeyspace);
        }
        String grpcPortText = props.getProperty("grpc.port");
        if (!Strings.isNullOrEmpty(grpcPortText)) {
            builder.grpcPort(Integer.parseInt(grpcPortText));
        }
        return builder.build();
    }

    @Value.Immutable
    static abstract class ServerConfiguration {
        @Value.Default
        @SuppressWarnings("immutables")
        List<String> cassandraContactPoint() {
            return ImmutableList.of("127.0.0.1");
        }
        @Value.Default
        String cassandraKeyspace() {
            return "glowroot";
        }
        @Value.Default
        int grpcPort() {
            return 8181;
        }
    }

    private static class NopRepoAdmin implements RepoAdmin {
        @Override
        public void defrag() throws Exception {}
        @Override
        public void resizeIfNecessary() throws Exception {}
    }
}
