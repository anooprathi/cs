package com.schwab.urlshortener.service;

import com.schwab.urlshortener.billing.UsageMeteringService;
import com.schwab.urlshortener.dto.ShortenUrlRequest;
import com.schwab.urlshortener.dto.ShortenUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.exception.DuplicateAliasException;
import com.schwab.urlshortener.exception.RateLimitExceededException;
import com.schwab.urlshortener.exception.ShortCodeGenerationException;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.ratelimit.RateLimitResult;
import com.schwab.urlshortener.ratelimit.TenantRateLimiterService;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import com.schwab.urlshortener.service.impl.CachedShortCodeLookup;
import com.schwab.urlshortener.service.impl.UrlMappingMapper;
import com.schwab.urlshortener.service.impl.UrlShortenerServiceImpl;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import com.schwab.urlshortener.tenant.TenantService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UrlShortenerServiceImplTest {

    private static final Long TENANT_ID = 42L;

    @Mock
    private UrlMappingRepository repository;

    @Mock
    private TenantService tenantService;

    @Mock
    private CachedShortCodeLookup cachedShortCodeLookup;

    @Mock
    private UrlSafetyChecker urlSafetyChecker;

    @Mock
    private TenantRateLimiterService rateLimiterService;

    @Mock
    private UsageMeteringService usageMeteringService;

    @Mock
    private ShortCodeGenerator shortCodeGenerator;

    // Pure/stateless — a real instance is simpler and more honest than mocking it.
    private final UrlMappingMapper mapper = new UrlMappingMapper();

    private UrlShortenerServiceImpl service;

    @BeforeEach
    void setUp() {
        when(urlSafetyChecker.isSafe(anyString())).thenReturn(true);
        when(shortCodeGenerator.generateCandidate()).thenReturn("aZ3kQ9m");
        when(tenantService.getPlanForRateLimiting(anyLong())).thenReturn(RateLimitPlan.STANDARD);
        when(rateLimiterService.tryConsumeRedirectPermit(anyLong(), any()))
                .thenReturn(new RateLimitResult(true, 99, 100, 0));

        service = new UrlShortenerServiceImpl(repository, tenantService, cachedShortCodeLookup, urlSafetyChecker,
                rateLimiterService, usageMeteringService, shortCodeGenerator, mapper, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");
    }

    // ---------- createShortUrl ----------

    @Test
    void createShortUrl_withoutCustomAlias_delegatesToGeneratorAndSaves() {
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com/very/long/path", null, null);
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> {
            UrlMapping m = inv.getArgument(0);
            m.setId(1L);
            m.setCreatedAt(Instant.now());
            return m;
        });

        ShortenUrlResponse response = service.createShortUrl(request, TENANT_ID);

        assertThat(response.shortCode()).isEqualTo("aZ3kQ9m");
        assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/aZ3kQ9m");
        assertThat(response.originalUrl()).isEqualTo(request.originalUrl());
        verify(shortCodeGenerator).generateCandidate();
        verify(repository).save(argThat(m -> TENANT_ID.equals(m.getTenantId())));
        verify(usageMeteringService).recordApiCall(TENANT_ID);
    }

    @Test
    void createShortUrl_withCustomAlias_usesProvidedAliasAndSkipsGenerator() {
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com", "myAlias", null);
        when(repository.existsByShortCode("myAlias")).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> {
            UrlMapping m = inv.getArgument(0);
            m.setId(1L);
            m.setCreatedAt(Instant.now());
            return m;
        });

        ShortenUrlResponse response = service.createShortUrl(request, TENANT_ID);

        assertThat(response.shortCode()).isEqualTo("myAlias");
        verifyNoInteractions(shortCodeGenerator);
    }

    @Test
    void createShortUrl_withDuplicateCustomAlias_throwsConflict() {
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com", "taken", null);
        when(repository.existsByShortCode("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.createShortUrl(request, TENANT_ID))
                .isInstanceOf(DuplicateAliasException.class)
                .hasMessageContaining("taken");

        verify(repository, never()).save(any());
    }

    @Test
    void createShortUrl_customAlias_racesPastPreCheck_stillThrowsDuplicateConflict() {
        // The existsByShortCode pre-check passes (alias looked free), but a
        // concurrent request wins the race and the DB's unique constraint
        // rejects the insert — this is the actual ACID guarantee, and it must
        // surface as the same clean 409-mapped exception, not an unhandled 500.
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com", "taken", null);
        when(repository.existsByShortCode("taken")).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        assertThatThrownBy(() -> service.createShortUrl(request, TENANT_ID))
                .isInstanceOf(DuplicateAliasException.class)
                .hasMessageContaining("taken");
    }

    @Test
    void createShortUrl_whenGeneratorAlwaysCollides_throwsGenerationExceptionAfterBoundedRetries() {
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com", null, null);
        when(repository.existsByShortCode(anyString())).thenReturn(true); // every candidate collides

        assertThatThrownBy(() -> service.createShortUrl(request, TENANT_ID))
                .isInstanceOf(ShortCodeGenerationException.class);

        // Bounded retry policy lives in the service, not the generator — prove it's actually bounded.
        verify(shortCodeGenerator, times(5)).generateCandidate();
        verify(repository, never()).save(any());
    }

    @Test
    void createShortUrl_generatedCode_racesPastPreCheck_failsCleanlyNotWithA500() {
        // Vanishingly rare in practice (a random 7-char collision AND a
        // concurrent request for the exact same value), but must still fail
        // as a clean, documented exception rather than an unhandled 500.
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com", null, null);
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        assertThatThrownBy(() -> service.createShortUrl(request, TENANT_ID))
                .isInstanceOf(ShortCodeGenerationException.class);

        // Deliberately NOT retried with a fresh candidate in-place — see the
        // Javadoc on UrlShortenerServiceImpl.createWithGeneratedCode for why.
        verify(shortCodeGenerator, times(1)).generateCandidate();
    }

    @Test
    void createShortUrl_flaggedUnsafeByChecker_throwsInvalidUrl() {
        ShortenUrlRequest request = new ShortenUrlRequest("https://malicious.example.com", null, null);
        when(urlSafetyChecker.isSafe("https://malicious.example.com")).thenReturn(false);

        assertThatThrownBy(() -> service.createShortUrl(request, TENANT_ID))
                .isInstanceOf(com.schwab.urlshortener.exception.InvalidUrlException.class);

        verify(repository, never()).save(any());
        verifyNoInteractions(shortCodeGenerator);
    }

    // ---------- resolveAndRecordHit ----------

    @Test
    void resolveAndRecordHit_validActiveCode_returnsOriginalUrlAndIncrements() {
        when(cachedShortCodeLookup.findActive("abc1234"))
                .thenReturn(new CachedShortCodeLookup.RedirectTarget(TENANT_ID, "https://example.com", null));

        String result = service.resolveAndRecordHit("abc1234");

        assertThat(result).isEqualTo("https://example.com");
        verify(repository).incrementClickCount(eq("abc1234"), any(Instant.class));
        verify(usageMeteringService).recordRedirect(TENANT_ID);
    }

    @Test
    void resolveAndRecordHit_unknownCode_throwsNotFound() {
        when(cachedShortCodeLookup.findActive("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.resolveAndRecordHit("missing"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolveAndRecordHit_expiredCode_throwsExpiredAndDoesNotIncrement() {
        when(cachedShortCodeLookup.findActive("old1234")).thenReturn(new CachedShortCodeLookup.RedirectTarget(
                TENANT_ID, "https://example.com", Instant.now().minus(1, ChronoUnit.DAYS)));

        assertThatThrownBy(() -> service.resolveAndRecordHit("old1234"))
                .isInstanceOf(UrlExpiredException.class);

        verify(repository, never()).incrementClickCount(anyString(), any());
    }

    @Test
    void resolveAndRecordHit_ownerTenantRateLimited_throwsRateLimitExceededAndDoesNotIncrement() {
        when(cachedShortCodeLookup.findActive("hot1234"))
                .thenReturn(new CachedShortCodeLookup.RedirectTarget(TENANT_ID, "https://example.com", null));
        when(rateLimiterService.tryConsumeRedirectPermit(eq(TENANT_ID), any()))
                .thenReturn(new RateLimitResult(false, 0, 100, 30));

        assertThatThrownBy(() -> service.resolveAndRecordHit("hot1234"))
                .isInstanceOf(RateLimitExceededException.class);

        verify(repository, never()).incrementClickCount(anyString(), any());
        verify(usageMeteringService, never()).recordRedirect(any());
    }

    // ---------- getStats ----------

    @Test
    void getStats_existingCode_returnsStats() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).tenantId(TENANT_ID).shortCode("abc1234").originalUrl("https://example.com")
                .active(true).clickCount(42L).createdAt(Instant.now()).build();
        when(repository.findByShortCodeAndActiveTrueAndTenantId("abc1234", TENANT_ID)).thenReturn(Optional.of(mapping));

        UrlStatsResponse stats = service.getStats("abc1234", TENANT_ID);

        assertThat(stats.clickCount()).isEqualTo(42L);
        assertThat(stats.shortCode()).isEqualTo("abc1234");
    }

    @Test
    void getStats_unknownCode_throwsNotFound() {
        when(repository.findByShortCodeAndActiveTrueAndTenantId("missing", TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStats("missing", TENANT_ID))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void getStats_belongsToDifferentTenant_throwsNotFound_notLeakingExistence() {
        Long otherTenantId = 999L;
        // Repository is tenant-scoped at the query level, so a mismatched
        // tenant simply finds nothing — same as a nonexistent code.
        when(repository.findByShortCodeAndActiveTrueAndTenantId("someones-link", otherTenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStats("someones-link", otherTenantId))
                .isInstanceOf(UrlNotFoundException.class);
    }

    // ---------- deactivate ----------

    @Test
    void deactivate_existingCode_setsInactiveAndSaves() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).tenantId(TENANT_ID).shortCode("abc1234").originalUrl("https://example.com")
                .active(true).build();
        when(repository.findByShortCodeAndActiveTrueAndTenantId("abc1234", TENANT_ID)).thenReturn(Optional.of(mapping));
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate("abc1234", TENANT_ID);

        assertThat(mapping.isActive()).isFalse();
        verify(repository).save(mapping);
        verify(cachedShortCodeLookup).evict("abc1234");
    }

    @Test
    void deactivate_unknownCode_throwsNotFound() {
        when(repository.findByShortCodeAndActiveTrueAndTenantId("missing", TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate("missing", TENANT_ID))
                .isInstanceOf(UrlNotFoundException.class);
    }
}
