/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.MessageLogConsumer;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.thrift.ApacheThriftCall;
import com.linecorp.armeria.common.thrift.ApacheThriftReply;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.logging.LogCollectingService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.AsyncIface;
import com.linecorp.armeria.service.test.thrift.main.SleepService;

import io.netty.handler.ssl.util.SelfSignedCertificate;

public abstract class AbstractThriftOverHttpTest {

    private static final Server server;

    private static int httpPort;
    private static int httpsPort;

    private static volatile boolean recordMessageLogs;
    private static final BlockingQueue<RequestLog> requestLogs = new LinkedBlockingQueue<>();
    private static final BlockingQueue<ResponseLog> responseLogs = new LinkedBlockingQueue<>();

    abstract static class HelloServiceBase implements AsyncIface {
        @Override
        public void hello(String name, AsyncMethodCallback resultHandler) throws TException {
            resultHandler.onComplete(getResponse(name));
        }

        protected String getResponse(String name) {
            return "Hello, " + name + '!';
        }
    }

    static class HelloServiceChild extends HelloServiceBase {
        @Override
        protected String getResponse(String name) {
            return "Goodbye, " + name + '!';
        }
    }

    static {
        final SelfSignedCertificate ssc;
        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.port(0, SessionProtocol.HTTP);
            sb.port(0, SessionProtocol.HTTPS);

            ssc = new SelfSignedCertificate("127.0.0.1");
            sb.sslContext(SessionProtocol.HTTPS, ssc.certificate(), ssc.privateKey());

            sb.serviceAt("/hello", THttpService.of(
                    (AsyncIface) (name, resultHandler) -> resultHandler.onComplete("Hello, " + name + '!')));

            sb.serviceAt("/hellochild", THttpService.of(new HelloServiceChild()));

            sb.serviceAt("/exception", THttpService.of(
                    (AsyncIface) (name, resultHandler) ->
                            resultHandler.onError(Exceptions.clearTrace(new Exception(name)))));

            sb.serviceAt("/hellochild", THttpService.of(new HelloServiceChild()));

            sb.serviceAt("/sleep", THttpService.of(
                    (SleepService.AsyncIface) (milliseconds, resultHandler) ->
                            RequestContext.current().eventLoop().schedule(
                                    () -> resultHandler.onComplete(milliseconds),
                                    milliseconds, TimeUnit.MILLISECONDS)));

            sb.decorator(LoggingService::new);

            final Function<Service<ThriftCall, ThriftReply>,
                    LogCollectingService<ThriftCall, ThriftReply>> logCollectingDecorator =
                    s -> new LogCollectingService<>(s, new MessageLogConsumer() {
                        @Override
                        public void onRequest(RequestContext ctx, RequestLog req) throws Exception {
                            if (recordMessageLogs) {
                                requestLogs.add(req);
                            }
                        }

                        @Override
                        public void onResponse(RequestContext ctx, ResponseLog res) throws Exception {
                            if (recordMessageLogs) {
                                responseLogs.add(res);
                            }
                        }
                    });

            sb.decorator(logCollectingDecorator);
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();

        httpPort = server.activePorts().values().stream()
                .filter(p -> p.protocol() == SessionProtocol.HTTP).findAny().get().localAddress().getPort();
        httpsPort = server.activePorts().values().stream()
                .filter(p -> p.protocol() == SessionProtocol.HTTPS).findAny().get().localAddress().getPort();
    }

    @AfterClass
    public static void destroy() throws Exception {
        server.stop();
    }

    @Before
    public void beforeTest() {
        recordMessageLogs = false;
        requestLogs.clear();
        responseLogs.clear();
    }

    @Test
    public void testHttpInvocation() throws Exception {
        try (TTransport transport = newTransport("http", "/hello")) {
            HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
        }
    }

    @Test
    public void testInheritedThriftService() throws Exception {
        try (TTransport transport = newTransport("http", "/hellochild")) {
            HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Goodbye, Trustin!");
        }
    }

    @Test
    public void testHttpsInvocation() throws Exception {
        try (TTransport transport = newTransport("https", "/hello")) {
            HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
        }
    }

