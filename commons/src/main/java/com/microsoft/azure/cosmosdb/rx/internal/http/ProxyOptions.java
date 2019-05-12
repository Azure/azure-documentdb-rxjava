// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.azure.cosmosdb.rx.internal.http;

import reactor.netty.tcp.ProxyProvider.Proxy;

import java.net.InetSocketAddress;

/**
 * proxy configuration.
 */
public class ProxyOptions {
    private final InetSocketAddress address;
    private final Type type;

    /**
     * Creates ProxyOptions.
     *
     * @param type the proxy type
     * @param address the proxy address (ip and port number)
     */
    public ProxyOptions(Type type, InetSocketAddress address) {
        this.type = type;
        this.address = address;
    }

    /**
     * @return the address of the proxy.
     */
    public InetSocketAddress address() {
        return address;
    }

    /**
     * @return the type of the proxy.
     */
    public Type type() {
        return type;
    }

    /**
     * The type of the proxy.
     */
    public enum Type {
        /**
         * HTTP proxy type.
         */
        HTTP(Proxy.HTTP),
        /**
         * SOCKS4 proxy type.
         */
        SOCKS4(Proxy.SOCKS4),
        /**
         * SOCKS5 proxy type.
         */
        SOCKS5(Proxy.SOCKS5);

        private final Proxy value;

        Type(Proxy reactorProxyType) {
            this.value = reactorProxyType;
        }

        Proxy value() {
            return value;
        }
    }
}
