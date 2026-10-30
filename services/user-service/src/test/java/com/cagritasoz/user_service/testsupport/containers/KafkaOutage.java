package com.cagritasoz.user_service.testsupport.containers;

import com.github.dockerjava.api.command.PauseContainerCmd;
import com.github.dockerjava.api.command.UnpauseContainerCmd;
import org.testcontainers.kafka.KafkaContainer;

// "Broker down" for a test, without losing the broker's port: a paused container keeps its mapped port but answers
// nothing, like a machine that dropped off the network. Stopping it would give the restarted broker a new port, and
// every client in the context would be pointed at the old one.
public final class KafkaOutage {

    private KafkaOutage() {
    }

    public static void begin(KafkaContainer kafka) {

        // docker-java commands are AutoCloseable, so try-with-resources is the form expected. For these
        // two commands close() releases nothing; it only keeps the resource-leak inspection quiet.
        try (PauseContainerCmd pause = kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId())) {
            pause.exec();
        }

    }

    public static void end(KafkaContainer kafka) {

        try (UnpauseContainerCmd unpause = kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId())) {
            unpause.exec();
        }

    }
}
