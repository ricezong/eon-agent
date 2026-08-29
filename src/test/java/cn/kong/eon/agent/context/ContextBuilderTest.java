package cn.kong.eon.agent.context;

import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBuilderTest {

    @Test
    void build_empty_returnsEmptyList() {
        ContextBuilder builder = new ContextBuilder();
        List<ChatMessage> result = builder.build();
        assertThat(result).isEmpty();
    }

    @Test
    void build_withSystemPrompt_addsSystemMessage() {
        ContextBuilder builder = new ContextBuilder()
                .setSystemPrompt("You are a helpful assistant.");
        List<ChatMessage> result = builder.build();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) result.get(0)).text()).isEqualTo("You are a helpful assistant.");
    }

    @Test
    void build_withSummary_wrapsInSummaryTags() {
        ContextBuilder builder = new ContextBuilder()
                .setSummary("Previous conversation summary");
        List<ChatMessage> result = builder.build();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        String text = ((SystemMessage) result.get(0)).text();
        assertThat(text).contains("<summary>").contains("Previous conversation summary").contains("</summary>");
    }

    @Test
    void build_allLayers_inCorrectOrder() {
        List<ChatMessage> transcript = List.of(
                UserMessage.from("user input"),
                AiMessage.from("assistant reply")
        );

        ContextBuilder builder = new ContextBuilder()
                .setSystemPrompt("system prompt")
                .setSummary("summary text")
                .setTranscript(transcript)
                .setMemories("memory content")
                .setNavigator("todo navigator")
                .setRuntimeNudges("nudge text");

        List<ChatMessage> result = builder.build();

        // System + Summary + 2 transcript + memories + navigator + nudges = 7
        assertThat(result).hasSize(7);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.get(1)).isInstanceOf(SystemMessage.class); // summary as SystemMessage
        assertThat(result.get(2)).isInstanceOf(UserMessage.class);    // transcript user
        assertThat(result.get(3)).isInstanceOf(AiMessage.class);      // transcript ai
        assertThat(result.get(4)).isInstanceOf(UserMessage.class);  // memories
        assertThat(result.get(5)).isInstanceOf(UserMessage.class);  // navigator
        assertThat(result.get(6)).isInstanceOf(UserMessage.class);  // nudges
    }

    @Test
    void build_blankLayers_areSkipped() {
        ContextBuilder builder = new ContextBuilder()
                .setSystemPrompt("sys")
                .setSummary("")
                .setMemories(null)
                .setNavigator("  ")
                .setRuntimeNudges("nudge");

        List<ChatMessage> result = builder.build();
        // System + nudges = 2 (empty/null/blank layers skipped)
        assertThat(result).hasSize(2);
    }

    @Test
    void estimateTokens_withoutEstimator_usesCharDiv2() {
        ContextBuilder builder = new ContextBuilder()
                .setSystemPrompt("12345678"); // 8 chars -> 4 tokens
        long tokens = builder.estimateTokens();
        assertThat(tokens).isEqualTo(4);
    }

    /**
     * 逐块估算，而不是"总字符数 / 2"。
     * <p>
     * 旧口径把 9 个字符合起来算成 4 token；新口径按块算：
     * 每块 3 字符 → 1 token，合计 3 token。
     * 新口径更贴近真实分词器——它是按消息分别编码的，不会跨消息合并。
     */
    @Test
    void estimateTokens_withTranscript_includesAllMessages() {
        ContextBuilder builder = new ContextBuilder()
                .setSystemPrompt("abc")            // 3 chars -> 1 token
                .setTranscript(List.of(
                        UserMessage.from("def"),       // 3 chars -> 1 token
                        AiMessage.from("ghi")          // 3 chars -> 1 token
                ));
        long tokens = builder.estimateTokens();
        assertThat(tokens).isEqualTo(3);
    }

    /**
     * transcript 现在是窗口的<b>投影</b>：{@code setTranscript} 把消息爆炸为块，
     * {@code getTranscript} 再把块组装回消息。因此内容等价，但不是同一个对象。
     * <p>
     * 这个"不再是同一实例"的变化正是本次改造的目的：
     * 窗口才是真相，消息只是进出 LLM 时的临时视图。
     */
    @Test
    void getTranscript_returnsProjectionOfWindow() {
        List<ChatMessage> transcript = List.of(UserMessage.from("test"));
        ContextBuilder builder = new ContextBuilder().setTranscript(transcript);

        assertThat(builder.getTranscript())
                .isNotSameAs(transcript)
                .containsExactlyElementsOf(transcript);
    }
}
