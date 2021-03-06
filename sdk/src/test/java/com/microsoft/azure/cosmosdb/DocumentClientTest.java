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

package com.microsoft.azure.cosmosdb;

import com.google.common.base.Strings;
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITest;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import java.lang.reflect.Method;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class DocumentClientTest implements ITest {

    protected static final Logger logger = LoggerFactory.getLogger(DocumentClientTest.class.getSimpleName());
    private final AsyncDocumentClient.Builder clientBuilder;
    private String testName;

    public DocumentClientTest() {
         this(new AsyncDocumentClient.Builder());
    }

    public DocumentClientTest(AsyncDocumentClient.Builder clientBuilder) {
        checkNotNull(clientBuilder, "clientBuilder: null");
        this.clientBuilder = clientBuilder;
    }

    public final AsyncDocumentClient.Builder clientBuilder() {
        return this.clientBuilder;
    }

    @Override
    public final String getTestName() {
        return this.testName;
    }

    @BeforeMethod(alwaysRun = true)
    public final void setTestName(Method method) {

        final ConnectionPolicy connectionPolicy = this.clientBuilder.getConnectionPolicy();
        final String connectionMode;

        if (connectionPolicy == null) {
            connectionMode = "None";
        } else {
            connectionMode = connectionPolicy.getConnectionMode() == ConnectionMode.Direct
                ? "Direct " + this.clientBuilder.getConfigs().getProtocol().name().toUpperCase()
                : "Gateway";
        }

        this.testName = Strings.lenientFormat("%s::%s[%s with %s consistency]",
            method.getDeclaringClass().getSimpleName(),
            method.getName(),
            connectionMode,
            clientBuilder.getDesiredConsistencyLevel());

        logger.info("Starting {}", this.testName);
    }

    @AfterMethod(alwaysRun = true)
    public final void unsetTestName() {
        logger.info("Finished {}", this.testName);
        this.testName = null;
    }
}
