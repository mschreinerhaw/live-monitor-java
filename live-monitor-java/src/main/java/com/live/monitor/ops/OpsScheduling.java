package com.live.monitor.ops;

import java.util.concurrent.*;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Container jobs and SSE must never occupy the original monitor's scheduler threads. */
@Component
public class OpsScheduling {
    private final OpsTaskService tasks;private final HealthDetectionService health;
    private final long interval,grace;
    private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(4,r->{Thread t=new Thread(r,"container-ops-scheduler");t.setDaemon(true);return t;});
    public OpsScheduling(OpsTaskService tasks,HealthDetectionService health,
        @Value("${live-monitor.ops.detection-interval-ms:15000}") long interval,
        @Value("${live-monitor.ops.startup-grace-ms:60000}") long grace){this.tasks=tasks;this.health=health;this.interval=Math.max(1000,interval);this.grace=Math.max(60000,grace);}
    private Runnable guarded(Runnable work){return ()->{try{work.run();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(OpsScheduling.class).warn("Container operations scheduling failed; legacy monitoring remains isolated: {}",e.getClass().getSimpleName());}};}
    @PostConstruct public void start(){
        scheduler.scheduleWithFixedDelay(guarded(tasks::dispatch),1000,1000,TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guarded(tasks::publishEvents),1000,1000,TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guarded(tasks::markInterrupted),60000,60000,TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guarded(health::tick),grace,interval,TimeUnit.MILLISECONDS);
    }
    @PreDestroy public void stop(){scheduler.shutdownNow();}
}
