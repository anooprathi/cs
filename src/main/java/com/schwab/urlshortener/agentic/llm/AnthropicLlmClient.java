package com.schwab.urlshortener.agentic.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Real LLM execution through the official Anthropic Java SDK, using
 * structured outputs: the artifact record class passed in IS the JSON
 * schema the API constrains the response to, and the SDK parses the
 * response back into that record. No hand-written JSON parsing, no
 * "extract the JSON from the prose" heuristics.
 *
 * Failure handling is strict by design — every path that doesn't yield a
 * fully-formed artifact throws {@link LlmException}, which fails the stage
 * (and the run) visibly:
 *  - stop_reason "refusal": the model declined; reported with its category.
 *  - stop_reason "max_tokens": output truncated, so the JSON is incomplete.
 *  - SDK/API errors (auth, rate limit, overload, network) after the SDK's
 *    own retries: reported by status/type, never with request content.
 *  - no text block in the response.
 *
 * Deliberately NOT done here: server-side refusal fallbacks to another
 * model. That's a beta endpoint feature whose combination with
 * class-based structured outputs isn't documented for the Java SDK, and
 * this repository's rule is not to ship an integration path that hasn't
 * been verified. A refusal therefore fails the stage with a clear reason.
 *
 * Logs record model, stop reason, and token counts only — never prompt or
 * response text, which contains the operator's requirement.
 */
@Slf4j
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;

    public AnthropicLlmClient(AnthropicClient client, String model, long maxTokens) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public <T> LlmResponse<T> generate(String systemPrompt, String userPrompt, Class<T> outputType) {
        return generate(systemPrompt, userPrompt, outputType, maxTokens);
    }

    @Override
    public <T> LlmResponse<T> generate(String systemPrompt, String userPrompt, Class<T> outputType, long maxOutputTokens) {
        // Last line of defense at the actual network boundary: callers check first, but nothing credential-shaped
        // is ever sent, whichever path reached this client.
        java.util.List<String> secrets = com.schwab.urlshortener.util.SecretPatterns.kindsFound(systemPrompt, userPrompt);
        if (!secrets.isEmpty()) {
            throw new LlmException("Request blocked before sending: the prompt contains what looks like a " + String.join(", ", secrets)
                    + ".", null, false);
        }
        long cap = Math.max(1, Math.min(maxTokens, maxOutputTokens));
        StructuredMessageCreateParams<T> params = buildParams(systemPrompt, userPrompt, outputType, cap);

        StructuredMessage<T> message;
        try {
            message = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            int status = e.statusCode();
            throw new LlmException("Anthropic API returned HTTP " + status
                    + " for " + outputType.getSimpleName() + " (after SDK retries)", e, status == 429 || status >= 500);
        } catch (AnthropicException e) {
            throw new LlmException("Anthropic API call failed for " + outputType.getSimpleName()
                    + ": " + e.getClass().getSimpleName(), e, e instanceof com.anthropic.errors.AnthropicIoException);
        }

        Optional<StopReason> stopReason = message.stopReason();
        long inputTokens = message.usage().inputTokens();
        long outputTokens = message.usage().outputTokens();
        log.info("LLM call model={} artifact={} stopReason={} inputTokens={} outputTokens={}",
                model, outputType.getSimpleName(), stopReason.map(StopReason::toString).orElse("none"),
                inputTokens, outputTokens);

        if (stopReason.isPresent() && StopReason.REFUSAL.equals(stopReason.get())) {
            String category = message.stopDetails()
                    .flatMap(d -> d.category().map(Object::toString))
                    .orElse("unspecified");
            throw new LlmException("Model declined to produce " + outputType.getSimpleName()
                    + " (stop_reason=refusal, category=" + category + ")");
        }
        if (stopReason.isPresent() && StopReason.MAX_TOKENS.equals(stopReason.get())) {
            throw new LlmException("Model output for " + outputType.getSimpleName()
                    + " was truncated at max_tokens=" + cap + "; structured output is incomplete");
        }

        T output;
        try {
            output = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(StructuredTextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new LlmException("Model response for " + outputType.getSimpleName()
                            + " contained no text block"));
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("Model response could not be parsed as " + outputType.getSimpleName(), e);
        }
        return new LlmResponse<>(output, inputTokens, outputTokens);
    }

    /** Package-private so the request shape can be asserted directly in tests without a network call. */
    <T> StructuredMessageCreateParams<T> buildParams(String systemPrompt, String userPrompt, Class<T> outputType) {
        return buildParams(systemPrompt, userPrompt, outputType, maxTokens);
    }

    <T> StructuredMessageCreateParams<T> buildParams(String systemPrompt, String userPrompt, Class<T> outputType, long cap) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(cap)
                .system(systemPrompt)
                .outputConfig(outputType)
                .addUserMessage(userPrompt)
                .build();
    }
}
