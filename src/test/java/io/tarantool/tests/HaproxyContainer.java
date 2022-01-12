package io.tarantool.tests;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class HaproxyContainer extends GenericContainer<HaproxyContainer> {

    public HaproxyContainer(DockerImageName parse) {
        super(parse);
    }

    public void addFixedExposedPorts(int hostPort, int containerPort) {
        super.addFixedExposedPort(hostPort, containerPort);
    }
}