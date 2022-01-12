package io.tarantool.tests;

import io.tarantool.driver.api.TarantoolClient;
import io.tarantool.driver.api.TarantoolClientFactory;
import io.tarantool.driver.api.TarantoolResult;
import io.tarantool.driver.api.TarantoolServerAddress;
import io.tarantool.driver.api.tuple.TarantoolTuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.TarantoolCartridgeContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.WaitingConsumer;
import org.testcontainers.shaded.com.google.common.io.Resources;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;


public class ConnectionsTest {

    private static String USER_NAME;
    private static String PASSWORD;

    private static final Logger logger = LoggerFactory.getLogger(ConnectionsTest.class);

    private static final TarantoolCartridgeContainer container =
            new TarantoolCartridgeContainer(
                    "connections-test/instances.yml",
                    "connections-test/topology.lua")
                    .withDirectoryBinding("connections-test")
                    .withLogConsumer(new Slf4jLogConsumer(logger));

    private static final HaproxyContainer haproxyContainer =
            new HaproxyContainer(DockerImageName.parse("haproxy:2.3-alpine"))
                    .withCopyFileToContainer(MountableFile.forClasspathResource("haproxy"), "/usr/local/etc/haproxy")
                    .withExposedPorts(1936, 3309);

    @BeforeAll
    public static void setUp() throws TimeoutException, IOException, URISyntaxException {
        startCluster();

        WaitingConsumer waitingConsumer = new WaitingConsumer();
        container.followOutput(waitingConsumer);
        waitingConsumer.waitUntil(f -> f.getUtf8String().contains("The cluster is balanced ok"));

        USER_NAME = container.getUsername();
        PASSWORD = container.getPassword();

        startHaproxy();
    }

    @Test
    void test_should_connectionManager_maintainNumberOfConnections_ifRoutersIsNotStable() throws Exception {
        final TarantoolClient<TarantoolTuple, TarantoolResult<TarantoolTuple>> client = makeClient();

        runLoad(client);
        checkConnectionsPerInstance(client, 100 / 3);

        stopCartridgeInstance("router-1");
        Thread.sleep(1000);
        checkConnectionsPerInstance(client, 100 / 2);

        stopCartridgeInstance("router-2");
        Thread.sleep(1000);
        checkConnectionsPerInstance(client, 100);

        runLoad(client);
        cartridgeStart();
        stopCartridgeInstance("router-3");
        Thread.sleep(1000);
        checkConnectionsPerInstance(client, 100 / 2);
    }

    private static void startCluster() {
        if (!container.isRunning()) {
            container.start();
        }
    }

    private static void startHaproxy() throws IOException, URISyntaxException {
        FileWriter fw = new FileWriter(Paths.get(Resources
                .getResource("haproxy/haproxy.cfg").toURI()).toString(), true);
        BufferedWriter bw = new BufferedWriter(fw);
        bw.write(String.format("\n\tserver router-01 host.docker.internal:%s",
                container.getMappedPort(3301)));
        bw.write(String.format("\n\tserver router-02 host.docker.internal:%s",
                container.getMappedPort(3302)));
        bw.write(String.format("\n\tserver router-03 host.docker.internal:%s",
                container.getMappedPort(3303)));
        bw.newLine();
        bw.close();

        haproxyContainer.start();
        haproxyContainer.addFixedExposedPorts(1936, 1936);
        haproxyContainer.addFixedExposedPorts(3309, 3309);
    }

    private TarantoolClient<TarantoolTuple, TarantoolResult<TarantoolTuple>> makeClient() {
        return TarantoolClientFactory.createClient()
                .withCredentials(USER_NAME, PASSWORD)
                .withAddresses(new TarantoolServerAddress(haproxyContainer.getHost(),
                        haproxyContainer.getMappedPort(3309)))
                .withConnections(100)
                .withConnectTimeout(1000)
                .withReadTimeout(1000)
                .build();
    }

    private void checkConnectionsPerInstance(TarantoolClient<TarantoolTuple, TarantoolResult<TarantoolTuple>> client,
                                             int expectedConnectionsPerInstance) {
        getInstancesNetStat(client).values()
                .forEach(count -> assertTrue(count <= expectedConnectionsPerInstance + 10));
    }

    private void stopCartridgeInstance(String routerName) throws IOException, InterruptedException {
        container.execInContainer("cartridge", "stop", "--run-dir=/tmp/run", routerName);
    }

    private void cartridgeStart() throws IOException, InterruptedException {
        container.execInContainer("cartridge", "start", "-d", "--run-dir=/tmp/run", "--data-dir=/tmp/data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> getInstancesNetStat(
            TarantoolClient<TarantoolTuple, TarantoolResult<TarantoolTuple>> client
    ) {
        final Map<String, Integer> connections = new HashMap<>();

        for (int i = 0; i < 3; i++) {

            final List<?> response = client.eval("return box.info().uuid, box.stat.net()").join();
            final String instanceUuid = (String) response.get(0);
            final Integer currentConnections =
                    ((Map<String, Map<String, Integer>>) response.get(1))
                            .get("CONNECTIONS")
                            .get("current");

            connections.put(instanceUuid, currentConnections);
        }

        return connections;
    }

    private void runLoad(TarantoolClient<TarantoolTuple, TarantoolResult<TarantoolTuple>> client) {
        final List<Thread> objects = new ArrayList<>();
        for (int j = 0; j < 1000; j++) {
            final Runnable runnable = () -> client.eval("return 'test'").join();
            final Thread thread = new Thread(runnable, "TarantoolTest");
            objects.add(thread);
        }
        objects.parallelStream().forEach(Thread::start);
    }
}
