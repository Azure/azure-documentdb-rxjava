// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.azure.data.cosmos.internal.changefeed.implementation;

import com.azure.data.cosmos.internal.changefeed.CancellationToken;
import com.azure.data.cosmos.internal.changefeed.CancellationTokenSource;
import com.azure.data.cosmos.internal.changefeed.Lease;
import com.azure.data.cosmos.internal.changefeed.LeaseContainer;
import com.azure.data.cosmos.internal.changefeed.PartitionController;
import com.azure.data.cosmos.internal.changefeed.PartitionLoadBalancer;
import com.azure.data.cosmos.internal.changefeed.PartitionLoadBalancingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Implementation for {@link PartitionLoadBalancer}.
 */
class PartitionLoadBalancerImpl implements PartitionLoadBalancer {
    private final Logger logger = LoggerFactory.getLogger(PartitionLoadBalancerImpl.class);
    private final PartitionController partitionController;
    private final LeaseContainer leaseContainer;
    private final PartitionLoadBalancingStrategy partitionLoadBalancingStrategy;
    private final Duration leaseAcquireInterval;
    private final Scheduler scheduler;

    private CancellationTokenSource cancellationTokenSource;

    private volatile boolean started;

    private final Object lock;

    public PartitionLoadBalancerImpl(
            PartitionController partitionController,
            LeaseContainer leaseContainer,
            PartitionLoadBalancingStrategy partitionLoadBalancingStrategy,
            Duration leaseAcquireInterval,
            Scheduler scheduler) {

        if (partitionController == null) {
            throw new IllegalArgumentException("partitionController");
        }

        if (leaseContainer == null) {
            throw new IllegalArgumentException("leaseContainer");
        }

        if (partitionLoadBalancingStrategy == null) {
            throw new IllegalArgumentException("partitionLoadBalancingStrategy");
        }

        if (scheduler == null) {
            throw new IllegalArgumentException("executorService");
        }

        this.partitionController = partitionController;
        this.leaseContainer = leaseContainer;
        this.partitionLoadBalancingStrategy = partitionLoadBalancingStrategy;
        this.leaseAcquireInterval = leaseAcquireInterval;
        this.scheduler = scheduler;

        this.started = false;
        this.lock = new Object();
    }

    @Override
    public Mono<Void> start() {
        synchronized (lock) {
            if (this.started) {
                throw new IllegalStateException("Partition load balancer already started");
            }

            this.started = true;
            this.cancellationTokenSource = new CancellationTokenSource();
        }

        return Mono.fromRunnable( () -> {
            scheduler.schedule(() -> this.run(this.cancellationTokenSource.getToken()).subscribe());
        });
    }

    @Override
    public Mono<Void> stop() {
        synchronized (lock) {
            this.started = false;
            this.cancellationTokenSource.cancel();
        }

        return this.partitionController.shutdown();
    }

    private Mono<Void> run(CancellationToken cancellationToken) {
        return Flux.just(this)
            .flatMap(value -> this.leaseContainer.getAllLeases())
            .collectList()
            .flatMap(allLeases -> {
                if (cancellationToken.isCancellationRequested()) return Mono.empty();
                List<Lease> leasesToTake = this.partitionLoadBalancingStrategy.selectLeasesToTake(allLeases);

                if (cancellationToken.isCancellationRequested()) return Mono.empty();
                return Flux.fromIterable(leasesToTake)
                    .flatMap(lease -> {
                        if (cancellationToken.isCancellationRequested()) return Mono.empty();
                        return this.partitionController.addOrUpdateLease(lease);
                    })
                    .then(Mono.just(this)
                        .flatMap(value -> {
                            if (cancellationToken.isCancellationRequested()) {
                                return Mono.empty();
                            }

                            ZonedDateTime stopTimer = ZonedDateTime.now().plus(this.leaseAcquireInterval);
                            return Mono.just(value)
                                .delayElement(Duration.ofMillis(100))
                                .repeat( () -> {
                                    ZonedDateTime currentTime = ZonedDateTime.now();
                                    return !cancellationToken.isCancellationRequested() && currentTime.isBefore(stopTimer);
                                }).last();
                        })
                    );
            })
            .repeat(() -> {
                return !cancellationToken.isCancellationRequested();
//                if (cancellationToken.isCancellationRequested()) return false;
//
//                long remainingWork = this.leaseAcquireInterval.toMillis();
//
//                try {
//                    while (!cancellationToken.isCancellationRequested() && remainingWork > 0) {
//                        Thread.sleep(100);
//                        remainingWork -= 100;
//                    }
//                } catch (InterruptedException ex) {
//                    // exception caught
//                    logger.warn("Partition load balancer caught an interrupted exception", ex);
//                }
//                if (cancellationToken.isCancellationRequested()) return false;
//
//                return true;
            })
            .then()
            .onErrorResume(throwable -> {
                // We should not get here.
                logger.info("Partition load balancer task stopped.");
                return this.stop();
            });
    }
}
