package fi.karivanttinen.fplassistant.sync;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import org.springframework.core.env.*;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.*;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.*;

public class OwnedMemoryProbe {
    static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026,8,1), LocalDate.of(2027,5,31));
    public static void main(String[] args) throws Exception {
        String url=System.getenv("DATABASE_URL");
        if (!url.startsWith("jdbc:postgresql://localhost:55436/") && !url.startsWith("jdbc:postgresql://127.0.0.1:55436/")) throw new IllegalStateException("Disposable probe DB only");
        var env=new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("probe",Map.of(
            "spring.datasource.url",url,"spring.datasource.username",System.getenv("DB_USER"),
            "spring.datasource.password",System.getenv("DB_PASSWORD"))));
        try(var db=OwnedSyncMonitoringDatabase.work(env)) {
            var tables=OwnedSyncTables.tests();
            new OwnedSyncSchema(db.jdbc(),tables).initialize();
            var clock=Clock.fixed(Instant.parse("2026-09-06T17:00:00Z"),ZoneOffset.UTC);
            var store=new OwnedSyncStore(db.jdbc(),db.transaction(),clock,tables);
            byte[] bootstrap=Files.readAllBytes(Path.of("backend/src/test/resources/owned-sync-source-fixtures/bootstrap-static.json"));
            byte[] fixtures=Files.readAllBytes(Path.of("backend/src/test/resources/owned-sync-source-fixtures/fixtures.json"));
            var read=new SourceRead(clock.instant(),clock.instant());
            for(int i=0;i<6;i++) {
                store.enqueue(SyncKind.CATALOGUE_SCHEDULE,SEASON.code(),"probe:"+i,"probe:"+i,clock.instant());
                var lease=store.acquire("probe",Duration.ofMinutes(5)).orElseThrow();
                store.stage(lease,Endpoint.bootstrap(),read,Validation.valid(),bootstrap);
                store.stage(lease,Endpoint.fixtures(),read,Validation.valid(),fixtures);
                store.completeStaged(lease,read,List.of(read));
            }
            System.out.printf("Six canonical pairs, raw body MiB=%.2f%n",6.0*(bootstrap.length+fixtures.length)/1048576);
            measure(store,20,12, false);
            measure(store,20,12, true);
            for(int i=6;i<20;i++) {
                store.enqueue(SyncKind.CATALOGUE_SCHEDULE,SEASON.code(),"probe:"+i,"probe:"+i,clock.instant());
                var lease=store.acquire("probe",Duration.ofMinutes(5)).orElseThrow();
                store.stage(lease,Endpoint.bootstrap(),read,Validation.valid(),bootstrap);
                store.stage(lease,Endpoint.fixtures(),read,Validation.valid(),fixtures);
                store.completeStaged(lease,read,List.of(read));
            }
            System.out.println("Twenty pairs, 300-selection bounded soak:");
            measure(store,20,300, false);
        }
    }
    static SourceDocument document(StagedPayload p) {
        return new SourceDocument(p.body(),new ReadInterval(p.read().startedAt(),p.read().finishedAt()));
    }
    static void measure(OwnedSyncStore store,int limit,int iterations, boolean rejectStale) throws Exception {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        System.gc(); Thread.sleep(100);
        long before=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start=System.nanoTime();
        for(int i=0;i<iterations;i++) {
            var selected=store.findInLatestValidPublicPayloadPairs(SEASON.code(),limit,
                rejectStale ? Instant.parse("2026-09-06T17:01:00Z") : null, p ->
                    Optional.of(CatalogueScheduleParser.parse(SEASON,document(p.bootstrap()),document(p.fixtures()))));
            if(rejectStale ? selected.isPresent() : selected.orElseThrow().fixtures().size()!=380)
                throw new AssertionError("Unexpected selection");
        }
        long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-before;
        System.gc();Thread.sleep(100);
        System.out.printf("limit=%d iterations=%d rejectStale=%s allocation/call=%.2f MiB elapsed=%.2fs retainedHeap=%.2f MiB%n",limit,iterations,rejectStale,
            allocated/1048576.0/iterations,(System.nanoTime()-start)/1e9,
            (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576.0);
    }
}
