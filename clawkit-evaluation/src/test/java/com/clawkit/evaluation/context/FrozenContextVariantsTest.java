package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.TokenizerFactory;
import com.clawkit.tools.schema.Message;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class FrozenContextVariantsTest {
    @TempDir Path temp;
    @Test void oldAndCandidatePipelineLoadDifferentImplementationsWithSharedPublicTypes() throws Exception {
        var artifacts = new EvaluationArtifacts(temp.resolve("baseline"));
        try (var variants = FrozenContextVariants.prepare(artifacts, EvaluationSourceSnapshot.repository().resolve(FrozenContextVariants.BASELINE))) {
            var tokenizer = TokenizerFactory.create("cl100k_base"); var budget = ContextBudgetPolicy.of(4096);
            var analyzer = new ContextBudgetAnalyzer(tokenizer, budget);
            var original = variants.create(ContinuationSpec.Arm.C2_FROZEN_ORIGINAL_CONTEXT, messages -> "采样已记录，没有新增决策。", tokenizer, analyzer, budget);
            var candidate = variants.create(ContinuationSpec.Arm.C3_CANDIDATE_CONTEXT, messages -> "采样已记录，没有新增决策。", tokenizer, analyzer, budget);
            assertThat(original.getClass().getClassLoader()).isNotSameAs(candidate.getClass().getClassLoader());
            assertThat(ContextPipeline.class.isInstance(original)).isTrue();
            var properties = new Properties();
            try (var input = Files.newBufferedReader(EvaluationSourceSnapshot.repository().resolve(
                "clawkit-context/src/test/resources/context/combined-memory-m1-anchor-overflow.properties"))) { properties.load(input); }
            var messages = new ArrayList<Message>();
            for (int i = 0; i < Integer.parseInt(properties.getProperty("message.count")); i++) {
                // Both SYSTEM and TOOL text were historically elevated; SYSTEM avoids constructing unrelated tool protocol here.
                messages.add(Message.system(properties.getProperty("message." + i + ".content")));
            }
            addPressure(messages);
            var request = new CompactionRequest(messages, 0, 20);
            assertThat(original.compact(request).audit().failureCode()).isEqualTo("REQUIRED_ANCHORS_OVER_BUDGET");
            assertThat(candidate.compact(request).audit().failureCode()).isNull();
            String protectedPaths = java.util.stream.IntStream.range(0, 7).mapToObj(i ->
                "/srv/orders/must-preserve-all-these-independent-targets/config-" + i + ".json")
                .collect(java.util.stream.Collectors.joining("\n"));
            var userMessages = new ArrayList<Message>(List.of(Message.user(protectedPaths)));
            addPressure(userMessages);
            var user = new CompactionRequest(userMessages, 0, 20);
            assertThat(original.compact(user).audit().failureCode()).isEqualTo("REQUIRED_ANCHORS_OVER_BUDGET");
            assertThat(candidate.compact(user).audit().failureCode()).isEqualTo("REQUIRED_ANCHORS_OVER_BUDGET");
            assertThat(variants.metadata()).containsKeys("originalJarHash", "sharedLoadedBytecodeHashes");
        }
    }

    @Test void originalPathStrategyCannotFallBackToCandidateExtractor() throws Exception {
        var artifacts = new EvaluationArtifacts(temp.resolve("path-strategy"));
        try (var variants = FrozenContextVariants.prepare(artifacts, EvaluationSourceSnapshot.repository().resolve(FrozenContextVariants.BASELINE))) {
            var tokenizer = TokenizerFactory.create("cl100k_base"); var budget = ContextBudgetPolicy.of(4096);
            var original = variants.create(ContinuationSpec.Arm.C2_FROZEN_ORIGINAL_CONTEXT, null, tokenizer,
                new ContextBudgetAnalyzer(tokenizer, budget), budget);
            var extractorClass = original.getClass().getClassLoader().loadClass("com.clawkit.context.ConstraintExtractor");
            assertThat(extractorClass).isNotSameAs(ConstraintExtractor.class);
            assertThat(extractorClass.getClassLoader()).isSameAs(original.getClass().getClassLoader());
            var input = List.of(Message.user("Read docs/requirements.md; write outputs/final-report.json"));
            @SuppressWarnings("unchecked")
            var oldPaths = (List<Constraint>) extractorClass.getMethod("extract", List.class)
                .invoke(extractorClass.getConstructor().newInstance(), input);
            assertThat(oldPaths.stream().map(Constraint::text)).containsExactly("/requirements.md", "/final-report.json");
            assertThat(new ConstraintExtractor().extract(input).stream().map(Constraint::text))
                .containsExactly("docs/requirements.md", "outputs/final-report.json");
            assertThat(variants.metadata()).containsKey("originalConstraintExtractorHash");
        }
    }

    private static void addPressure(List<Message> messages) {
        for (int turn = 0; turn < 18; turn++) {
            var sample = new StringBuilder("sample,latency,volume,accepted\n");
            for (int row = 0; row < 22; row++) sample.append(turn * 22 + row).append(',')
                .append(31 + turn + row).append(',').append(240 + row).append(",true\n");
            messages.add(Message.user(sample.toString()));
            messages.add(Message.assistant("采样 " + turn + " 已记录。"));
        }
        messages.add(Message.user("继续按此前已确认的边界处理。"));
    }

    @Test void changedManifestCannotBeSilentlyReplacedWithTheCandidate() throws Exception {
        Path archive = temp.resolve("unreviewed"); Files.createDirectories(archive); Files.writeString(archive.resolve("manifest.json"), "{}");
        var artifacts = new EvaluationArtifacts(temp.resolve("refused"));
        assertThatThrownBy(() -> FrozenContextVariants.prepare(artifacts, archive)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unreviewed original");
    }
}
