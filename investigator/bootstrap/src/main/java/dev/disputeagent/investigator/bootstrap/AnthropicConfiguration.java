package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.anthropic.AnthropicAdapter;
import dev.disputeagent.investigator.adapters.anthropic.PriceTable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** The live model on the Anthropic SDK; the default {@code investigator.llm.live}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "investigator.llm.live", havingValue = "anthropic", matchIfMissing = true)
class AnthropicConfiguration {

    @Bean
    @Lazy
    LiveLlm liveLlm(InvestigatorProperties properties, PriceTable prices) {
        var llm = properties.llm();
        var adapter = new AnthropicAdapter(
                AnthropicAdapter.client(llm.apiKey(), llm.baseUrl(), llm.timeout(), llm.maxRetries()), prices);
        return adapter::send;
    }
}
