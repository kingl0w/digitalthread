package com.aetnios.dt.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Replays canonical failure events as Kafka messages. Strips the resolved unitId/assetId so the
 * write service must resolve entities from the raw identity fields, the way a live feed would
 * arrive. A real deployment replaces this with per-source adapters; the topic contract is the same.
 */
@Component
public class Replay implements ApplicationRunner {

    static final String TOPIC = "dt.failures";

    private final KafkaTemplate<String, String> kafka;
    private final ConfigurableApplicationContext ctx;

    public Replay(KafkaTemplate<String, String> kafka, ConfigurableApplicationContext ctx) {
        this.kafka = kafka;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("replay")) return;
        Path file = Path.of(args.getOptionValues("replay").isEmpty()
                ? "../digital-thread-core/data/canonical/failure_event.jsonl"
                : args.getOptionValues("replay").get(0));
        ObjectMapper mapper = new ObjectMapper();
        long sent = 0;
        for (String line : Files.readAllLines(file)) {
            ObjectNode e = (ObjectNode) mapper.readTree(line);
            e.remove("unitId");
            e.remove("assetId");
            kafka.send(TOPIC, e.path("id").asText(), e.toString());
            sent++;
        }
        kafka.flush();
        System.out.printf("replayed %d events to %s%n", sent, TOPIC);
        ctx.close();
    }
}
