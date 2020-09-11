/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.azure.cosmosdb.rx.internal;

import com.microsoft.azure.cosmosdb.ConnectionPolicy;
import com.microsoft.azure.cosmosdb.Document;
import com.microsoft.azure.cosmosdb.FeedOptions;
import com.microsoft.azure.cosmosdb.FeedResponse;
import com.microsoft.azure.cosmosdb.PartitionKey;
import com.microsoft.azure.cosmosdb.RequestOptions;
import com.microsoft.azure.cosmosdb.ResourceResponse;
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient;
import com.microsoft.azure.cosmosdb.rx.FailureValidator;
import com.microsoft.azure.cosmosdb.rx.FeedResponseListValidator;
import com.microsoft.azure.cosmosdb.rx.FeedResponseValidator;
import com.microsoft.azure.cosmosdb.rx.ResourceResponseValidator;
import com.microsoft.azure.cosmosdb.rx.TestConfigurations;
import com.microsoft.azure.cosmosdb.rx.TestSuiteBase;
import io.netty.buffer.ByteBuf;
import io.reactivex.netty.protocol.http.client.CompositeHttpClient;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.testng.annotations.Test;
import rx.Observable;

import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.fail;

public class HttpRequestTimeoutTest extends TestSuiteBase {


	@Test(groups = {"emulator"}, timeOut = TIMEOUT)
	public void clientInitialization() {
		AsyncDocumentClient client = null;
		try {
			ConnectionPolicy connectionPolicy = new ConnectionPolicy();
			connectionPolicy.setRequestTimeoutInMillis(1); //Very low time, forcing it to fail
			client = new AsyncDocumentClient.Builder()
			.withConnectionPolicy(connectionPolicy)
			.withMasterKeyOrResourceToken(TestConfigurations.MASTER_KEY)
			.withServiceEndpoint(TestConfigurations.HOST)
			.build();
		} catch (Exception ex) {
			fail("Client initialization should not fail due to low requestTimeout on connection policy " + ex.getMessage());
		} finally {
			if (client != null) {
				client.close();
			}
		}
	}

	@Test(groups = {"emulator"}, timeOut = TIMEOUT)
	public void DocumentWrite() {
		AsyncDocumentClient client = null;
		try {
			ConnectionPolicy connectionPolicy = new ConnectionPolicy();
			client = new AsyncDocumentClient.Builder()
			.withConnectionPolicy(connectionPolicy)
			.withMasterKeyOrResourceToken(TestConfigurations.MASTER_KEY)
			.withServiceEndpoint(TestConfigurations.HOST)
			.build();

			RetryPolicy retryPolicy = (RetryPolicy) FieldUtils.readField(client, "retryPolicy", true);
			GlobalEndpointManager globalEndpointManager = (GlobalEndpointManager) FieldUtils.readField(retryPolicy, "globalEndpointManager", true);
			GlobalEndpointManager spyGlobalEndpointManager = Mockito.spy(globalEndpointManager);
			FieldUtils.writeField(retryPolicy, "globalEndpointManager", spyGlobalEndpointManager, true);

			HttpClientFactory httpClientFactory = new HttpClientFactory(new Configs());
			httpClientFactory.withRequestTimeoutInMillis(1);
			CompositeHttpClient<ByteBuf, ByteBuf> httpClient = httpClientFactory.toHttpClientBuilder().build();

			//  Scenario after restricting http readTimeout i.e.verifying success and no region fail over as httpClientFactory.withRequestTimeoutInMillis(1)
			// should not lower the timeout below 60 sec
			RxGatewayStoreModel storeModel = (RxGatewayStoreModel) FieldUtils.readField(client, "gatewayProxy", true);
			FieldUtils.writeField(storeModel, "httpClient", httpClient, true);
			Document successDocument = new Document();
			successDocument.setId(UUID.randomUUID().toString());
			successDocument.set("mypk", successDocument.getId());
			Observable<ResourceResponse<Document>> readObservable = client.createDocument(SHARED_MULTI_PARTITION_COLLECTION.getSelfLink(), successDocument, new RequestOptions(), true);
			ResourceResponseValidator<Document> successValidator = new ResourceResponseValidator.Builder<Document>()
			.withId(successDocument.getId())
			.build();
			validateSuccess(readObservable, successValidator);
			Mockito.verify(spyGlobalEndpointManager, Mockito.times(0)).markEndpointUnavailableForWrite(Matchers.any(URL.class));

			// Scenario before restricting http readTimeout i.e. verifying failure by updating requestTimeoutInMillis via reflection
			// we should see the request failure and region fail over
			httpClientFactory.withRequestTimeoutInMillis(1);
			FieldUtils.writeField(httpClientFactory, "requestTimeoutInMillis", 1, true);
			httpClient = httpClientFactory.toHttpClientBuilder().build();
			FieldUtils.writeField(storeModel, "httpClient", httpClient, true);
			Document failureDocument = new Document();
			failureDocument.setId(UUID.randomUUID().toString());
			failureDocument.set("mypk", successDocument.getId());
			readObservable = client.createDocument(SHARED_MULTI_PARTITION_COLLECTION.getSelfLink(), failureDocument, new RequestOptions(), true);
			FailureValidator failureValidator = new FailureValidator.Builder().causeInstanceOf(ClosedChannelException.class).build();
			validateFailure(readObservable, failureValidator);
			Mockito.verify(spyGlobalEndpointManager, Mockito.times(1)).markEndpointUnavailableForWrite(Matchers.any(URL.class));
		} catch (Exception ex) {
			fail("Should not throw exception in the test" + ex.getMessage());
		} finally {
			if (client != null) {
				client.close();
			}
		}
	}