    @Test(timeout = 10000)
    public void testMessageLogsForCall() throws Exception {
        try (TTransport transport = newTransport("http", "/hello")) {
            HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));
            recordMessageLogs = true;
            client.hello("Trustin");
        }

        final RequestLog req = requestLogs.take();
        final ResponseLog res = responseLogs.take();

        assertThat(req.hasAttr(RequestLog.HTTP_HEADERS)).isTrue();
        assertThat(req.hasAttr(RequestLog.RPC_REQUEST)).isTrue();
        assertThat(req.hasAttr(RequestLog.RAW_RPC_REQUEST)).isTrue();

        final ApacheThriftCall rawRequest = (ApacheThriftCall) req.attr(RequestLog.RAW_RPC_REQUEST).get();
        assertThat(rawRequest.header().type).isEqualTo(TMessageType.CALL);
        assertThat(rawRequest.header().name).isEqualTo("hello");
        assertThat(rawRequest.args()).isInstanceOf(HelloService.hello_args.class);
        assertThat(((HelloService.hello_args) rawRequest.args()).getName()).isEqualTo("Trustin");

        assertThat(res.hasAttr(ResponseLog.HTTP_HEADERS)).isTrue();
        assertThat(res.hasAttr(ResponseLog.RPC_RESPONSE)).isTrue();
        assertThat(res.hasAttr(ResponseLog.RAW_RPC_RESPONSE)).isTrue();

        final ApacheThriftReply rawResponse = (ApacheThriftReply) res.attr(ResponseLog.RAW_RPC_RESPONSE).get();
        assertThat(rawResponse.header().type).isEqualTo(TMessageType.REPLY);
        assertThat(rawResponse.header().name).isEqualTo("hello");
        assertThat(rawResponse.result()).isInstanceOf(HelloService.hello_result.class);
        assertThat(((HelloService.hello_result) rawResponse.result()).getSuccess())
                .isEqualTo("Hello, Trustin!");
    }

    @Test(timeout = 10000)
    public void testMessageLogsForException() throws Exception {
        try (TTransport transport = newTransport("http", "/exception")) {
            HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));
            recordMessageLogs = true;
            assertThatThrownBy(() -> client.hello("Trustin")).isInstanceOf(TApplicationException.class);
        }

        final RequestLog req = requestLogs.take();
        final ResponseLog res = responseLogs.take();

        assertThat(req.hasAttr(RequestLog.HTTP_HEADERS)).isTrue();
        assertThat(req.hasAttr(RequestLog.RPC_REQUEST)).isTrue();
        assertThat(req.hasAttr(RequestLog.RAW_RPC_REQUEST)).isTrue();

        final ApacheThriftCall rawRequest = (ApacheThriftCall) req.attr(RequestLog.RAW_RPC_REQUEST).get();
        assertThat(rawRequest.header().type).isEqualTo(TMessageType.CALL);
        assertThat(rawRequest.header().name).isEqualTo("hello");
        assertThat(rawRequest.args()).isInstanceOf(HelloService.hello_args.class);
        assertThat(((HelloService.hello_args) rawRequest.args()).getName()).isEqualTo("Trustin");

        assertThat(res.hasAttr(ResponseLog.HTTP_HEADERS)).isTrue();
        assertThat(res.hasAttr(ResponseLog.RPC_RESPONSE)).isTrue();
        assertThat(res.hasAttr(ResponseLog.RAW_RPC_RESPONSE)).isTrue();

        final ApacheThriftReply rawResponse = (ApacheThriftReply) res.attr(ResponseLog.RAW_RPC_RESPONSE)
                                                                     .get();
        assertThat(rawResponse.header().type).isEqualTo(TMessageType.EXCEPTION);
        assertThat(rawResponse.header().name).isEqualTo("hello");
        assertThat(rawResponse.exception()).isNotNull();
    }

    protected final TTransport newTransport(String scheme, String path) throws TTransportException {
        return newTransport(newUri(scheme, path));
    }

    protected abstract TTransport newTransport(String uri) throws TTransportException;

    protected static String newUri(String scheme, String path) {
        switch (scheme) {
        case "http":
            return scheme + "://127.0.0.1:" + httpPort + path;
        case "https":
            return scheme + "://127.0.0.1:" + httpsPort + path;
        }

        throw new Error();
    }
}
