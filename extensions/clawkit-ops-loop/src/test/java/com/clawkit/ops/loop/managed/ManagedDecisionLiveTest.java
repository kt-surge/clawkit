package com.clawkit.ops.loop.managed;

import com.clawkit.observability.FileRunRecorder;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.ProviderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in paid-provider protocol smoke. Observations are test doubles, NOT a container/production experiment. */
@EnabledIfEnvironmentVariable(named="CLAWKIT_LIVE_DECISION",matches="true")
class ManagedDecisionLiveTest {
    @Test void actualModelChoosesProbesAndSubmitsEvidenceBoundDecision() throws Exception {
        String key = System.getenv("CLAWKIT_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("CLAWKIT_API_KEY required");
        var builder = LLMConfig.builder().apiKey(key).requestTimeout(Duration.ofSeconds(45)).maxRetries(0);
        String model = System.getenv("CLAWKIT_MODEL");
        if (model != null && !model.isBlank()) builder.model(model);
        var config = builder.build();
        Path output = Path.of(System.getProperty("clawkit.live.output", "target/managed-decision-live"))
            .toAbsolutePath().normalize();
        Files.createDirectories(output);
        var json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        Files.writeString(output.resolve("metadata.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
            Map.of("evidenceKind","ACTUAL_MODEL_TEST_DOUBLE_OBSERVATIONS","model",config.model(),
                "providerRetries",0,"providerCallLimit",6,"toolCallLimit",12,"tokenLimit",30000,
                "deadlineSeconds",120,"scenario","stopped-stateless-service-proposal","application",ManagedDecisionTest.app())));
        try (var recorder = new FileRunRecorder(output)) {
            var agent = new OpsDecisionAgent(ProviderFactory.create(config),output,recorder,Clock.systemUTC(),OpsDecisionAgent.Limits.defaults());
            var outcome = agent.decide(ManagedDecisionTest.app(),(app,probe) -> {
                var sample = ManagedDecisionTest.observer(ManagedObserver.Status.STOPPED).observe(app,probe);
                return new ManagedObserver.Observation(sample.targetId(),sample.composeProject(),sample.service(),probe,
                    Clock.systemUTC().instant(),sample.status(),sample.detail());
            });
            Files.writeString(output.resolve("outcome.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(outcome));
            assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.MODEL);
            assertThat(outcome.failureType()).isNull();
            assertThat(outcome.evidence()).isNotEmpty();
            assertThat(outcome.decision().disposition()).isEqualTo(OpsDecision.Disposition.PROPOSE_ACTION);
            assertThat(outcome.decision().playbook()).isEqualTo(OpsDecision.Playbook.START_STOPPED_V1);
        }
    }
}
