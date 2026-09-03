package com.schwab.urlshortener.service.safety;

import com.schwab.urlshortener.client.UrlSafetyClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeignUrlSafetyCheckerTest {

    @Mock
    private UrlSafetyClient client;

    @Test
    void isSafe_delegatesToClient_safeResult() {
        FeignUrlSafetyChecker checker = new FeignUrlSafetyChecker(client);
        when(client.check("https://example.com")).thenReturn(new UrlSafetyClient.UrlSafetyResult(true, null));

        assertThat(checker.isSafe("https://example.com")).isTrue();
    }

    @Test
    void isSafe_delegatesToClient_unsafeResult() {
        FeignUrlSafetyChecker checker = new FeignUrlSafetyChecker(client);
        when(client.check("https://malicious.example")).thenReturn(new UrlSafetyClient.UrlSafetyResult(false, "phishing"));

        assertThat(checker.isSafe("https://malicious.example")).isFalse();
    }

    @Test
    void isSafe_clientThrows_failsOpenAndReturnsTrue() {
        FeignUrlSafetyChecker checker = new FeignUrlSafetyChecker(client);
        when(client.check("https://example.com")).thenThrow(new RuntimeException("connection refused"));

        assertThat(checker.isSafe("https://example.com")).isTrue();
    }
}
