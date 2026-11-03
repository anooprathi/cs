package com.schwab.urlshortener.agentic.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SDK boundary of real LLM mode, tested WITHOUT network access: the
 * Anthropic client is mocked, so these tests verify the request we build
 * and how every response/failure shape is handled. They do not and cannot
 * show that a live model returns good artifacts. That needs an actual
 * API call (see AnthropicLiveSmokeTest, opt-in).
 */
class AnthropicLlmClientTest {

    private AnthropicClient client;
    private MessageService messages;
    private AnthropicLlmClient llm;

    @BeforeEach
    void setUp() {
        client = mock(AnthropicClient.class);
        messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        llm = new AnthropicLlmClient(client, "claude-opus-5", 16_000);
    }

    @Test
    void credentialInThePrompt_isBlockedBeforeTheSdkIsCalled() {
        assertThatThrownBy(() -> llm.generate("sys", "key usk_SYNTHETICprobeKEY0000000000", RiskAssessment.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("blocked before sending")
                .hasMessageNotContaining("usk_SYNTHETIC");
        org.mockito.Mockito.verifyNoInteractions(messages);
    }

    @Test
    void buildParams_setsModelMaxTokensSystemUserAndStructuredOutputSchema() {
        StructuredMessageCreateParams<RiskAssessment> params = llm.buildParams("SYS", "USER", RiskAssessment.class);
        MessageCreateParams raw = params.rawParams();

        assertThat(raw.model().asString()).isEqualTo("claude-opus-5");
        assertThat(raw.maxTokens()).isEqualTo(16_000);
        assertThat(raw.system()).hasValueSatisfying(s -> assertThat(s.asString()).isEqualTo("SYS"));
        assertThat(raw.messages()).singleElement().satisfies(m -> assertThat(m.content().asString()).isEqualTo("USER"));
        // the artifact record became the JSON schema the API must conform to
        assertThat(raw.outputConfig()).hasValueSatisfying(oc -> assertThat(oc.format()).isPresent());
        assertThat(raw.outputConfig().get().format().get().schema().toString()).contains("severity").contains("mitigation");
        assertThat(llm.modelId()).isEqualTo("claude-opus-5");
    }

    @Test
    void successfulResponse_returnsParsedArtifactAndUsage() {
        RiskAssessment expected = new RiskAssessment(List.of());
        stubResponse(StopReason.END_TURN, Optional.of(expected), Optional.empty());

        LlmResponse<RiskAssessment> response = llm.generate("s", "u", RiskAssessment.class);

        assertThat(response.output()).isSameAs(expected);
        assertThat(response.inputTokens()).isEqualTo(1200);
        assertThat(response.outputTokens()).isEqualTo(340);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<StructuredMessageCreateParams<RiskAssessment>> captor = ArgumentCaptor.forClass(StructuredMessageCreateParams.class);
        verify(messages).create(captor.capture());
        assertThat(captor.getValue().rawParams().model().asString()).isEqualTo("claude-opus-5");
    }

    @Test
    void refusal_failsWithCategory_neverReturnsPartialOutput() {
        RefusalStopDetails details = mock(RefusalStopDetails.class);
        when(details.category()).thenReturn(Optional.empty());
        stubResponse(StopReason.REFUSAL, Optional.empty(), Optional.of(details));

        assertThatThrownBy(() -> llm.generate("s", "u", RiskAssessment.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("stop_reason=refusal")
                .hasMessageContaining("category=unspecified");
    }

    @Test
    void maxTokens_failsAsTruncated() {
        stubResponse(StopReason.MAX_TOKENS, Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> llm.generate("s", "u", RiskAssessment.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("truncated at max_tokens=16000");
    }

    @Test
    void noTextBlock_fails() {
        stubResponse(StopReason.END_TURN, Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> llm.generate("s", "u", RiskAssessment.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("contained no text block");
    }

    @Test
    void unparseableOutput_failsAsParseError() {
        @SuppressWarnings("unchecked")
        StructuredMessage<RiskAssessment> message = baseMessage(StopReason.END_TURN);
        @SuppressWarnings("unchecked")
        StructuredContentBlock<RiskAssessment> block = mock(StructuredContentBlock.class);
        @SuppressWarnings("unchecked")
        StructuredTextBlock<RiskAssessment> text = mock(StructuredTextBlock.class);
        when(text.text()).thenThrow(new IllegalStateException("bad json"));
        when(block.text()).thenReturn(Optional.of(text));
        when(message.content()).thenReturn(List.of(block));
        when(messages.create(any(StructuredMessageCreateParams.class))).thenReturn((StructuredMessage) message);

        assertThatThrownBy(() -> llm.generate("s", "u", RiskAssessment.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("could not be parsed as RiskAssessment");
    }

    @Test
    void apiError_reportsStatusOnly() {
        AnthropicServiceException apiError = mock(AnthropicServiceException.class);
        when(apiError.statusCode()).thenReturn(529);
        when(messages.create(any(StructuredMessageCreateParams.class))).thenThrow(apiError);

        assertThatThrownBy(() -> llm.generate("s", "secret requirement text", RiskAssessment.class))
                .isInstanceOf(LlmException.class)
                .hasMessage("Anthropic API returned HTTP 529 for RiskAssessment (after SDK retries)");
    }

    @Test
    void transportError_reportsTypeOnly() {
        when(messages.create(any(StructuredMessageCreateParams.class))).thenThrow(new AnthropicIoException("connection reset"));

        assertThatThrownBy(() -> llm.generate("s", "u", RiskAssessment.class))
                .isInstanceOf(LlmException.class)
                .hasMessage("Anthropic API call failed for RiskAssessment: AnthropicIoException");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubResponse(StopReason stopReason, Optional<RiskAssessment> output, Optional<RefusalStopDetails> details) {
        StructuredMessage<RiskAssessment> message = baseMessage(stopReason);
        when(message.stopDetails()).thenReturn(details);
        if (output.isPresent()) {
            StructuredContentBlock<RiskAssessment> block = mock(StructuredContentBlock.class);
            StructuredTextBlock<RiskAssessment> text = mock(StructuredTextBlock.class);
            when(text.text()).thenReturn(output.get());
            when(block.text()).thenReturn(Optional.of(text));
            when(message.content()).thenReturn(List.of(block));
        } else {
            when(message.content()).thenReturn(List.of());
        }
        when(messages.create(any(StructuredMessageCreateParams.class))).thenReturn((StructuredMessage) message);
    }

    @SuppressWarnings("unchecked")
    private StructuredMessage<RiskAssessment> baseMessage(StopReason stopReason) {
        StructuredMessage<RiskAssessment> message = mock(StructuredMessage.class);
        Usage usage = mock(Usage.class);
        when(usage.inputTokens()).thenReturn(1200L);
        when(usage.outputTokens()).thenReturn(340L);
        when(message.usage()).thenReturn(usage);
        when(message.stopReason()).thenReturn(Optional.of(stopReason));
        return message;
    }
}
