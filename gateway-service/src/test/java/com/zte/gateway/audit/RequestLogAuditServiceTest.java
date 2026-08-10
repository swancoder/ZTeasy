package com.zte.gateway.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RequestLogAuditService}.
 *
 * <p>Persistence happens on a background subscriber ({@code Schedulers.boundedElastic()}),
 * so assertions use Mockito's {@code timeout(...)} verification mode rather
 * than asserting immediately after {@link RequestLogAuditService#record}.
 */
@ExtendWith(MockitoExtension.class)
class RequestLogAuditServiceTest {

    @Mock RequestLogRepository repository;

    private RequestLog sampleEntry() {
        return RequestLog.of("trace-1", "127.0.0.1", "curl/8.0", "1234",
                "/api/v1/service-a/hello", 200, null);
    }

    @Test
    void record_persistsViaRepository() {
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        RequestLogAuditService service = new RequestLogAuditService(repository);

        service.record(sampleEntry());

        verify(repository, timeout(2000)).save(any(RequestLog.class));
    }

    @Test
    void record_dbWriteFails_fallsBackToSlf4jWithoutThrowing() {
        when(repository.save(any())).thenReturn(Mono.error(new RuntimeException("connection refused")));
        RequestLogAuditService service = new RequestLogAuditService(repository);

        service.record(sampleEntry());

        // The failure is swallowed (onErrorResume -> SLF4J fallback) — the async
        // stream must keep running, not terminate. Prove it by recording a second
        // entry and confirming save() is still invoked for it too.
        verify(repository, timeout(2000)).save(any(RequestLog.class));
        service.record(sampleEntry());
        verify(repository, timeout(2000).times(2)).save(any(RequestLog.class));
    }
}
