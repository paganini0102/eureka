package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 应用实例信息复制器
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;
    /** 应用实例信息 */
    private final InstanceInfo instanceInfo;
    /** 定时执行频率（单位：秒） */
    private final int replicationIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    /** 定时执行任务的Future */
    private final AtomicReference<Future> scheduledPeriodicRef;
    /** 是否开启调度 */
    private final AtomicBoolean started;
    /** 限流相关 */
    private final RateLimiter rateLimiter;
    private final int burstSize;
    private final int allowedRatePerMinute;

    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;

        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {
            // 设置应用实例信息数据不一致
            instanceInfo.setIsDirty();  // for initial register
            // 提交任务，并设置该任务的Future
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        scheduler.shutdownNow();
        started.set(false);
    }

    public boolean onDemandUpdate() {
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) { // 限流判断
            scheduler.submit(new Runnable() {
                @Override
                public void run() {
                    logger.debug("Executing on-demand update of local InstanceInfo");

                    Future latestPeriodic = scheduledPeriodicRef.get(); // 取消任务
                    if (latestPeriodic != null && !latestPeriodic.isDone()) {
                        logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                        latestPeriodic.cancel(false);
                    }

                    InstanceInfoReplicator.this.run(); // 再次调用
                }
            });
            return true;
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    public void run() {
        try {
            discoveryClient.refreshInstanceInfo(); // 刷新应用实例信息

            Long dirtyTimestamp = instanceInfo.isDirtyWithTime(); // 判断应用实例信息是否数据不一致
            if (dirtyTimestamp != null) {
                discoveryClient.register(); // 发起注册
                instanceInfo.unsetIsDirty(dirtyTimestamp); // 设置应用实例信息数据一致
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);  // 提交任务，并设置该任务的Future
            scheduledPeriodicRef.set(next);
        }
    }

}