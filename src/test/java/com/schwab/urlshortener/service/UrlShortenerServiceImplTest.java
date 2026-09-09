package com.schwab.urlshortener.service;

import com.schwab.urlshortener.billing.UsageMeteringService;
import com.schwab.urlshortener.dto.PageResponse;
import com.schwab.urlshortener.dto.ShortenUrlRequest;
import com.schwab.urlshortener.dto.ShortenUrlResponse;
import com.schwab.urlshortener.dto.UpdateUrlRequest;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.exception.DuplicateAliasException;
import com.schwab.urlshortener.exception.InvalidUrlException;
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
import com.schwab.urlshortener.tenant.Tenant;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
        // No custom domain by default — most tests exercise the platform-default host;
        // see the dedicated "brandedShortUrl" tests below for the custom-domain path.
        when(tenantService.getTenantOrThrow(anyLong())).thenReturn(Tenant.builder().id(TENANT_ID).build());
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

    // ---------- createShortUrl: branded shortUrl (custom domain) ----------

    @Test
    void createShortUrl_tenantWithNoCustomDomain_usesPlatformDefaultHost() {
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com", null, null);
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> {
            UrlMapping m = inv.getArgument(0);
            m.setId(1L);
            m.setCreatedAt(Instant.now());
            return m;
        });

        ShortenUrlResponse response = service.createShortUrl(request, TENANT_ID);

        assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/aZ3kQ9m");
    }

    @Test
    void createShortUrl_tenantWithCustomDomain_usesBrandedHost() {
        when(tenantService.getTenantOrThrow(TENANT_ID))
                .thenReturn(Tenant.builder().id(TENANT_ID).customDomain("go.acme.com").build());
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com", null, null);
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> {
            UrlMapping m = inv.getArgument(0);
            m.setId(1L);
            m.setCreatedAt(Instant.now());
            return m;
        });

        ShortenUrlResponse response = service.createShortUrl(request, TENANT_ID);

        assertThat(response.shortUrl()).isEqualTo("https://go.acme.com/aZ3kQ9m");
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

    // ---------- listMyUrls ----------

    @Test
    void listMyUrls_returnsTenantsOwnLinksIncludingInactive() {
        UrlMapping active = UrlMapping.builder().shortCode("abc1234").originalUrl("https://example.com").active(true).clickCount(1).createdAt(Instant.now()).build();
        UrlMapping inactive = UrlMapping.builder().shortCode("old1234").originalUrl("https://example.com/old").active(false).clickCount(9).createdAt(Instant.now()).build();
        Pageable requested = PageRequest.of(0, 50);
        when(repository.findByTenantId(eq(TENANT_ID), any())).thenReturn(new PageImpl<>(List.of(active, inactive), requested, 2));

        PageResponse<UrlStatsResponse> result = service.listMyUrls(TENANT_ID, requested);

        assertThat(result.content()).hasSize(2);
        assertThat(result.content()).extracting(UrlStatsResponse::active).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void listMyUrls_noSortRequested_defaultsToCreatedAtDescending() {
        Pageable unsorted = PageRequest.of(0, 50);
        when(repository.findByTenantId(eq(TENANT_ID), any())).thenReturn(new PageImpl<>(List.of(), unsorted, 0));

        service.listMyUrls(TENANT_ID, unsorted);

        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByTenantId(eq(TENANT_ID), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void listMyUrls_explicitSortRequested_isHonoredNotOverridden() {
        // Regression test: this used to unconditionally override any
        // caller-supplied sort with createdAt DESC, making ?sort= inoperable.
        Pageable requestedSort = PageRequest.of(0, 50, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "clickCount"));
        when(repository.findByTenantId(eq(TENANT_ID), any())).thenReturn(new PageImpl<>(List.of(), requestedSort, 0));

        service.listMyUrls(TENANT_ID, requestedSort);

        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByTenantId(eq(TENANT_ID), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "clickCount"));
    }

    // ---------- updateUrl ----------

    @Test
    void updateUrl_changesDestination_evictsCache() {
        UrlMapping mapping = UrlMapping.builder().id(1L).tenantId(TENANT_ID).shortCode("abc1234").originalUrl("https://old.example.com").active(true).build();
        when(repository.findByShortCodeAndTenantId("abc1234", TENANT_ID)).thenReturn(Optional.of(mapping));
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlStatsResponse result = service.updateUrl("abc1234", TENANT_ID, new UpdateUrlRequest("https://new.example.com", null));

        assertThat(result.originalUrl()).isEqualTo("https://new.example.com");
        verify(cachedShortCodeLookup).evict("abc1234");
    }

    @Test
    void updateUrl_changesExpiryOnly_leavesDestinationUntouched() {
        Instant newExpiry = Instant.now().plus(30, ChronoUnit.DAYS);
        UrlMapping mapping = UrlMapping.builder().id(1L).tenantId(TENANT_ID).shortCode("abc1234").originalUrl("https://example.com").active(true).build();
        when(repository.findByShortCodeAndTenantId("abc1234", TENANT_ID)).thenReturn(Optional.of(mapping));
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlStatsResponse result = service.updateUrl("abc1234", TENANT_ID, new UpdateUrlRequest(null, newExpiry));

        assertThat(result.originalUrl()).isEqualTo("https://example.com");
        assertThat(result.expiresAt()).isEqualTo(newExpiry);
    }

    @Test
    void updateUrl_neitherFieldProvided_throwsInvalidUrl_beforeAnyLookup() {
        assertThatThrownBy(() -> service.updateUrl("abc1234", TENANT_ID, new UpdateUrlRequest(null, null)))
                .isInstanceOf(InvalidUrlException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void updateUrl_newDestinationFlaggedUnsafe_throwsInvalidUrl_doesNotSave() {
        UrlMapping mapping = UrlMapping.builder().id(1L).tenantId(TENANT_ID).shortCode("abc1234").originalUrl("https://example.com").active(true).build();
        when(repository.findByShortCodeAndTenantId("abc1234", TENANT_ID)).thenReturn(Optional.of(mapping));
        when(urlSafetyChecker.isSafe("https://malicious.example.com")).thenReturn(false);

        assertThatThrownBy(() -> service.updateUrl("abc1234", TENANT_ID, new UpdateUrlRequest("https://malicious.example.com", null)))
                .isInstanceOf(InvalidUrlException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void updateUrl_unknownOrNotOwnedCode_throwsNotFound() {
        when(repository.findByShortCodeAndTenantId("missing", TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateUrl("missing", TENANT_ID, new UpdateUrlRequest("https://example.com", null)))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void updateUrl_deactivatedLink_canStillBeUpdated() {
        // Editing a destination/expiry before reactivating is a legitimate
        // sequence — update deliberately doesn't require active=true.
        UrlMapping inactive = UrlMapping.builder().id(1L).tenantId(TENANT_ID).shortCode("old1234").originalUrl("https://example.com").active(false).build();
        when(repository.findByShortCodeAndTenantId("old1234", TENANT_ID)).thenReturn(Optional.of(inactive));
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlStatsResponse result = service.updateUrl("old1234", TENANT_ID, new UpdateUrlRequest("https://fixed.example.com", null));

        assertThat(result.originalUrl()).isEqualTo("https://fixed.example.com");
    }

    // ---------- reactivate ----------

    @Test
    void reactivate_deactivatedLink_setsActiveTrue_noCacheEvictionNeeded() {
        UrlMapping mapping = UrlMapping.builder().id(1L).tenantId(TENANT_ID).shortCode("old1234").originalUrl("https://example.com").active(false).build();
        when(repository.findByShortCodeAndTenantId("old1234", TENANT_ID)).thenReturn(Optional.of(mapping));
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlStatsResponse result = service.reactivate("old1234", TENANT_ID);

        assertThat(result.active()).isTrue();
        // A miss is never cached in the first place (CachedShortCodeLookup's
        // `unless` clause) — nothing stale to evict for a code that was
        // inactive a moment ago.
        verifyNoInteractions(cachedShortCodeLookup);
    }

    @Test
    void reactivate_alreadyActiveLink_isIdempotent() {
        UrlMapping mapping = UrlMapping.builder().id(1L).tenantId(TENANT_ID).shortCode("abc1234").originalUrl("https://example.com").active(true).build();
        when(repository.findByShortCodeAndTenantId("abc1234", TENANT_ID)).thenReturn(Optional.of(mapping));
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlStatsResponse result = service.reactivate("abc1234", TENANT_ID);

        assertThat(result.active()).isTrue();
    }

    @Test
    void reactivate_unknownOrNotOwnedCode_throwsNotFound() {
        when(repository.findByShortCodeAndTenantId("missing", TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reactivate("missing", TENANT_ID))
                .isInstanceOf(UrlNotFoundException.class);
    }
}
