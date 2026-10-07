package com.clawkit.context;

import com.clawkit.tools.schema.Message;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConstraintExtractorTest {
    @Test void preservesFullRelativePathsAndDoesNotInventSuffixesFromUris() {
        var found = new ConstraintExtractor().extract(List.of(Message.user(
            "Read docs/requirements.md; write preview/final-report.json and 配置/服务.json. "
            + "Preserve C:/workspace/config.json and /srv/app/state.json. "
            + "Links https://example.test/docs/guide.md and run://sample/tools/read are not paths. Use glob/grep/read or 静态/SSR.")));
        assertThat(found.stream().map(Constraint::text)).containsExactly(
            "docs/requirements.md", "preview/final-report.json", "配置/服务.json",
            "C:/workspace/config.json", "/srv/app/state.json");
        assertThat(new ConstraintExtractor().verify(
            List.of(new Constraint.FilePath("preview/final-report.json")),
            List.of(Message.assistant("wrote preview/report.json")))).hasSize(1);
    }
    @Test void preservesWindowsSeparatorsAndExplicitDotRelativePaths() {
        var found = new ConstraintExtractor().extract(List.of(Message.user(
            "Read ./.clawkit/rules.md and ../notes/state.json; preserve C:\\repo\\config.json.")));
        assertThat(found.stream().map(Constraint::text)).containsExactly(
            "./.clawkit/rules.md", "../notes/state.json", "C:\\repo\\config.json");
    }
}