	@Test(groups = {"emulator"}, timeOut = TIMEOUT)
	public void DocumentRead() {
		AsyncDocumentClient client = null;
		try {
			ConnectionPolicy connectionPolicy = new ConnectionPolicy();
			client = new AsyncDocumentClient.Builder()
			.withConnectionPolicy(connectionPolicy)
			.withMasterKeyOrResourceToken(TestConfigurations.MASTER_KEY)
			.withServiceEndpoint(TestConfigurations.HOST)
			.build();

			//Creating document for read
			Document document = new Document();
			document.setId(UUID.randomUUID().toString());
			document.set("mypk", document.getId());
			RequestOptions options = new RequestOptions();
			options.setPartitionKey(new PartitionKey(document.getId()));
			document = client.createDocument(SHARED_MULTI_PARTITION_COLLECTION.getSelfLink(), document, options, true)
			.toBlocking()
			.first()
			.getResource();

			RetryPolicy retryPolicy = (RetryPolicy) FieldUtils.readField(client, "retryPolicy", true);
			GlobalEndpointManager globalEndpointManager = (GlobalEndpointManager) FieldUtils.readField(retryPolicy, "globalEndpointManager", true);
			GlobalEndpointManager spyGlobalEndpointManager = Mockito.spy(globalEndpointManager);
			FieldUtils.writeField(retryPolicy, "globalEndpointManager", spyGlobalEndpointManager, true);

			HttpClientFactory httpClientFactory = new HttpClientFactory(new Configs());
			httpClientFactory.withRequestTimeoutInMillis(1);
			CompositeHttpClient<ByteBuf, ByteBuf> httpClient = httpClientFactory.toHttpClientBuilder().build();

			// Scenario after restricting http readTimeout i.e.verifying success and no region fail over as httpClientFactory.withRequestTimeoutInMillis(1)
			// should not lower the timeout below 60 sec
			RxGatewayStoreModel storeModel = (RxGatewayStoreModel) FieldUtils.readField(client, "gatewayProxy", true);
			FieldUtils.writeField(storeModel, "httpClient", httpClient, true);
			Observable<ResourceResponse<Document>> readObservable = client.readDocument(document.getSelfLink(), options);
			ResourceResponseValidator<Document> successValidator = new ResourceResponseValidator.Builder<Document>()
			.withId(document.getId())
			.build();
			validateSuccess(readObservable, successValidator);
			Mockito.verify(spyGlobalEndpointManager, Mockito.times(0)).markEndpointUnavailableForRead(Matchers.any(URL.class));

			// Scenario before restricting http readTimeout i.e. verifying failure by updating requestTimeoutInMillis via reflection
			// we should see the request failure and region fail over
			httpClientFactory.withRequestTimeoutInMillis(1);
			FieldUtils.writeField(httpClientFactory, "requestTimeoutInMillis", 1, true);
			httpClient = httpClientFactory.toHttpClientBuilder().build();
			FieldUtils.writeField(storeModel, "httpClient", httpClient, true);
			readObservable = readObservable = client.readDocument(document.getSelfLink(), options);
			FailureValidator failureValidator = new FailureValidator.Builder().causeInstanceOf(ClosedChannelException.class).build();
			validateFailure(readObservable, failureValidator);
			Mockito.verify(spyGlobalEndpointManager, Mockito.times(1)).markEndpointUnavailableForRead(Matchers.any(URL.class));
		} catch (Exception ex) {
			fail("Should not throw exception in the test" + ex.getMessage());
		} finally {
			if (client != null) {
				client.close();
			}
		}
	}

