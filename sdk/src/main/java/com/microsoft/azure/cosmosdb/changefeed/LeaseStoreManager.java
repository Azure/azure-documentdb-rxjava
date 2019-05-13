/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.microsoft.azure.cosmosdb.changefeed;

import com.microsoft.azure.cosmosdb.changefeed.internal.LeaseStoreManagerImpl;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Defines an interface for operations with {@link Lease}.
 */
public interface LeaseStoreManager extends LeaseContainer, LeaseManager, LeaseStore, LeaseCheckpointer
{
    /**
     * Provides flexible way to build lease manager constructor parameters.
     * For the actual creation of lease manager instance, delegates to lease manager factory.
     */
    interface LeaseStoreManagerBuilderDefinition {
        LeaseStoreManagerBuilderDefinition withLeaseCollection(ContainerInfo leaseCollectionLocation);

        LeaseStoreManagerBuilderDefinition withLeaseDocumentClient(ChangeFeedContextClient leaseDocumentClient);

        LeaseStoreManagerBuilderDefinition withLeasePrefix(String leasePrefix);

        LeaseStoreManagerBuilderDefinition withLeaseCollectionLink(String leaseCollectionLink);

        LeaseStoreManagerBuilderDefinition withRequestOptionsFactory(RequestOptionsFactory requestOptionsFactory);

        LeaseStoreManagerBuilderDefinition withHostName(String hostName);

        LeaseStoreManager build();

        Flux<LeaseStoreManager> buildAsync();
    }

    static LeaseStoreManagerBuilderDefinition Builder() {
        return new LeaseStoreManagerImpl();
    }

    /**
     * @return List of all leases.
     */
    Flux<Lease> getAllLeasesAsync();

    /**
     * @return all leases owned by the current host.
     */
    Flux<Lease> getOwnedLeasesAsync();

    /**
     * Checks whether the lease exists and creates it if it does not exist.
     *
     * @param leaseToken the partition to work on.
     * @param continuationToken the continuation token if it exists.
     * @return the lease.
     */
    Flux<Lease> createLeaseIfNotExistAsync(String leaseToken, String continuationToken);

    /**
     * Delete the lease.
     *
     * @param lease the lease to remove.
     * @return a representation of the deferred computation of this call.
     */
    Mono<Void> deleteAsync(Lease lease);

    /**
     * Acquire ownership of the lease.
     *
     * @param lease the Lease to acquire.
     * @return the updated acquired lease.
     */
    Flux<Lease> acquireAsync(Lease lease);

    /**
     * Release ownership of the lease.
     *
     * @param lease the lease to acquire.
     * @return a representation of the deferred computation of this call.
     */
    Mono<Void> releaseAsync(Lease lease);

    /**
     * Renew the lease. Leases are periodically renewed to prevent expiration.
     *
     * @param lease the Lease to renew.
     * @return the updated renewed lease.
     */
    Flux<Lease> renewAsync(Lease lease);

    /**
     * Replace properties from the specified lease.
     *
     * @param leaseToUpdatePropertiesFrom the Lease containing new properties.
     * @return the updated lease.
     */
    Flux<Lease> updatePropertiesAsync(Lease leaseToUpdatePropertiesFrom);

    /**
     * Checkpoint the lease.
     *
     * @param lease the Lease to renew.
     * @param continuationToken the continuation token.
     * @return the updated renewed lease.
     */
    Flux<Lease> checkpointAsync(Lease lease, String continuationToken);

    /**
     * @return true if the lease store is initialized.
     */
    Mono<Boolean> isInitializedAsync();

    /**
     * Mark the store as initialized.
     *
     * @return a representation of the deferred computation of this call.
     */
    Mono<Void> markInitializedAsync();

    /**
     * Places a lock on the lease store for initialization. Only one process may own the store for the lock time.
     *
     * @param lockExpirationTime the time for the lock to expire.
     * @return true if the lock was acquired, false otherwise.
     */
    Mono<Boolean> acquireInitializationLockAsync(Duration lockExpirationTime);

    /**
     * Releases the lock one the lease store for initialization.
     *
     * @return true if the lock was acquired and was relesed, false if the lock was not acquired.
     */
    Mono<Boolean> releaseInitializationLockAsync();
}
