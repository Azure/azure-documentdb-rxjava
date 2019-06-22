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
package com.azure.data.cosmos.internal.query;

import com.azure.data.cosmos.CosmosClientException;
import com.azure.data.cosmos.FeedOptions;
import com.azure.data.cosmos.FeedResponse;
import com.azure.data.cosmos.PartitionKeyRange;
import com.azure.data.cosmos.Resource;
import com.azure.data.cosmos.SqlQuerySpec;
import com.azure.data.cosmos.internal.HttpConstants;
import com.azure.data.cosmos.internal.IDocumentClientRetryPolicy;
import com.azure.data.cosmos.internal.ResourceType;
import com.azure.data.cosmos.internal.RxDocumentServiceRequest;
import com.azure.data.cosmos.internal.Strings;
import com.azure.data.cosmos.internal.routing.Range;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * While this class is public, but it is not part of our published public APIs.
 * This is meant to be internally used only by our sdk.
 */
public abstract class ParallelDocumentQueryExecutionContextBase<T extends Resource>
        extends DocumentQueryExecutionContextBase<T> implements IDocumentQueryExecutionComponent<T> {

    protected final List<DocumentProducer<T>> documentProducers;
    protected final List<PartitionKeyRange> partitionKeyRanges;
    protected final SqlQuerySpec querySpec;
    protected int pageSize;
    protected int top = -1;

    protected ParallelDocumentQueryExecutionContextBase(IDocumentQueryClient client,
            List<PartitionKeyRange> partitionKeyRanges, ResourceType resourceTypeEnum, Class<T> resourceType,
            SqlQuerySpec query, FeedOptions feedOptions, String resourceLink, String rewrittenQuery,
            boolean isContinuationExpected, boolean getLazyFeedResponse, UUID correlatedActivityId) {
        super(client, resourceTypeEnum, resourceType, query, feedOptions, resourceLink, getLazyFeedResponse,
                correlatedActivityId);

        documentProducers = new ArrayList<>();

        this.partitionKeyRanges = partitionKeyRanges;

        if (!Strings.isNullOrEmpty(rewrittenQuery)) {
            this.querySpec = new SqlQuerySpec(rewrittenQuery, super.query.parameters());
        } else {
            this.querySpec = super.query;
        }
    }

    protected void initialize(String collectionRid,
            Map<PartitionKeyRange, String> partitionKeyRangeToContinuationTokenMap, int initialPageSize,
            SqlQuerySpec querySpecForInit) {
        this.pageSize = initialPageSize;
        Map<String, String> commonRequestHeaders = createCommonHeadersAsync(this.getFeedOptions(null, null));

        for (PartitionKeyRange targetRange : partitionKeyRangeToContinuationTokenMap.keySet()) {
            TriFunction<PartitionKeyRange, String, Integer, RxDocumentServiceRequest> createRequestFunc = (partitionKeyRange,
                                                                                                     continuationToken, pageSize) -> {
                Map<String, String> headers = new HashMap<>(commonRequestHeaders);
                headers.put(HttpConstants.HttpHeaders.CONTINUATION, continuationToken);
                headers.put(HttpConstants.HttpHeaders.PAGE_SIZE, Strings.toString(pageSize));
                return this.createDocumentServiceRequest(headers, querySpecForInit, partitionKeyRange, collectionRid);
            };

            Function<RxDocumentServiceRequest, Flux<FeedResponse<T>>> executeFunc = (request) -> {
                return this.executeRequestAsync(request).flux();
            };

            DocumentProducer<T> dp = createDocumentProducer(collectionRid, targetRange,
                    partitionKeyRangeToContinuationTokenMap.get(targetRange), initialPageSize, feedOptions,
                    querySpecForInit, commonRequestHeaders, createRequestFunc, executeFunc,
                    () -> client.getResetSessionTokenRetryPolicy().getRequestPolicy());

            documentProducers.add(dp);
        }
    }

    protected <TContinuationToken> int FindTargetRangeAndExtractContinuationTokens(
            List<PartitionKeyRange> partitionKeyRanges, Range<String> range) throws CosmosClientException {
        if (partitionKeyRanges == null) {
            throw new IllegalArgumentException("partitionKeyRanges can not be null.");
        }

        if (partitionKeyRanges.size() < 1) {
            throw new IllegalArgumentException("partitionKeyRanges must have atleast one element.");
        }

        for (PartitionKeyRange partitionKeyRange : partitionKeyRanges) {
            if (partitionKeyRange == null) {
                throw new IllegalArgumentException("partitionKeyRanges can not have null elements.");
            }
        }

        // Find the minimum index.
        PartitionKeyRange needle = new PartitionKeyRange(/* id */ null, range.getMin(), range.getMax());
        int minIndex;
        for (minIndex = 0; minIndex < partitionKeyRanges.size(); minIndex++) {
            if (needle.getMinInclusive().equals(partitionKeyRanges.get(minIndex).getMinInclusive())) {
                break;
            }
        }

        if (minIndex == partitionKeyRanges.size()) {
            throw new CosmosClientException(HttpConstants.StatusCodes.BADREQUEST,
                    String.format("Could not find partition key range for continuation token: {0}", needle));
        }

        return minIndex;
    }

    abstract protected DocumentProducer<T> createDocumentProducer(String collectionRid, PartitionKeyRange targetRange,
            String initialContinuationToken, int initialPageSize, FeedOptions feedOptions, SqlQuerySpec querySpecForInit,
            Map<String, String> commonRequestHeaders,
            TriFunction<PartitionKeyRange, String, Integer, RxDocumentServiceRequest> createRequestFunc,
            Function<RxDocumentServiceRequest, Flux<FeedResponse<T>>> executeFunc,
            Callable<IDocumentClientRetryPolicy> createRetryPolicyFunc);

    @Override
    abstract public Flux<FeedResponse<T>> drainAsync(int maxPageSize);

    public void setTop(int newTop) {
        this.top = newTop;

        for (DocumentProducer<T> producer : this.documentProducers) {
            producer.top = newTop;
        }
    }
}