	@Test(groups = {"emulator"}, timeOut = TIMEOUT)
	public void DocumentQuery() {
		AsyncDocumentClient client = null;
		try {
			ConnectionPolicy connectionPolicy = new ConnectionPolicy();
			client = new AsyncDocumentClient.Builder()
			.withConnectionPolicy(connectionPolicy)
			.withMasterKeyOrResourceToken(TestConfigurations.MASTER_KEY)
			.withServiceEndpoint(TestConfigurations.HOST)
			.build();

			//Creating document for read
			Document document = new Document();
			document.setId(UUID.randomUUID().toString());
			document.set("mypk", document.getId());
			RequestOptions options = new RequestOptions();
			options.setPartitionKey(new PartitionKey(document.getId()));
			document = client.createDocument(SHARED_MULTI_PARTITION_COLLECTION.getSelfLink(), document, options, true)
			.toBlocking()
			.first()
			.getResource();

			RetryPolicy retryPolicy = (RetryPolicy) FieldUtils.readField(client, "retryPolicy", true);
			GlobalEndpointManager globalEndpointManager = (GlobalEndpointManager) FieldUtils.readField(retryPolicy, "globalEndpointManager", true);
			GlobalEndpointManager spyGlobalEndpointManager = Mockito.spy(globalEndpointManager);
			FieldUtils.writeField(retryPolicy, "globalEndpointManager", spyGlobalEndpointManager, true);

			HttpClientFactory httpClientFactory = new HttpClientFactory(new Configs());
			httpClientFactory.withRequestTimeoutInMillis(1);
			CompositeHttpClient<ByteBuf, ByteBuf> httpClient = httpClientFactory.toHttpClientBuilder().build();

			// Scenario after restricting http readTimeout i.e.verifying success and no region fail over as httpClientFactory.withRequestTimeoutInMillis(1)
			// should not lower the timeout below 60 sec
			RxGatewayStoreModel storeModel = (RxGatewayStoreModel) FieldUtils.readField(client, "gatewayProxy", true);
			FieldUtils.writeField(storeModel, "httpClient", httpClient, true);
			FeedOptions feedOptions = new FeedOptions();
			feedOptions.setPartitionKey(new PartitionKey(document.getId()));
			Observable<FeedResponse<Document>> queryObservable = client.queryDocuments(SHARED_MULTI_PARTITION_COLLECTION.getSelfLink(), "Select * from C", feedOptions);
			FeedResponseListValidator<Document> validator = new FeedResponseListValidator.Builder<Document>()
			.totalSize(1)
			.numberOfPages(1)
			.pageSatisfy(0, new FeedResponseValidator.Builder<Document>()
			.requestChargeGreaterThanOrEqualTo(1.0).build())
			.build();
			validateQuerySuccess(queryObservable, validator, FEED_TIMEOUT);
			Mockito.verify(spyGlobalEndpointManager, Mockito.times(0)).markEndpointUnavailableForWrite(Matchers.any(URL.class));

			// Scenario before restricting http readTimeout i.e. verifying failure by updating requestTimeoutInMillis via reflection
			// we should see the request failure and region fail over
			httpClientFactory.withRequestTimeoutInMillis(1);
			FieldUtils.writeField(httpClientFactory, "requestTimeoutInMillis", 1, true);
			httpClient = httpClientFactory.toHttpClientBuilder().build();
			FieldUtils.writeField(storeModel, "httpClient", httpClient, true);
			queryObservable = client.queryDocuments(SHARED_MULTI_PARTITION_COLLECTION.getSelfLink(), "Select * from C", feedOptions);
			FailureValidator failureValidator = new FailureValidator.Builder().causeInstanceOf(ClosedChannelException.class).build();
			validateQueryFailure(queryObservable, failureValidator);
			// TODO https://github.com/Azure/azure-cosmosdb-java/issues/359
			// After above fix, below check should be on markEndpointUnavailableForRead
			Mockito.verify(spyGlobalEndpointManager, Mockito.times(0)).markEndpointUnavailableForWrite(Matchers.any(URL.class));
		} catch (Exception ex) {
			fail("Should not throw exception in the test" + ex.getMessage());
		} finally {
			if (client != null) {
				client.close();
			}
		}
	}
}
