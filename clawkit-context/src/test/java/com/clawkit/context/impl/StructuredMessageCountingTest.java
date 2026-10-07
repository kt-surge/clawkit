package com.clawkit.context.impl;

import com.clawkit.context.*;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Protocol payload regressions, not actual provider billing. */
class StructuredMessageCountingTest {
    private final Tokenizer characters=new Tokenizer(){public int countTokens(String text){return text==null?0:text.length();}public String encodingName(){return "deterministic-character-fixture";}};
    @Test void toolArgumentsCannotDisappearWhenAssistantBodyIsNull(){
        var payload=new ObjectMapper().createObjectNode().put("path","preview/config.json").put("content","x".repeat(16000));
        var message=Message.assistantWithTools(List.of(new ToolCall("write-large","write",payload)));
        assertThat(characters.countTokens(List.of(message))).isGreaterThan(16000);
        var report=new ContextBudgetAnalyzer(characters,ContextBudgetPolicy.of(4096)).analyze(List.of(message),0,Map.of());
        assertThat(report.totalTokens()).isGreaterThan(16000);assertThat(report.status()).isEqualTo(ContextBudgetReport.BudgetStatus.HARD_LIMIT);
        assertThat(report.sections().get(ContextSection.HISTORY)).isEqualTo(report.totalTokens());
    }
    @Test void reasoningAndToolResultIdentityAreCountedWithoutReplacingTheirBodies(){
        var plain=Message.assistant("body");var reasoning=new Message(Role.ASSISTANT,"body",null,null,"reasoning".repeat(100));
        assertThat(characters.countTokens(List.of(reasoning))-characters.countTokens(List.of(plain))).isEqualTo(900);
        var result=Message.toolResult("tool-identity","facts");
        assertThat(characters.countTokens(List.of(result))).isGreaterThan(characters.countTokens("facts"));
        var report=new ContextBudgetAnalyzer(characters,ContextBudgetPolicy.of(4096)).analyze(List.of(reasoning,result),50,Map.of());
        assertThat(report.totalTokens()).isEqualTo(characters.countTokens(List.of(reasoning,result))+50);
    }
}
