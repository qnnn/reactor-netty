/*
 * Copyright (c) 2011-2024 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty5.http.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.net.ssl.SSLException;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelId;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.group.ChannelGroup;
import io.netty5.channel.group.DefaultChannelGroup;
import io.netty5.channel.nio.NioHandler;
import io.netty5.channel.socket.DomainSocketAddress;
import io.netty5.handler.codec.http.DefaultFullHttpResponse;
import io.netty5.handler.codec.http.HttpClientCodec;
import io.netty5.handler.codec.http.HttpContentDecompressor;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
import io.netty5.handler.codec.http.headers.DefaultHttpCookiePair;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpObjectDecoder;
import io.netty5.handler.codec.http.HttpResponseEncoder;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.HttpVersion;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.ssl.SslContext;
import io.netty5.handler.ssl.SslContextBuilder;
import io.netty5.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty5.handler.ssl.util.SelfSignedCertificate;
import io.netty5.handler.timeout.ReadTimeoutHandler;
import io.netty5.resolver.AddressResolverGroup;
import io.netty5.resolver.dns.DnsAddressResolverGroup;
import io.netty5.util.concurrent.SingleThreadEventExecutor;
import io.netty5.util.concurrent.EventExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty5.BaseHttpTest;
import reactor.netty5.BufferFlux;
import reactor.netty5.BufferMono;
import reactor.netty5.CancelReceiverHandlerTest;
import reactor.netty5.Connection;
import reactor.netty5.FutureMono;
import reactor.netty5.LogTracker;
import reactor.netty5.NettyPipeline;
import reactor.netty5.SocketUtils;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.http.Http11SslContextSpec;
import reactor.netty5.http.Http2SslContextSpec;
import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.HttpResources;
import reactor.netty5.http.server.HttpServer;
import reactor.netty5.resources.ConnectionPoolMetrics;
import reactor.netty5.resources.ConnectionProvider;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.tcp.SslProvider;
import reactor.netty5.tcp.TcpServer;
import reactor.test.StepVerifier;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * This test class verifies {@link HttpClient}.
 *
 * @author Stephane Maldini
 * @since 0.6
 */
class HttpClientTest extends BaseHttpTest {

	static final Logger log = Loggers.getLogger(HttpClientTest.class);

	static SelfSignedCertificate ssc;
	static final EventExecutor executor = new SingleThreadEventExecutor();

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@AfterAll
	static void cleanup() throws ExecutionException, InterruptedException, TimeoutException {
		executor.shutdownGracefully()
				.asStage().get(30, TimeUnit.SECONDS);
	}

	@Test
	void abort() {
		disposableServer =
				TcpServer.create()
				         .port(0)
				         .handle((in, out) ->
				                 in.receive()
				                   .take(1)
				                   .thenMany(Flux.defer(() ->
				                           out.withConnection(c ->
				                                   c.addHandlerFirst(new HttpResponseEncoder()))
				                              .sendObject(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                                                                      HttpResponseStatus.ACCEPTED,
				                                                                      out.alloc().allocate(0)))
				                              .then(Mono.delay(Duration.ofSeconds(2)).then()))))
				         .wiretap(true)
				         .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("abort", 1);

		HttpClient client = createHttpClientForContextWithPort(pool);

		client.get()
		      .uri("/")
		      .responseSingle((r, buf) -> Mono.just(r.status().code()))
		      .log()
		      .block(Duration.ofSeconds(30));

		client.get()
		      .uri("/")
		      .responseContent()
		      .log()
		      .blockLast(Duration.ofSeconds(30));

		client.get()
		      .uri("/")
		      .responseContent()
		      .log()
		      .blockLast(Duration.ofSeconds(30));

		pool.dispose();
	}

	/** This ensures that non-default values for the HTTP request line are visible for parsing. */
	@Test
	void postVisibleToOnRequest() {
		disposableServer =
				createServer()
				          .route(r -> r.post("/foo", (in, out) -> out.sendString(Flux.just("bar"))))
				          .bindNow();

		final AtomicReference<HttpMethod> method = new AtomicReference<>();
		final AtomicReference<String> path = new AtomicReference<>();

		final HttpClientResponse response =
				createHttpClientForContextWithPort()
				        .doOnRequest((req, con) -> {
				            method.set(req.method());
				            path.set(req.path());
				        })
				        .post()
				        .uri("/foo")
				        .send(BufferFlux.fromString(Mono.just("bar")))
				        .response()
				        .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
		assertThat(method.get()).isEqualTo(HttpMethod.POST);
		// req.path() returns the decoded path, without a leading "/"
		assertThat(path.get()).isEqualTo("foo");
	}

	@Test
	void userIssue() throws Exception {
		final ConnectionProvider pool = ConnectionProvider.create("userIssue", 1);
		CountDownLatch latch = new CountDownLatch(3);
		Set<String> localAddresses = ConcurrentHashMap.newKeySet();
		disposableServer =
				createServer()
				          .route(r -> r.post("/",
				                  (req, resp) -> req.receive()
				                                    .asString()
				                                    .flatMap(data -> {
				                                        latch.countDown();
				                                        return resp.status(200)
				                                                   .send();
				                                    })))
				          .bindNow();

		final HttpClient client = createHttpClientForContextWithAddress(pool);

		Flux.just("1", "2", "3")
		    .concatMap(data ->
		            client.doOnResponse((res, conn) ->
		                    localAddresses.add(conn.channel()
		                                           .localAddress()
		                                           .toString()))
		                  .post()
		                  .uri("/")
		                  .send(BufferFlux.fromString(Flux.just(data)))
		                  .responseContent())
		    .subscribe();


		latch.await();
		pool.dispose();
		System.out.println("Local Addresses used: " + localAddresses);
	}

	@Test
	void testClientReuseIssue405() {
		disposableServer =
				createServer()
				          .handle((in, out) -> out.sendString(Flux.just("hello")))
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("testClientReuseIssue405", 1);
		HttpClient httpClient = createHttpClientForContextWithPort(pool);

		Mono<String> mono1 =
				httpClient.get()
				          .responseSingle((r, buf) -> buf.asString())
				          .log("mono1");

		Mono<String> mono2 =
				httpClient.get()
				          .responseSingle((r, buf) -> buf.asString())
				          .log("mono1");

		StepVerifier.create(Flux.zip(mono1, mono2))
		            .expectNext(Tuples.of("hello", "hello"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(20));

		pool.dispose();
	}

	@Test
	//https://github.com/reactor/reactor-pool/issues/82
	void testConnectionRefusedConcurrentRequests() {
		ConnectionProvider provider = ConnectionProvider.create("testConnectionRefusedConcurrentRequests", 1);

		HttpClient httpClient = createClient(provider, 8282);

		Mono<String> mono1 =
				httpClient.get()
				          .responseSingle((r, buf) -> buf.asString())
				          .log("mono1");

		Mono<String> mono2 =
				httpClient.get()
				          .responseSingle((r, buf) -> buf.asString())
				          .log("mono2");

		StepVerifier.create(Flux.just(mono1.onErrorResume(e -> Mono.empty()), mono2)
		                        .flatMap(Function.identity()))
		            .expectError()
		            .verify(Duration.ofSeconds(5));

		provider.disposeLater()
		        .block(Duration.ofSeconds(5));
	}

	@Test
	void backpressured() throws Exception {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		disposableServer = createServer()
		                             .route(routes -> routes.directory("/test", resource))
		                             .bindNow();

		BufferFlux remote =
				createHttpClientForContextWithPort()
				        .get()
				        .uri("/test/test.css")
				        .responseContent();

		Mono<String> page = remote.asString()
		                          .limitRate(1)
		                          .reduce(String::concat);

		Mono<String> cancelledPage = remote.asString()
		                                   .take(5)
		                                   .limitRate(1)
		                                   .reduce(String::concat);

		page.block(Duration.ofSeconds(30));
		cancelledPage.block(Duration.ofSeconds(30));
		page.block(Duration.ofSeconds(30));
	}

	@Test
	void serverInfiniteClientClose() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				createServer()
				          .handle((req, resp) -> {
				          	req.withConnection(cn -> cn.onDispose(latch::countDown));

				                  return Flux.interval(Duration.ofSeconds(1))
				                             .flatMap(d -> resp.sendObject(preferredAllocator().allocate(0)));
				          })
				          .bindNow();

		createHttpClientForContextWithPort()
		        .get()
		        .uri("/")
		        .response()
		        .block(Duration.ofSeconds(5));

		latch.await();
	}

	@Test
	void simpleTestHttps() {
		StepVerifier.create(HttpClient.create()
		                              .wiretap(true)
		                              .get()
		                              .uri("https://example.com")
		                              .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));

		StepVerifier.create(HttpClient.create()
		                              .wiretap(true)
		                              .get()
		                              .uri("https://example.com")
		                              .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));
	}

	@Test
	void prematureCancel() {
		Sinks.Many<Void> signal = Sinks.unsafe().many().unicast().onBackpressureError();
		disposableServer =
				TcpServer.create()
				         .host("localhost")
				         .port(0)
				         .handle((in, out) -> {
				             signal.tryEmitComplete().orThrow();
				             return out.withConnection(c -> c.addHandlerFirst(new HttpResponseEncoder()))
				                       .sendObject(Mono.delay(Duration.ofSeconds(2))
				                                       .map(t -> new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                                                                             HttpResponseStatus.PROCESSING,
				                                                                             out.alloc().allocate(0))))
				                       .neverComplete();
				         })
				         .wiretap(true)
				         .bindNow(Duration.ofSeconds(30));

		StepVerifier.create(
				createHttpClientForContextWithAddress()
				        .get()
				        .uri("/")
				        .responseContent()
				        .timeout(signal.asFlux()))
				    .expectError(TimeoutException.class)
				    .verify(Duration.ofSeconds(5));
	}

	@Test
	void gzip() {
		String content = "HELLO WORLD";

		disposableServer =
				createServer()
				          .compress(true)
				          .handle((req, res) -> res.sendString(Mono.just(content)))
				          .bindNow();

		//verify gzip is negotiated (when no decoder)
		StepVerifier.create(
				createHttpClientForContextWithPort()
				        .headers(h -> h.add("Accept-Encoding", "gzip")
				                       .add("Accept-Encoding", "deflate"))
				        .followRedirect(true)
				        .get()
				        .response((r, buf) -> buf.aggregate()
				                                 .asString()
				                                 .zipWith(Mono.just(r.responseHeaders()
				                                                     .get("Content-Encoding", "")))
				                                 .zipWith(Mono.just(r))))
				    .expectNextMatches(tuple -> {
				            String content1 = tuple.getT1().getT1();
				            return !content1.equals(content)
				                   && "gzip".equals(tuple.getT1().getT2().toString());
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		//verify decoder does its job and removes the header
		StepVerifier.create(
				createHttpClientForContextWithPort()
				        .followRedirect(true)
				        .headers(h -> h.add("Accept-Encoding", "gzip")
				                       .add("Accept-Encoding", "deflate"))
				        .doOnRequest((req, conn) ->
				                conn.addHandlerFirst("gzipDecompressor", new HttpContentDecompressor()))
				        .get()
				        .response((r, buf) -> buf.aggregate()
				                                 .asString()
				                                 .zipWith(Mono.just(r.responseHeaders()
				                                                     .get("Content-Encoding", "")))
				                                 .zipWith(Mono.just(r))))
				    .expectNextMatches(tuple -> {
				            String content1 = tuple.getT1().getT1();
				            return content1.equals(content)
				                   && "".equals(tuple.getT1().getT2().toString());
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void gzipEnabled() {
		doTestGzip(true);
	}

	@Test
	void gzipDisabled() {
		doTestGzip(false);
	}

	private void doTestGzip(boolean gzipEnabled) {
		String expectedResponse = gzipEnabled ? "gzip" : "no gzip";
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendString(Mono.just(req.requestHeaders()
				                                                           .get(HttpHeaderNames.ACCEPT_ENCODING,
				                                                                "no gzip").toString())))
				          .bindNow();
		HttpClient client = createHttpClientForContextWithPort();

		if (gzipEnabled) {
			client = client.compress(true);
		}

		StepVerifier.create(client.get()
		                          .uri("/")
		                          .response((r, buf) -> buf.asString()
		                                                   .elementAt(0)
		                                                   .zipWith(Mono.just(r))))
		            .expectNextMatches(tuple -> expectedResponse.equals(tuple.getT1()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testUserAgent() {
		disposableServer =
				createServer()
				          .handle((req, resp) -> {
				                  assertThat(req.requestHeaders()
				                                .contains(HttpHeaderNames.USER_AGENT) &&
				                                   getHeader(req.requestHeaders(), HttpHeaderNames.USER_AGENT, "")
				                                      .equals(HttpClient.USER_AGENT))
				                      .as("" + req.requestHeaders()
				                                  .get(HttpHeaderNames.USER_AGENT))
				                      .isTrue();

				                  return req.receive().then();
				          })
				          .bindNow();

		createHttpClientForContextWithPort()
		        .get()
		        .uri("/")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(5));
	}

	@Test
	void gettingOptionsDuplicates() {
		HttpClient client1 = HttpClient.create();
		HttpClient client2 = client1.host("example.com")
		                            .wiretap(true)
		                            .port(123)
		                            .compress(true);
		assertThat(client2)
				.isNotSameAs(client1)
				.isNotSameAs(((HttpClientConnect) client2).duplicate());
	}

	@Test
	void sslExchangeRelativeGet() throws SSLException {
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                        .build();

		disposableServer =
				createServer()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .handle((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .bindNow();


		String responseString =
				createHttpClientForContextWithAddress()
				          .secure(ssl -> ssl.sslContext(sslClient))
				          .get()
				          .uri("/foo")
				          .responseSingle((res, buf) -> buf.asString(StandardCharsets.UTF_8))
				          .block(Duration.ofMillis(200));

		assertThat(responseString).isEqualTo("hello /foo");
	}

	@Test
	void sslExchangeAbsoluteGet() throws SSLException {
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();

		disposableServer =
				createServer()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .handle((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .bindNow();

		String responseString = createHttpClientForContextWithAddress()
		                                .secure(ssl -> ssl.sslContext(sslClient))
		                                .get()
		                                .uri("/foo")
		                                .responseSingle((res, buf) -> buf.asString(StandardCharsets.UTF_8))
		                                .block(Duration.ofSeconds(5));

		assertThat(responseString).isEqualTo("hello /foo");
	}

	@Test
	void secureSendFile() throws SSLException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		AtomicReference<String> uploaded = new AtomicReference<>();

		disposableServer =
				createServer()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .route(r -> r.post("/upload", (req, resp) ->
				                  req.receive()
				                     .aggregate()
				                     .asString(StandardCharsets.UTF_8)
				                     .log()
				                     .doOnNext(uploaded::set)
				                     .then(resp.status(201).sendString(Mono.just("Received File")).then())))
				          .bindNow();

		Tuple2<String, Integer> response =
				createHttpClientForContextWithAddress()
				        .secure(ssl -> ssl.sslContext(sslClient))
				        .post()
				        .uri("/upload")
				        .send((r, out) -> out.sendFile(largeFile))
				        .responseSingle((res, buf) -> buf.asString()
				                                         .zipWith(Mono.just(res.status().code())))
				        .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.getT2()).isEqualTo(201);
		assertThat(response.getT1()).isEqualTo("Received File");

		assertThat(uploaded.get())
		                   .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " +
		                           "It contains accents like é.")
		                   .contains("1024 mark here -><- 1024 mark here")
		                   .endsWith("End of File");
	}

	@Test
	void chunkedSendFile() throws URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		AtomicReference<String> uploaded = new AtomicReference<>();

		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.post("/upload", (req, resp) ->
				                  req.receive()
				                    .aggregate()
				                    .asString(StandardCharsets.UTF_8)
				                    .doOnNext(uploaded::set)
				                    .then(resp.status(201)
				                              .sendString(Mono.just("Received File"))
				                              .then())))
				          .bindNow();

		Tuple2<String, Integer> response =
				createHttpClientForContextWithAddress()
				        .post()
				        .uri("/upload")
				        .send((r, out) -> out.sendFile(largeFile))
				        .responseSingle((res, buf) -> buf.asString()
				                                         .zipWith(Mono.just(res.status().code())))
				        .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.getT2()).isEqualTo(201);
		assertThat(response.getT1()).isEqualTo("Received File");

		assertThat(uploaded.get())
		                   .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " +
		                           "It contains accents like é.")
		                   .contains("1024 mark here -><- 1024 mark here")
		                   .endsWith("End of File");
	}

	@Test
	void test() throws Exception {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.put("/201", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders())
				                       .put("/204", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                     .sendHeaders())
				                       .get("/200", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .sendHeaders()))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(3);
		AtomicInteger onReq = new AtomicInteger();
		AtomicInteger afterReq = new AtomicInteger();
		AtomicInteger onResp = new AtomicInteger();
		createHttpClientForContextWithAddress()
		        .doOnRequest((r, c) -> onReq.getAndIncrement())
		        .doAfterRequest((r, c) -> afterReq.getAndIncrement())
		        .doOnResponse((r, c) -> onResp.getAndIncrement())
		        .doAfterResponseSuccess((r, c) -> latch.countDown())
		        .put()
		        .uri("/201")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(5));

		createHttpClientForContextWithAddress()
		        .doOnRequest((r, c) -> onReq.getAndIncrement())
		        .doAfterRequest((r, c) -> afterReq.getAndIncrement())
		        .doOnResponse((r, c) -> onResp.getAndIncrement())
		        .doAfterResponseSuccess((r, c) -> latch.countDown())
		        .put()
		        .uri("/204")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(30));

		createHttpClientForContextWithAddress()
		        .doOnRequest((r, c) -> onReq.getAndIncrement())
		        .doAfterRequest((r, c) -> afterReq.getAndIncrement())
		        .doOnResponse((r, c) -> onResp.getAndIncrement())
		        .doAfterResponseSuccess((r, c) -> latch.countDown())
		        .get()
		        .uri("/200")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(onReq.get()).isEqualTo(3);
		assertThat(afterReq.get()).isEqualTo(3);
		assertThat(onResp.get()).isEqualTo(3);
	}

	@Test
	void testDeferredUri() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.get("/201", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders())
				                       .get("/204", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                     .sendHeaders())
				                       .get("/200", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .sendHeaders()))
				          .bindNow();

		AtomicInteger i = new AtomicInteger();
		createHttpClientForContextWithAddress()
		        .observe((c, s) -> log.info(s + "" + c))
		        .get()
		        .uri(Mono.fromCallable(() -> {
		            switch (i.incrementAndGet()) {
		                case 1: return "/201";
		                case 2: return "/204";
		                case 3: return "/200";
		                default: return null;
		            }
		        }))
		        .responseContent()
		        .repeat(4)
		        .blockLast(Duration.ofSeconds(5));
	}

	@Test
	void testDeferredHeader() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.get("/201", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders()))
				          .bindNow();

		createHttpClientForContextWithAddress()
		        .headersWhen(h -> Mono.just(h.set("test", "test")).delayElement(Duration.ofSeconds(2)))
		        .observe((c, s) -> log.debug(s + "" + c))
		        .get()
		        .uri("/201")
		        .responseContent()
		        .repeat(4)
		        .blockLast(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("CollectionUndefinedEquality")
	void testCookie() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.get("/201",
				                  (req, res) -> res.addHeader("test",
				                                              req.cookies()
				                                                 // Suppressed "CollectionUndefinedEquality", the CharSequence is String
				                                                 .get("test")
				                                                 .stream()
				                                                 .findFirst()
				                                                 .get()
				                                                 .value())
				                                   .status(HttpResponseStatus.CREATED)
				                                   .sendHeaders()))
				          .bindNow();

		createHttpClientForContextWithAddress()
		        .cookie(new DefaultHttpCookiePair("test", "lol"))
		        .get()
		        .uri("/201")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(5));
	}

	@Test
	void closePool() {
		ConnectionProvider pr = ConnectionProvider.create("closePool", 1);
		disposableServer =
				createServer()
				          .handle((in, out) ->  out.sendString(Mono.just("test")
				                                                   .delayElement(Duration.ofMillis(100))
				                                                   .repeat()))
				          .bindNow();

		Flux<String> ws = createHttpClientForContextWithPort(pr)
		                          .get()
		                          .uri("/")
		                          .responseContent()
		                          .asString();

		List<String> expected =
				Flux.range(1, 20)
				    .map(v -> "test")
				    .collectList()
				    .block();
		assertThat(expected).isNotNull();

		StepVerifier.create(
				Flux.range(1, 10)
				    .concatMap(i -> ws.take(2)
				                      .log()))
				    .expectNextSequence(expected)
				    .expectComplete()
				    .verify(Duration.ofSeconds(5));

		pr.dispose();
	}

	@Test
	void testIssue303() {
		disposableServer =
				createServer()
				          .handle((req, resp) -> resp.sendString(Mono.just("OK")))
				          .bindNow();

		Mono<String> content =
				createHttpClientForContextWithPort()
				        .request(HttpMethod.GET)
				        .uri("/")
				        .send(BufferFlux.fromInbound(Mono.defer(() -> Mono.just("Hello".getBytes(Charset.defaultCharset())))))
				        .responseContent()
				        .aggregate()
				        .asString();

		StepVerifier.create(content)
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	private HttpClient createHttpClientForContextWithAddress() {
		return createHttpClientForContextWithAddress(null);
	}

	private HttpClient createHttpClientForContextWithAddress(@Nullable ConnectionProvider pool) {
		return createClient(pool, disposableServer::address);
	}

	private HttpClient createHttpClientForContextWithPort() {
		return createHttpClientForContextWithPort(null);
	}

	private HttpClient createHttpClientForContextWithPort(@Nullable ConnectionProvider pool) {
		return createClient(pool, disposableServer.port());
	}

	@Test
	void testIssue361() {
		disposableServer =
				createServer()
				          .handle((req, res) -> req.receive()
				                                   .aggregate()
				                                   .asString()
				          .flatMap(s -> res.sendString(Mono.just(s))
				                           .then()))
				          .bindNow();

		assertThat(disposableServer).isNotNull();

		ConnectionProvider connectionProvider = ConnectionProvider.create("testIssue361", 1);
		HttpClient client = createHttpClientForContextWithPort(connectionProvider);

		String response = client.post()
		                        .uri("/")
		                        .send(BufferFlux.fromString(Mono.just("test")
		                                         .then(Mono.error(new Exception("error")))))
		                        .responseContent()
		                        .aggregate()
		                        .asString()
		                        .onErrorResume(t -> Mono.just(t.getMessage()))
		                        .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("error");

		response = client.post()
		                 .uri("/")
		                 .send(BufferFlux.fromString(Mono.just("test")))
		                 .responseContent()
		                 .aggregate()
		                 .asString()
		                 .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("test");

		connectionProvider.dispose();
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue473() {
		Http11SslContextSpec serverSslContextBuilder =
				Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		disposableServer =
				createServer()
				          .secure(spec -> spec.sslContext(serverSslContextBuilder))
				          .bindNow();

		StepVerifier.create(
				HttpClient.create(ConnectionProvider.newConnection())
				          .secure()
				          .websocket()
				          .uri("wss://" + disposableServer.host() + ":" + disposableServer.port())
				          .handle((in, out) -> Mono.empty()))
				    .expectErrorMatches(t -> t.getCause() instanceof CertificateException)
				.verify(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue407_1() {
		disposableServer =
				createServer()
				          .secure(spec -> spec.sslContext(
				              Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())))
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .bindNow(Duration.ofSeconds(30));

		ConnectionProvider provider = ConnectionProvider.create("testIssue407_1", 1);
		HttpClient client =
				createHttpClientForContextWithAddress(provider)
				        .secure(spec -> spec.sslContext(
				            Http11SslContextSpec.forClient()
				                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE))));

		AtomicReference<Channel> ch1 = new AtomicReference<>();
		StepVerifier.create(client.doOnConnected(c -> ch1.set(c.channel()))
				                  .get()
				                  .uri("/1")
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch2 = new AtomicReference<>();
		StepVerifier.create(client.doOnConnected(c -> ch2.set(c.channel()))
				                  .post()
				                  .uri("/2")
				                  .send(BufferFlux.fromString(Mono.just("test")))
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch3 = new AtomicReference<>();
		StepVerifier.create(
				client.doOnConnected(c -> ch3.set(c.channel()))
				      .secure(spec -> spec.sslContext(
				          Http11SslContextSpec.forClient()
				                              .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE))))
				      .post()
				      .uri("/3")
				      .responseContent()
				      .aggregate()
				      .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		assertThat(ch1.get()).isSameAs(ch2.get());
		assertThat(ch1.get()).isNotSameAs(ch3.get());

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue407_2() {
		disposableServer =
				createServer()
				          .secure(spec -> spec.sslContext(
				              Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())))
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .bindNow(Duration.ofSeconds(30));

		Http11SslContextSpec clientSslContextBuilder1 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		Http11SslContextSpec clientSslContextBuilder2 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		ConnectionProvider provider = ConnectionProvider.create("testIssue407_2", 1);
		HttpClient client =
				createHttpClientForContextWithAddress(provider)
				        .secure(spec -> spec.sslContext(clientSslContextBuilder1));

		AtomicReference<Channel> ch1 = new AtomicReference<>();
		StepVerifier.create(client.doOnConnected(c -> ch1.set(c.channel()))
				                  .get()
				                  .uri("/1")
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch2 = new AtomicReference<>();
		StepVerifier.create(client.doOnConnected(c -> ch2.set(c.channel()))
				                  .post()
				                  .uri("/2")
				                  .send(BufferFlux.fromString(Mono.just("test")))
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch3 = new AtomicReference<>();
		StepVerifier.create(
				client.doOnConnected(c -> ch3.set(c.channel()))
				      .secure(spec -> spec.sslContext(clientSslContextBuilder2))
				      .post()
				      .uri("/3")
				      .responseContent()
				      .aggregate()
				      .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		assertThat(ch1.get()).isSameAs(ch2.get());
		assertThat(ch1.get()).isNotSameAs(ch3.get());

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}


	@Test
	void testClientContext_WithPool() throws Exception {
		doTestClientContext(HttpClient.create());
	}

	@Test
	void testClientContext_NoPool() throws Exception {
		doTestClientContext(HttpClient.create(ConnectionProvider.newConnection()));
	}

	private void doTestClientContext(HttpClient client) throws Exception {
		CountDownLatch latch = new CountDownLatch(4);

		disposableServer =
				createServer()
				          .handle((req, res) -> res.send(req.receive().transferOwnership()))
				          .bindNow();

		StepVerifier.create(
				client.port(disposableServer.port())
				      .doOnRequest((req, c) -> {
				          if (req.currentContextView().hasKey("test")) {
				              latch.countDown();
				          }
				      })
				      .doAfterRequest((req, c) -> {
				          if (req.currentContextView().hasKey("test")) {
				              latch.countDown();
				          }
				      })
				      .doOnResponse((res, c) -> {
				          if (res.currentContextView().hasKey("test")) {
				              latch.countDown();
				          }
				      })
				      .doAfterResponseSuccess((req, c) -> {
				          if (req.currentContextView().hasKey("test")) {
				              latch.countDown();
				          }
				      })
				      .post()
				      .send((req, out) ->
				          out.sendString(Mono.deferContextual(Mono::just)
				                             .map(ctx -> ctx.getOrDefault("test", "fail"))))
				      .responseContent()
				      .asString()
				      .contextWrite(Context.of("test", "success")))
				    .expectNext("success")
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isEqualTo(true);
	}

	@Test
	void doOnError() {
		disposableServer =
				createServer()
				          .handle((req, resp) -> {
				              if (req.requestHeaders().contains("during")) {
				                  return resp.sendString(Flux.just("test").hide())
				                             .then(Mono.error(new RuntimeException("test")));
				              }
				              throw new RuntimeException("test");
				          })
				          .bindNow();

		HttpClient client = createHttpClientForContextWithPort();
		doOnError(client.headers(h -> h.add("before", "test")));
		doOnError(client.headersWhen(h -> Mono.just(h.add("before", "test"))));
	}

	private void doOnError(HttpClient client) {
		AtomicReference<String> requestError1 = new AtomicReference<>();
		AtomicReference<String> responseError1 = new AtomicReference<>();

		Mono<String> content =
				client.doOnRequestError((req, err) ->
				          requestError1.set(req.currentContextView().getOrDefault("test", "empty")))
				      .doOnResponseError((res, err) ->
				          responseError1.set(res.currentContextView().getOrDefault("test", "empty")))
				      .mapConnect(c -> c.contextWrite(Context.of("test", "success")))
				      .get()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString();

		StepVerifier.create(content)
		            .expectError(PrematureCloseException.class)
		            .verify(Duration.ofSeconds(5));

		assertThat(requestError1.get()).isEqualTo("success");
		assertThat(responseError1.get()).isNull();

		AtomicReference<String> requestError2 = new AtomicReference<>();
		AtomicReference<String> responseError2 = new AtomicReference<>();

		content =
				createHttpClientForContextWithPort()
				        .headers(h -> h.add("during", "test"))
				        .doOnError((req, err) ->
				            requestError2.set(req.currentContextView().getOrDefault("test", "empty")),
				            (res, err) ->
				            responseError2.set(res.currentContextView().getOrDefault("test", "empty")))
				        .mapConnect(c -> c.contextWrite(Context.of("test", "success")))
				        .get()
				        .uri("/")
				        .responseContent()
				        .aggregate()
				        .asString();

		StepVerifier.create(content)
		            .expectError(PrematureCloseException.class)
		            .verify(Duration.ofSeconds(5));

		assertThat(requestError2.get()).isNull();
		assertThat(responseError2.get()).isEqualTo("success");
	}

	@Test
	void withConnector_1() {
		disposableServer = createServer()
		                             .handle((req, resp) ->
		                                 resp.sendString(Mono.just(getHeader(req.requestHeaders(), "test",
				                                 "header not found from server"))))
		                             .bindNow();

		Mono<String> content = createHttpClientForContextWithPort()
		                               .mapConnect(c -> c.contextWrite(Context.of("test", "success")))
		                               .post()
		                               .uri("/")
		                               .send((req, out) -> {
		                                   req.requestHeaders()
		                                      .set("test",
		                                           req.currentContextView()
		                                              .getOrDefault("test", "fail"));
		                                   return Mono.empty();
		                               })
		                               .responseContent()
		                               .aggregate()
		                               .asString();

		StepVerifier.create(content)
		            .expectNext("success")
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));
	}

	@ParameterizedTest
	@MethodSource("dataWithConnector_2")
	void withConnector_2(boolean withConnector, String expectation) {
		disposableServer =
				createServer().handle((req, resp) -> resp.sendString(Mono.just(getHeader(req.requestHeaders(), "test",
				                  "header not found from server"))))
				              .bindNow();

		HttpClient client = createHttpClientForContextWithPort();
		if (withConnector) {
			client = client.mapConnect(c -> c.contextWrite(Context.of("test", "Second")));
		}

		HttpClient.ResponseReceiver<?> responseReceiver =
				client.post()
				      .uri("/")
				      .send((req, out) -> Mono.deferContextual(ctx -> {
				          req.requestHeaders().set("test", ctx.getOrDefault("test", "Fail"));
				          return Mono.empty();
				      }));

		doWithConnector_2(
				responseReceiver.responseConnection((res, conn) -> Mono.deferContextual(ctx ->
				                    conn.inbound()
				                        .receive()
				                        .aggregate()
				                        .asString()
				                        .flatMap(s -> Mono.just(s + ctx.getOrDefault("test", "Fail")))))
				                .contextWrite(Context.of("test", "First")),
				expectation);

		doWithConnector_2(
				responseReceiver.response((res, bytes) -> Mono.deferContextual(ctx ->
				                    bytes.aggregate()
				                         .asString()
				                         .flatMap(s -> Mono.just(s + ctx.getOrDefault("test", "Fail")))))
				                .contextWrite(Context.of("test", "First")),
				expectation);

		doWithConnector_2(
				responseReceiver.responseSingle((res, bytes) -> Mono.deferContextual(ctx ->
				                    bytes.asString()
				                         .flatMap(s -> Mono.just(s + ctx.getOrDefault("test", "Fail")))))
				                .contextWrite(Context.of("test", "First")),
				expectation);
	}

	static Object[][] dataWithConnector_2() {
		return new Object[][]{
				{true, "SecondSecond"},
				{false, "FirstFirst"}
		};
	}

	private void doWithConnector_2(Publisher<String> content, String expectation) {
		StepVerifier.create(content)
		            .expectNext(expectation)
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));
	}

	@Test
	void testPreferContentLengthWhenPost() {
		disposableServer =
				createServer()
				          .handle((req, res) ->
				                  res.header(HttpHeaderNames.CONTENT_LENGTH,
				                             req.requestHeaders()
				                                .get(HttpHeaderNames.CONTENT_LENGTH))
				                     .send(req.receive()
				                              .aggregate()
				                              .transferOwnership()))
				          .bindNow();

		StepVerifier.create(
				createHttpClientForContextWithAddress()
				        .headers(h -> h.add(HttpHeaderNames.CONTENT_LENGTH, "5"))
				        .post()
				        .uri("/")
				        .send(Mono.just(preferredAllocator().copyOf("hello".getBytes(Charset.defaultCharset()))))
				        .responseContent()
				        .aggregate()
				        .asString())
				    .expectNextMatches("hello"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void testExplicitEmptyBodyOnGetWorks() throws Exception {
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();

		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                        .build();

		disposableServer =
				createServer()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .handle((req, res) -> res.send(req.receive().transferOwnership()))
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("testExplicitEmptyBodyOnGetWorks", 1);

		for (int i = 0; i < 4; i++) {
			StepVerifier.create(createHttpClientForContextWithAddress(pool)
			                            .secure(ssl -> ssl.sslContext(sslClient))
			                            .request(HttpMethod.GET)
			                            .uri("/")
			                            .send((req, out) -> out.send(Flux.empty()))
			                            .responseContent())
			            .expectComplete()
			            .verify(Duration.ofSeconds(30));
		}

		pool.dispose();
	}

	@Test
	void testExplicitSendMonoErrorOnGet() {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.send(req.receive().transferOwnership()))
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("test", 1);

		StepVerifier.create(
				Flux.range(0, 1000)
				    .flatMapDelayError(i ->
				        createHttpClientForContextWithAddress(pool)
				                .request(HttpMethod.GET)
				                .uri("/")
				                .send((req, out) -> out.send(Mono.error(new Exception("test"))))
				                .responseContent(), Queues.SMALL_BUFFER_SIZE, Queues.XS_BUFFER_SIZE))
				    .expectError()
				    .verify(Duration.ofSeconds(30));

		pool.dispose();
	}

	@Test
	void testRetryNotEndlessIssue587() throws Exception {
		doTestRetry(false, true);
	}

	@Test
	void testRetryDisabledWhenHeadersSent() throws Exception {
		doTestRetry(false, false);
	}

	@Test
	void testRetryDisabledIssue995() throws Exception {
		doTestRetry(true, false);
	}

	private void doTestRetry(boolean retryDisabled, boolean expectRetry) throws Exception {
		ExecutorService threadPool = Executors.newCachedThreadPool();
		int serverPort = SocketUtils.findAvailableTcpPort();
		ConnectionResetByPeerServer server = new ConnectionResetByPeerServer(serverPort);
		Future<?> serverFuture = threadPool.submit(server);
		if (!server.await(10, TimeUnit.SECONDS)) {
			throw new IOException("fail to start test server");
		}

		AtomicInteger doOnRequest = new AtomicInteger();
		AtomicInteger doOnRequestError = new AtomicInteger();
		AtomicInteger doOnResponseError = new AtomicInteger();
		HttpClient client =
				createClient(serverPort)
				          .doOnRequest((req, conn) -> doOnRequest.getAndIncrement())
				          .doOnError((req, t) -> doOnRequestError.getAndIncrement(),
				                     (res, t) -> doOnResponseError.getAndIncrement());

		if (retryDisabled) {
			client = client.disableRetry(retryDisabled);
		}

		AtomicReference<Throwable> error = new AtomicReference<>();
		StepVerifier.create(client.request(HttpMethod.GET)
		                          .uri("/")
		                          .send((req, out) -> {
		                              if (expectRetry) {
		                                  return Mono.error(new IOException("Connection reset by peer"));
		                              }
		                              return out;
		                          })
		                          .responseContent())
		            .expectErrorMatches(t -> {
		                error.set(t);
		                return t.getMessage() != null &&
		                               (t.getMessage().contains("Connection reset by peer") ||
		                                        t.getMessage().contains("Connection reset") ||
		                                        t.getMessage().contains("readAddress(..)") || // https://github.com/reactor/reactor-netty/issues/1673
		                                        t.getMessage().contains("Connection prematurely closed BEFORE response"));
		            })
		            .verify(Duration.ofSeconds(30));

		int requestCount = 1;
		int requestErrorCount = 1;
		if (expectRetry && !(error.get() instanceof PrematureCloseException)) {
			requestCount = 2;
			requestErrorCount = 2;
		}
		assertThat(doOnRequest.get()).isEqualTo(requestCount);
		assertThat(doOnRequestError.get()).isEqualTo(requestErrorCount);
		assertThat(doOnResponseError.get()).isEqualTo(0);

		server.close();
		assertThat(serverFuture.get()).isNull();
		threadPool.shutdown();
		assertThat(threadPool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	private static final class ConnectionResetByPeerServer extends CountDownLatch implements Runnable {
		final int port;
		private final ServerSocketChannel server;
		private volatile Thread thread;

		private ConnectionResetByPeerServer(int port) {
			super(1);
			this.port = port;
			try {
				server = ServerSocketChannel.open();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void run() {
			try {
				server.configureBlocking(true);
				server.socket()
				      .bind(new InetSocketAddress(port));
				countDown();
				thread = Thread.currentThread();
				while (true) {
					SocketChannel ch = server.accept();

					ByteBuffer buffer = ByteBuffer.allocate(1);
					int read = ch.read(buffer);
					if (read > 0) {
						buffer.flip();
					}

					ch.write(buffer);

					ch.close();
				}
			}
			catch (Exception e) {
				// Server closed
			}
		}

		public void close() throws IOException {
			Thread thread = this.thread;
			if (thread != null) {
				thread.interrupt();
			}
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	@Test
	void testIssue600_1() {
		doTestIssue600(true);
	}

	@Test
	void testIssue600_2() {
		doTestIssue600(false);
	}

	private void doTestIssue600(boolean withLoop) {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.send(req.receive()
				                                            .transferOwnership()
				                                            .delaySubscription(Duration.ofSeconds(1))))
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("doTestIssue600", 10);
		LoopResources loop = LoopResources.create("test", 4, true);
		HttpClient client;
		if (withLoop) {
			client = createHttpClientForContextWithAddress(pool)
			            .runOn(loop);
		}
		else {
			client = createHttpClientForContextWithAddress(pool);
		}

		Set<String> threadNames = new ConcurrentSkipListSet<>();
		StepVerifier.create(
				Flux.range(1, 4)
				    .flatMap(i -> client.request(HttpMethod.GET)
				                        .uri("/")
				                        .send((req, out) -> out.send(Flux.empty()))
				                        .responseContent()
				                        .doFinally(s -> threadNames.add(Thread.currentThread().getName()))))
 		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		pool.dispose();
		loop.dispose();

		assertThat(threadNames.size()).isGreaterThan(1);
	}

	@Test
	void testChannelGroupClosesAllConnections() throws Exception {
		disposableServer =
				createServer()
				          .route(r -> r.get("/never",
				                  (req, res) -> res.sendString(Mono.never()))
				              .get("/delay10",
				                  (req, res) -> res.sendString(Mono.just("test")
				                                                   .delayElement(Duration.ofSeconds(10))))
				              .get("/delay1",
				                  (req, res) -> res.sendString(Mono.just("test")
				                                                   .delayElement(Duration.ofSeconds(1)))))
				          .bindNow(Duration.ofSeconds(30));

		ConnectionProvider connectionProvider =
				ConnectionProvider.create("testChannelGroupClosesAllConnections", Integer.MAX_VALUE);

		ChannelGroup group = new DefaultChannelGroup(executor);

		CountDownLatch latch1 = new CountDownLatch(3);
		CountDownLatch latch2 = new CountDownLatch(3);

		HttpClient client = createHttpClientForContextWithAddress(connectionProvider);

		Flux.just("/never", "/delay10", "/delay1")
		    .flatMap(s ->
		            client.doOnConnected(c -> {
		                          c.onDispose()
		                           .subscribe(null, null, latch2::countDown);
		                          group.add(c.channel());
		                          latch1.countDown();
		                      })
		                  .get()
		                  .uri(s)
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		    .subscribe();

		assertThat(latch1.await(30, TimeUnit.SECONDS)).isTrue();

		Mono.whenDelayError(FutureMono.from(group.close()), connectionProvider.disposeLater())
		    .block(Duration.ofSeconds(30));

		assertThat(latch2.await(30, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void testIssue614() {
		disposableServer =
				createServer()
				          .route(routes ->
				              routes.post("/dump", (req, res) -> {
				                  if (req.requestHeaders().contains("Transfer-Encoding")) {
				                      return Mono.error(new Exception("Transfer-Encoding is not expected"));
				                  }
				                  return res.sendString(Mono.just("OK"));
				              }))
				          .bindNow();

		StepVerifier.create(
				createHttpClientForContextWithAddress()
				        .post()
				        .uri("/dump")
				        .sendForm((req, form) -> form.attr("attribute", "value"))
				        .responseContent()
				        .aggregate()
				        .asString())
				    .expectNext("OK")
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue632() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) ->
				              res.header(HttpHeaderNames.CONNECTION,
				                         HttpHeaderValues.UPGRADE + ", " + HttpHeaderValues.CLOSE))
				          .bindNow();
		assertThat(disposableServer).isNotNull();

		CountDownLatch latch = new CountDownLatch(1);
		createHttpClientForContextWithPort()
		        .doOnConnected(conn ->
		                conn.channel()
		                    .closeFuture()
		                    .addListener(future -> latch.countDown()))
		        .get()
		        .uri("/")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void testIssue694() {
		disposableServer =
				createServer()
				          .handle((req, res) -> {
				              req.receive()
				                 .subscribe();
				              return Mono.empty();
				          })
				          .bindNow();

		HttpClient client = createHttpClientForContextWithPort();

		BufferAllocator alloc = preferredAllocator();

		try (Buffer buffer1 = alloc.allocate(16).writeInt(1).makeReadOnly()) {
			client.request(HttpMethod.GET)
			      .send((req, out) -> out.send(Flux.range(0, 10)
			                                             .map(i -> buffer1.copy(0, buffer1.readableBytes(), true))))
			      .response()
			      .block(Duration.ofSeconds(30));
		}

		try (Buffer buffer2 = alloc.allocate(16).writeInt(1).makeReadOnly()) {
			client.request(HttpMethod.GET)
			      .send(Flux.range(0, 10)
			                .map(i -> buffer2.copy(0, buffer2.readableBytes(), true)))
			      .response()
			      .block(Duration.ofSeconds(30));
		}
	}

	@Test
	void testIssue700AndIssue876() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				                  res.sendString(Flux.range(0, 10)
				                                     .map(i -> "test")
				                                     .delayElements(Duration.ofMillis(4))))
				          .bindNow();

		HttpClient client = createHttpClientForContextWithAddress();
		for (int i = 0; i < 1000; ++i) {
			try {
				client.get()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .timeout(Duration.ofMillis(ThreadLocalRandom.current().nextInt(1, 35)))
				      .block(Duration.ofMillis(100));
			}
			catch (Throwable t) {
				// ignore
			}
		}

		System.gc();
		for (int i = 0; i < 100000; ++i) {
			@SuppressWarnings("UnusedVariable")
			int[] arr = new int[100000];
		}
		System.gc();
	}

	@Test
	void httpClientResponseConfigInjectAttributes() {
		AtomicReference<Channel> channelRef = new AtomicReference<>();
		AtomicBoolean validate = new AtomicBoolean();
		AtomicBoolean allowDuplicateContentLengths = new AtomicBoolean();
		disposableServer =
				createServer()
				          .handle((req, resp) -> req.receive()
				                                    .then(resp.sendNotFound()))
				          .bindNow();

		createHttpClientForContextWithAddress()
		        .httpResponseDecoder(opt -> opt.maxInitialLineLength(123)
		                                       .maxHeaderSize(456)
		                                       .validateHeaders(false)
		                                       .initialBufferSize(10)
		                                       .failOnMissingResponse(true)
		                                       .parseHttpAfterConnectRequest(true)
		                                       .allowDuplicateContentLengths(true))
		        .doOnConnected(c -> {
		                    channelRef.set(c.channel());
		                    HttpClientCodec codec = c.channel()
		                                             .pipeline()
		                                             .get(HttpClientCodec.class);
		                    HttpObjectDecoder decoder = (HttpObjectDecoder) getValueReflection(codec, "inboundHandler", 1);
		                    validate.set((Boolean) getValueReflection(decoder, "validateHeaders", 2));
		                    allowDuplicateContentLengths.set((Boolean) getValueReflection(decoder, "allowDuplicateContentLengths", 2));
		                })
		        .post()
		        .uri("/")
		        .send(BufferFlux.fromString(Mono.just("bodysample")))
		        .responseContent()
		        .aggregate()
		        .asString()
		        .block(Duration.ofSeconds(30));

		assertThat(channelRef.get()).isNotNull();
		assertThat(validate).as("validate headers").isFalse();
		assertThat(allowDuplicateContentLengths).as("allow duplicate Content-Length").isTrue();
	}

	private Object getValueReflection(Object obj, String fieldName, int superLevel) {
		try {
			Field field;
			if (superLevel == 1) {
				field = obj.getClass()
				           .getSuperclass()
				           .getDeclaredField(fieldName);
			}
			else {
				field = obj.getClass()
				           .getSuperclass()
				           .getSuperclass()
				           .getDeclaredField(fieldName);
			}
			field.setAccessible(true);
			return field.get(obj);
		}
		catch (NoSuchFieldException | IllegalAccessException e) {
			return new RuntimeException(e);
		}
	}

	@Test
	void testDoOnRequestInvokedBeforeSendingRequest() {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.send(req.receive().transferOwnership()))
				          .bindNow();

		StepVerifier.create(
		        createHttpClientForContextWithAddress()
		                  .doOnRequest((req, con) -> req.header("test", "test"))
		                  .post()
		                  .uri("/")
		                  .send((req, out) -> {
		                      CharSequence header = req.requestHeaders().get("test");
		                      if (header != null) {
		                          return out.sendString(Flux.just("FOUND"));
		                      }
		                      else {
		                          return out.sendString(Flux.just("NOT_FOUND"));
		                      }
		                  })
		                  .responseSingle((res, bytes) -> bytes.asString()))
		            .expectNext("FOUND")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue719_TEWithTextNoSSL() {
		doTestIssue719(BufferFlux.fromString(Mono.just("test")),
				h -> h.set("Transfer-Encoding", "chunked"), false);
	}

	@Test
	void testIssue719_CLWithTextNoSSL() {
		doTestIssue719(BufferFlux.fromString(Mono.just("test")),
				h -> h.set("Content-Length", "4"), false);
	}

	@Test
	void testIssue719_TENoTextNoSSL() {
		doTestIssue719(BufferFlux.fromString(Mono.just("")),
				h -> h.set("Transfer-Encoding", "chunked"), false);
	}

	@Test
	void testIssue719_CLNoTextNoSSL() {
		doTestIssue719(BufferFlux.fromString(Mono.just("")),
				h -> h.set("Content-Length", "0"), false);
	}

	@Test
	void testIssue719_TEWithTextWithSSL() {
		doTestIssue719(BufferFlux.fromString(Mono.just("test")),
				h -> h.set("Transfer-Encoding", "chunked"), true);
	}

	@Test
	void testIssue719_CLWithTextWithSSL() {
		doTestIssue719(BufferFlux.fromString(Mono.just("test")),
				h -> h.set("Content-Length", "4"), true);
	}

	@Test
	void testIssue719_TENoTextWithSSL() {
		doTestIssue719(BufferFlux.fromString(Mono.just("")),
				h -> h.set("Transfer-Encoding", "chunked"), true);
	}

	@Test
	void testIssue719_CLNoTextWithSSL() {
		doTestIssue719(BufferFlux.fromString(Mono.just("")),
				h -> h.set("Content-Length", "0"), true);
	}

	@SuppressWarnings("deprecation")
	private void doTestIssue719(Publisher<Buffer> clientSend,
			Consumer<HttpHeaders> clientSendHeaders, boolean ssl) {
		HttpServer server =
				createServer()
				          .handle((req, res) -> req.receive()
				                                   .then(res.sendString(Mono.just("test"))
				                                            .then()));

		if (ssl) {
			server = server.secure(spec -> spec.sslContext(
					Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())));
		}

		disposableServer = server.bindNow();

		HttpClient client = createHttpClientForContextWithAddress();
		if (ssl) {
			client = client.secure(spec ->
					spec.sslContext(
							Http11SslContextSpec.forClient()
							                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE))));
		}

		StepVerifier.create(
				client.headers(clientSendHeaders)
				      .post()
				      .uri("/")
				      .send(clientSend)
				      .responseContent()
				      .aggregate()
				      .asString())
		            .expectNext("test")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(
				client.headers(clientSendHeaders)
				      .post()
				      .uri("/")
				      .send(clientSend)
				      .responseContent()
				      .aggregate()
				      .asString())
		            .expectNext("test")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue777() {
		disposableServer = createServer()
		                             .route(r ->
		                                 r.post("/empty", (req, res) -> {
		                                     // Just consume the incoming body
		                                     req.receive().subscribe();
		                                     return res.status(400)
		                                               .header(HttpHeaderNames.CONNECTION, "close")
		                                               .send(Mono.empty());
		                                  })
		                                  .post("/test", (req, res) -> {
		                                      // Just consume the incoming body
		                                      req.receive().subscribe();
		                                      return res.status(400)
		                                                .header(HttpHeaderNames.CONNECTION, "close")
		                                                .sendString(Mono.just("Test"));
		                                  }))
		                             .bindNow();

		HttpClient client = createHttpClientForContextWithAddress();

		BiFunction<HttpClientResponse, BufferMono, Mono<String>> receiver =
				(resp, bytes) -> {
					if (!Objects.equals(HttpResponseStatus.OK, resp.status())) {
						return bytes.asString()
						            .switchIfEmpty(Mono.just(resp.status().reasonPhrase()))
						            .flatMap(text -> Mono.error(new RuntimeException(text)));
					}
					return bytes.asString();
				};
		doTestIssue777_1(client, "/empty", "Bad Request", receiver);
		doTestIssue777_1(client, "/test", "Test", receiver);

		receiver = (resp, bytes) -> {
			if (Objects.equals(HttpResponseStatus.OK, resp.status())) {
				return bytes.asString();
			}
			return Mono.error(new RuntimeException("error"));
		};
		doTestIssue777_1(client, "/empty", "error", receiver);
		doTestIssue777_1(client, "/test", "error", receiver);

		BiFunction<HttpClientResponse, BufferMono, Mono<Tuple2<String, HttpClientResponse>>> receiver1 =
				(resp, buffer) ->
						Mono.zip(buffer.asString(StandardCharsets.UTF_8)
						               .switchIfEmpty(Mono.just(resp.status().reasonPhrase())),
						         Mono.just(resp));
		doTestIssue777_2(client, "/empty", "Bad Request", receiver1);
		doTestIssue777_2(client, "/test", "Test", receiver1);

		receiver =
				(resp, bytes) -> bytes.asString(StandardCharsets.UTF_8)
				                      .switchIfEmpty(Mono.just(resp.status().reasonPhrase()))
				                      .map(respBody -> {
				                          if (!Objects.equals(HttpResponseStatus.OK, resp.status())) {
				                              throw new RuntimeException(respBody);
				                          }
				                          return respBody;
				                      });
		doTestIssue777_1(client, "/empty", "Bad Request", receiver);
		doTestIssue777_1(client, "/test", "Test", receiver);
	}

	private void doTestIssue777_1(HttpClient client, String uri, String expectation,
			BiFunction<? super HttpClientResponse, ? super BufferMono, ? extends Mono<String>> receiver) {
		StepVerifier.create(
		        client.post()
		              .uri(uri)
		              .send((req, out) -> out.sendString(Mono.just("Test")))
		              .responseSingle(receiver))
		            .expectErrorMessage(expectation)
		            .verify(Duration.ofSeconds(30));
	}

	private void doTestIssue777_2(HttpClient client, String uri, String expectation,
			BiFunction<? super HttpClientResponse, ? super BufferMono, ? extends Mono<Tuple2<String, HttpClientResponse>>> receiver) {
		StepVerifier.create(
		        client.post()
		              .uri(uri)
		              .send((req, out) -> out.sendString(Mono.just("Test")))
		              .responseSingle(receiver)
		              .map(tuple -> {
		                  if (!Objects.equals(HttpResponseStatus.OK, tuple.getT2().status())) {
		                      throw new RuntimeException(tuple.getT1());
		                  }
		                  return tuple.getT1();
		              }))
		            .expectErrorMessage(expectation)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testConnectionIdleTimeFixedPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionIdleTimeFixedPool")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .maxIdleTime(Duration.ofMillis(10))
				                  .build();
		ChannelId[] ids = doTestConnectionIdleTime(provider);
		assertThat(ids[0]).isNotEqualTo(ids[1]);
	}

	@Test
	void testConnectionIdleTimeElasticPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionIdleTimeElasticPool")
				                  .maxConnections(Integer.MAX_VALUE)
				                  .maxIdleTime(Duration.ofMillis(10))
				                  .build();
		ChannelId[] ids = doTestConnectionIdleTime(provider);
		assertThat(ids[0]).isNotEqualTo(ids[1]);
	}

	@Test
	void testConnectionNoIdleTimeFixedPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionNoIdleTimeFixedPool")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .build();
		ChannelId[] ids = doTestConnectionIdleTime(provider);
		assertThat(ids[0]).isEqualTo(ids[1]);
	}

	@Test
	void testConnectionNoIdleTimeElasticPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.create("testConnectionNoIdleTimeElasticPool", Integer.MAX_VALUE);
		ChannelId[] ids = doTestConnectionIdleTime(provider);
		assertThat(ids[0]).isEqualTo(ids[1]);
	}

	private ChannelId[] doTestConnectionIdleTime(ConnectionProvider provider) throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendString(Mono.just("hello")))
				          .bindNow();

		Flux<ChannelId> id = createHttpClientForContextWithAddress(provider)
		                       .get()
		                       .uri("/")
		                       .responseConnection((res, conn) -> Mono.just(conn.channel().id())
		                                                              .delayUntil(ch -> conn.inbound().receive()));

		ChannelId id1 = id.blockLast(Duration.ofSeconds(30));
		Thread.sleep(30);
		ChannelId id2 = id.blockLast(Duration.ofSeconds(30));

		assertThat(id1).isNotNull();
		assertThat(id2).isNotNull();

		provider.dispose();
		return new ChannelId[] {id1, id2};
	}

	@Test
	void testConnectionLifeTimeFixedPoolHttp1() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionLifeTimeFixedPoolHttp1")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .maxLifeTime(Duration.ofMillis(30))
				                  .build();
		try {
			ChannelId[] ids = doTestConnectionLifeTime(createServer(),
					createClient(provider, () -> disposableServer.address()));
			assertThat(ids[0]).isNotEqualTo(ids[1]);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void testConnectionLifeTimeFixedPoolHttp2_1() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionLifeTimeFixedPoolHttp2_1")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .maxLifeTime(Duration.ofMillis(30))
				                  .build();
		try {
			ChannelId[] ids = doTestConnectionLifeTime(
					createServer().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
					createClient(provider, () -> disposableServer.address()).protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)));
			assertThat(ids[0]).isNotEqualTo(ids[1]);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testConnectionLifeTimeElasticPoolHttp1() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionLifeTimeElasticPoolHttp1")
				                  .maxConnections(Integer.MAX_VALUE)
				                  .maxLifeTime(Duration.ofMillis(30))
				                  .build();
		try {
			ChannelId[] ids = doTestConnectionLifeTime(createServer(),
					createClient(provider, () -> disposableServer.address()));
			assertThat(ids[0]).isNotEqualTo(ids[1]);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void testConnectionLifeTimeElasticPoolHttp2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionLifeTimeElasticPoolHttp2")
				                  .maxConnections(Integer.MAX_VALUE)
				                  .maxLifeTime(Duration.ofMillis(30))
				                  .build();
		try {
			ChannelId[] ids = doTestConnectionLifeTime(
					createServer().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
					createClient(provider, () -> disposableServer.address()).protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)));
			assertThat(ids[0]).isNotEqualTo(ids[1]);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testConnectionNoLifeTimeFixedPoolHttp1() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionNoLifeTimeFixedPoolHttp1")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .build();
		try {
			ChannelId[] ids = doTestConnectionLifeTime(createServer(),
					createClient(provider, () -> disposableServer.address()));
			assertThat(ids[0]).isEqualTo(ids[1]);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void testConnectionNoLifeTimeFixedPoolHttp2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionNoLifeTimeFixedPoolHttp2")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .build();
		try {
			ChannelId[] ids = doTestConnectionLifeTime(
					createServer().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
					createClient(provider, () -> disposableServer.address()).protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)));
			assertThat(ids[0]).isEqualTo(ids[1]);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testConnectionNoLifeTimeElasticPoolHttp1() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.create("testConnectionNoLifeTimeElasticPoolHttp1", Integer.MAX_VALUE);
		try {
			ChannelId[] ids = doTestConnectionLifeTime(createServer(),
					createClient(provider, () -> disposableServer.address()));
			assertThat(ids[0]).isEqualTo(ids[1]);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void testConnectionNoLifeTimeElasticPoolHttp2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		ConnectionProvider provider =
				ConnectionProvider.create("testConnectionNoLifeTimeElasticPoolHttp2", Integer.MAX_VALUE);
		try {
			ChannelId[] ids = doTestConnectionLifeTime(
					createServer().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
					createClient(provider, () -> disposableServer.address()).protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)));
			assertThat(ids[0]).isEqualTo(ids[1]);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	private ChannelId[] doTestConnectionLifeTime(HttpServer server, HttpClient client) throws Exception {
		disposableServer =
				server.handle((req, resp) ->
				          resp.sendObject(BufferFlux.fromString(Mono.delay(Duration.ofMillis(30))
				                                                     .map(Objects::toString))))
				      .bindNow();

		Flux<ChannelId> id = client.get()
		                           .uri("/")
		                           .responseConnection((res, conn) -> {
		                               Channel channel = !client.configuration().checkProtocol(HttpClientConfig.h2) ?
		                                   conn.channel() : conn.channel().parent();
		                               return Mono.just(channel.id())
		                                          .delayUntil(ch -> conn.inbound().receive());
		                           });

		ChannelId id1 = id.blockLast(Duration.ofSeconds(30));
		Thread.sleep(10);
		ChannelId id2 = id.blockLast(Duration.ofSeconds(30));

		assertThat(id1).isNotNull();
		assertThat(id2).isNotNull();

		return new ChannelId[] {id1, id2};
	}

	@Test
	@SuppressWarnings("deprecation")
	void testConnectionLifeTimeFixedPoolHttp2_2() {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		disposableServer =
				createServer()
				        .protocol(HttpProtocol.H2)
				        .secure(spec -> spec.sslContext(serverCtx))
				        .http2Settings(setting -> setting.maxConcurrentStreams(2))
				        .handle((req, resp) ->
				            resp.sendObject(BufferFlux.fromString(Mono.delay(Duration.ofMillis(30))
				                                                       .map(Objects::toString))))
				        .bindNow();

		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionLifeTimeFixedPoolHttp2_2")
				                  .maxConnections(1)
				                  .maxLifeTime(Duration.ofMillis(30))
				                  .build();

		HttpClient client =
				createClient(provider, () -> disposableServer.address())
				        .protocol(HttpProtocol.H2)
				        .secure(spec -> spec.sslContext(clientCtx));

		Flux<ChannelId> id = client.get()
		                           .uri("/")
		                           .responseConnection((res, conn) ->
		                               Mono.just(conn.channel().parent().id())
		                                   .delayUntil(ch -> conn.inbound().receive()));
		try {
			//warmup
			id.blockLast(Duration.ofSeconds(5));

			List<ChannelId> ids =
					Flux.range(0, 3)
					    .flatMap(i -> id)
					    .collectList()
					    .block(Duration.ofSeconds(5));

			assertThat(ids).isNotNull().hasSize(3);

			assertThat(ids.get(0)).isEqualTo(ids.get(1));
			assertThat(ids.get(0)).isNotEqualTo(ids.get(2));
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testResourceUrlSetInResponse() {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.send())
				          .bindNow();

		final String requestUri = "http://localhost:" + disposableServer.port() + "/foo";
		StepVerifier.create(
		        createHttpClientForContextWithAddress()
		                .get()
		                .uri(requestUri)
		                .responseConnection((res, conn) -> Mono.justOrEmpty(res.resourceUrl())))
		            .expectNext(requestUri)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue975() throws Exception {
		disposableServer =
				createServer()
				          .route(routes ->
				              routes.get("/dispose",
				                  (req, res) -> res.sendString(
				                      Flux.range(0, 10_000)
				                          .map(i -> {
				                              if (i == 1_000) {
				                                  res.withConnection(Connection::disposeNow);
				                              }
				                              return "a";
				                          }))))
				          .bindNow();

		AtomicBoolean doAfterResponseSuccess = new AtomicBoolean();
		AtomicBoolean doOnResponseError = new AtomicBoolean();
		CountDownLatch latch = new CountDownLatch(1);
		HttpClient.create()
		          .doAfterResponseSuccess((resp, conn) -> doAfterResponseSuccess.set(true))
		          .doOnResponseError((resp, exc) -> doOnResponseError.set(true))
		          .get()
		          .uri("http://localhost:" + disposableServer.port() + "/dispose")
		          .responseSingle((resp, bytes) -> bytes.asString())
		          .subscribe(null, t -> latch.countDown());

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(doAfterResponseSuccess.get()).isFalse();
		assertThat(doOnResponseError.get()).isTrue();
	}

	@Test
	void testIssue988() {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .bindNow(Duration.ofSeconds(30));

		ConnectionProvider provider = ConnectionProvider.create("testIssue988", 1);
		HttpClient client =
				createHttpClientForContextWithAddress(provider)
				        .wiretap("testIssue988", LogLevel.INFO)
				        .metrics(true, Function.identity());

		AtomicReference<Channel> ch1 = new AtomicReference<>();
		StepVerifier.create(client.doOnConnected(c -> ch1.set(c.channel()))
				                  .get()
				                  .uri("/1")
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch2 = new AtomicReference<>();
		StepVerifier.create(client.doOnConnected(c -> ch2.set(c.channel()))
				                  .post()
				                  .uri("/2")
				                  .send(BufferFlux.fromString(Mono.just("test")))
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch3 = new AtomicReference<>();
		StepVerifier.create(
				client.doOnConnected(c -> ch3.set(c.channel()))
				      .wiretap("testIssue988", LogLevel.ERROR)
				      .post()
				      .uri("/3")
				      .responseContent()
				      .aggregate()
				      .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		assertThat(ch1.get()).isSameAs(ch2.get());
		assertThat(ch1.get()).isNotSameAs(ch3.get());

		provider.dispose();
	}

	@Test
	void testDoAfterResponseSuccessDisposeConnection() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendString(Flux.just("test", "test", "test")))
				          .bindNow(Duration.ofSeconds(30));

		CountDownLatch latch = new CountDownLatch(1);
		StepVerifier.create(
		        createHttpClientForContextWithPort()
		                .doAfterResponseSuccess((res, conn) -> {
		                    conn.onDispose()
		                        .subscribe(null, null, latch::countDown);
		                    conn.dispose();
		                })
		                .get()
		                .uri("/")
		                .responseContent()
		                .aggregate()
		                .asString())
		            .expectNext("testtesttest")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void testHttpClientWithDomainSocketsWithHost() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> createClient(() -> new DomainSocketAddress("/tmp/test.sock"))
		                                    .host("localhost")
		                                    .get()
		                                    .uri("/")
		                                    .responseContent()
		                                    .aggregate()
		                                    .block(Duration.ofSeconds(30)));
	}

	@Test
	void testHttpClientWithDomainSocketsWithPort() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> createClient(() -> new DomainSocketAddress("/tmp/test.sock"))
		                                    .port(1234)
		                                    .get()
		                                    .uri("/")
		                                    .responseContent()
		                                    .aggregate()
		                                    .block(Duration.ofSeconds(30)));
	}

	@Test
	void testUriNotAbsolute_1() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> HttpClient.create()
		                                    .get()
		                                    .uri(new URI("/")));
	}

	@Test
	void testUriNotAbsolute_2() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> HttpClient.create()
		                                    .websocket()
		                                    .uri(new URI("/")));
	}

	@Test
	void testUriWhenFailedRequest_1() throws Exception {
		doTestUriWhenFailedRequest(false);
	}

	@Test
	void testUriWhenFailedRequest_2() throws Exception {
		doTestUriWhenFailedRequest(true);
	}

	private void doTestUriWhenFailedRequest(boolean useUri) throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) -> {
				              throw new RuntimeException("doTestUriWhenFailedRequest");
				          })
				          .bindNow(Duration.ofSeconds(30));

		AtomicReference<String> uriFailedRequest = new AtomicReference<>();
		HttpClient client = createHttpClientForContextWithPort()
				          .doOnRequestError((req, t) -> uriFailedRequest.set(req.uri()));

		String uri = "http://localhost:" + disposableServer.port() + "/";
		if (useUri) {
			StepVerifier.create(client.get()
			                          .uri(new URI(uri))
			                          .responseContent())
			            .expectError()
			            .verify(Duration.ofSeconds(30));
		}
		else {
			StepVerifier.create(client.get()
			                          .uri(uri)
			                          .responseContent())
			            .expectError()
			            .verify(Duration.ofSeconds(30));
		}

		assertThat(uriFailedRequest.get()).isNotNull();
		assertThat(uriFailedRequest.get()).isEqualTo(uri);
	}

	@Test
	void testIssue1133() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendString(Mono.just("testIssue1133")))
				          .bindNow(Duration.ofSeconds(30));

		StepVerifier.create(createHttpClientForContextWithPort()
		                              .get()
		                              .uri(new URI("http://localhost:" + disposableServer.port() + "/"))
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("testIssue1133")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue1031() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/2"))
				                       .get("/2", (req, res) -> res.status(200)
				                                                   .sendString(Mono.just("OK"))))
				          .bindNow();

		AtomicReference<List<HttpClientInfos>> onRequest = new AtomicReference<>(new ArrayList<>());
		AtomicReference<HttpClientInfos> onRedirect = new AtomicReference<>();
		AtomicReference<HttpClientInfos> onResponse = new AtomicReference<>();
		AtomicReference<HttpClientInfos> onResponseError = new AtomicReference<>();
		Tuple2<String, HttpResponseStatus> response =
				createHttpClientForContextWithAddress()
				          .followRedirect(true, req -> req.addHeader("testIssue1031", "testIssue1031"))
				          .doOnRequest((req, c) -> onRequest.get().add(req))
				          .doOnRedirect((res, c) -> onRedirect.set(res))
				          .doOnResponse((res, c) -> onResponse.set(res))
				          .doOnResponseError((res, t) -> onResponseError.set(res))
				          .get()
				          .uri("/1")
				          .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res.status())))
				          .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.getT1()).isEqualTo("OK");
		assertThat(response.getT2()).isEqualTo(HttpResponseStatus.OK);
		assertThat(onRequest.get().size()).isEqualTo(2);
		String address = "http://" + disposableServer.host() + ":" + disposableServer.port();
		checkExpectationsIssue1031(onRequest.get().get(0), "/1", 0, address + "/1", null);
		checkExpectationsIssue1031(onRequest.get().get(1), "/2", 1, address + "/2", "testIssue1031");
		checkExpectationsIssue1031(onRedirect.get(), "/1", 0, address + "/1", null);
		checkExpectationsIssue1031(onResponse.get(), "/2", 1, address + "/2", "testIssue1031");
		assertThat(onResponseError.get()).isNull();
	}

	private void checkExpectationsIssue1031(HttpClientInfos info, String expectedUri, int expectedRedirections,
			String expectedResourceUri, @Nullable String expectedLocation) {
		assertThat(info).isNotNull();
		assertThat(info.method()).isEqualTo(HttpMethod.GET);
		assertThat(info.uri()).isEqualTo(expectedUri);
		assertThat(info.redirectedFrom().length).isEqualTo(expectedRedirections);
		assertThat(info.resourceUrl()).isEqualTo(expectedResourceUri);
		assertThat(info.requestHeaders().get("testIssue1031")).isEqualTo(expectedLocation);
	}

	@Test
	void testIssue1159() {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendString(Mono.just("testIssue1159")))
				          .bindNow();

		doTestIssue1159(false, 100);
		doTestIssue1159(true, 200);
	}

	private void doTestIssue1159(boolean onHttpRequestLevel, long expectedTimeout) {
		AtomicBoolean onRequest = new AtomicBoolean();
		AtomicBoolean onResponse = new AtomicBoolean();
		AtomicBoolean onDisconnected = new AtomicBoolean();
		AtomicLong timeout = new AtomicLong();
		String response =
				createHttpClientForContextWithAddress()
				        .doOnRequest((req, conn) -> {
				            if (onHttpRequestLevel) {
				                req.responseTimeout(Duration.ofMillis(200));
				            }
				            onRequest.set(conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler) != null);
				        })
				        .doOnResponse((req, conn) -> {
				            ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler);
				            if (handler != null) {
				                onResponse.set(true);
				                timeout.set(((ReadTimeoutHandler) handler).getReaderIdleTimeInMillis());
				            }
				        })
				        .doOnDisconnected(conn ->
				            onDisconnected.set(conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler) != null))
				        .responseTimeout(Duration.ofMillis(100))
				        .post()
				        .uri("/")
				        .responseContent()
				        .aggregate()
				        .asString()
				        .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("testIssue1159");
		assertThat(onRequest.get()).isFalse();
		assertThat(onResponse.get()).isTrue();
		assertThat(onDisconnected.get()).isFalse();
		assertThat(timeout.get()).isEqualTo(expectedTimeout);
	}

	@Test
	void testLoopAndResolver() {
		LoopResources loop = LoopResources.create("testLoopAndResolver");
		HttpClient client =
				HttpClient.create()
				          .wiretap(true);

		StepVerifier.create(client.get()
		                          .uri("https://example.com")
		                          .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.runOn(loop, false)
		                          .get()
		                          .uri("https://example.com")
		                          .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.runOn(loop, false)
		                          .resolver(spec -> spec.trace("reactor.netty5.testLoopAndResolver", LogLevel.DEBUG))
		                          .get()
		                          .uri("https://example.com")
		                          .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		loop.disposeLater()
		    .block(Duration.ofSeconds(30));
	}

	@Test
	void testCustomMetricsRecorderWithUriMapper() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(5);

		disposableServer =
				createServer()
				          .handle((req, resp) -> resp.sendString(Mono.just("OK")))
				          .bindNow();

		List<String> collectedUris = new CopyOnWriteArrayList<>();

		HttpClient.create()
		          .metrics(true,
		              () -> new HttpClientMetricsRecorder() {
		                  @Override
		                  public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		                      collectedUris.add(uri);
		                      latch.countDown();
		                  }

		                  @Override
		                  public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		                      collectedUris.add(uri);
		                      latch.countDown();
		                  }

		                  @Override
		                  public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		                      collectedUris.add(uri);
		                      latch.countDown();
		                  }

		                  @Override
		                  public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		                  }

		                  @Override
		                  public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		                  }

		                  @Override
		                  public void incrementErrorsCount(SocketAddress remoteAddress) {
		                  }

		                  @Override
		                  public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		                  }

		                  @Override
		                  public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		                  }

		                  @Override
		                  public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		                  }

		                  @Override
		                  public void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		                      collectedUris.add(uri);
		                      latch.countDown();
		                  }

		                  @Override
		                  public void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time) {
		                      collectedUris.add(uri);
		                      latch.countDown();
		                  }

		                  @Override
		                  public void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		                      collectedUris.add(uri);
		                      latch.countDown();
		                  }
		              },
		              s -> s.startsWith("/stream/") ? "/stream/{n}" : s)
		          .get()
		          .uri("http://localhost:" + disposableServer.port() + "/stream/1024")
		          .responseContent()
		          .aggregate()
		          .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(collectedUris).isNotEmpty()
		                         .containsOnly("/stream/{n}");
	}

	@Test
	void testNoEvictInBackground() throws Exception {
		doTestEvictInBackground(1, false);
	}

	@Test
	void testEvictInBackground() throws Exception {
		doTestEvictInBackground(0, true);
	}

	private void doTestEvictInBackground(int expectation, boolean evict) throws Exception {
		TestMeterRegistrar registrar = new TestMeterRegistrar();

		ConnectionProvider.Builder builder =
				ConnectionProvider.builder("testEvictInBackground")
				                  .maxConnections(1)
				                  .maxIdleTime(Duration.ofMillis(20))
				                  .metrics(true, () -> registrar);

		if (evict) {
			builder.evictInBackground(Duration.ofMillis(50));
		}

		disposableServer =
				createServer()
				          .handle((req, resp) -> resp.sendString(Mono.just("testEvictInBackground")))
				          .bindNow();

		createHttpClientForContextWithAddress(builder.build())
		        .get()
		        .uri("/")
		        .responseContent()
		        .aggregate()
		        .block(Duration.ofSeconds(30));

		Thread.sleep(200);

		assertThat(registrar.m.get()).isNotNull();
		assertThat(registrar.m.get().idleSize()).isEqualTo(expectation);
	}

	@Test
	void testIssue1478() throws Exception {
		disposableServer =
				HttpServer.create()
				          .handle((req, res) -> res.addHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
				                                   .status(HttpResponseStatus.BAD_REQUEST)
				                                   .send(req.receive()
				                                            .transferOwnership()
				                                            .next()))
				          .bindNow();

		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());

		Path largeFileParent = largeFile.getParent();
		assertThat(largeFileParent).isNotNull();

		Path tempFile = Files.createTempFile(largeFileParent, "temp", ".txt");
		tempFile.toFile().deleteOnExit();

		byte[] fileBytes = Files.readAllBytes(largeFile);
		for (int i = 0; i < 1000; i++) {
			Files.write(tempFile, fileBytes, StandardOpenOption.APPEND);
		}

		HttpClient.create()
		          .port(disposableServer.port())
		          .post()
		          .send((req, out) -> out.sendFile(tempFile))
		          .responseSingle((res, bytes) -> Mono.just(res.status().code()))
		          .as(StepVerifier::create)
		          .expectNext(400)
		          .expectComplete()
		          .verify(Duration.ofSeconds(5));
	}

	@Test
	void testConfigurationSecurityThenProtocols_DefaultHTTP11SslProvider() {
		HttpClient client = HttpClient.create().secure();

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.HTTP11),
				HttpClientSecure.DEFAULT_HTTP_SSL_PROVIDER);

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C),
				HttpClientSecure.DEFAULT_HTTP_SSL_PROVIDER);
	}

	@Test
	void testConfigurationSecurityThenProtocols_DefaultH2SslProvider() {
		HttpClient client = HttpClient.create().secure();

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.HTTP11, HttpProtocol.H2),
				HttpClientSecure.DEFAULT_HTTP2_SSL_PROVIDER);

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.HTTP11, HttpProtocol.H2, HttpProtocol.H2C),
				HttpClientSecure.DEFAULT_HTTP2_SSL_PROVIDER);

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.H2),
				HttpClientSecure.DEFAULT_HTTP2_SSL_PROVIDER);

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.H2, HttpProtocol.H2C),
				HttpClientSecure.DEFAULT_HTTP2_SSL_PROVIDER);
	}

	@Test
	void testConfigurationOnlyProtocols_NoDefaultSslProvider() {
		HttpClient client = HttpClient.create();

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.HTTP11), null);

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C), null);

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.HTTP11, HttpProtocol.H2), null);

		doTestProtocolsAndDefaultSslProviderAvailability(
				client.protocol(HttpProtocol.HTTP11, HttpProtocol.H2, HttpProtocol.H2C), null);

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.H2), null);

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.H2, HttpProtocol.H2C), null);

		doTestProtocolsAndDefaultSslProviderAvailability(client.protocol(HttpProtocol.H2C), null);
	}

	private void doTestProtocolsAndDefaultSslProviderAvailability(HttpClient client, @Nullable SslProvider sslProvider) {
		assertThat(client.configuration().sslProvider()).isSameAs(sslProvider);
	}

	@Test
	void testSameNameResolver_WithConnectionPoolNoMetrics() {
		doTestSameNameResolver(true, false);
	}

	@Test
	void testSameNameResolver_WithConnectionPoolWithMetrics() {
		doTestSameNameResolver(true, true);
	}

	@Test
	void testSameNameResolver_NoConnectionPoolNoMetrics() {
		doTestSameNameResolver(false, false);
	}

	@Test
	void testSameNameResolver_NoConnectionPoolWithMetrics() {
		doTestSameNameResolver(false, true);
	}

	private void doTestSameNameResolver(boolean useConnectionPool, boolean enableMetrics) {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("doTestSameNameResolver")))
				        .bindNow();

		int port = disposableServer.port();
		AtomicReference<List<AddressResolverGroup<?>>> resolvers = new AtomicReference<>(new ArrayList<>());
		Flux.range(0, 2)
		    .flatMap(i -> {
		        // HttpClient creation multiple times is deliberate
		        HttpClient client = useConnectionPool ? createClient(port) : createClientNewConnection(port);
		        return client.metrics(enableMetrics, Function.identity())
		                     .doOnConnect(config -> resolvers.get().add(config.resolverInternal()))
		                     .get()
		                     .uri("/")
		                     .responseContent()
		                     .aggregate()
		                     .asString();
		    })
		    .as(StepVerifier::create)
		    .expectNext("doTestSameNameResolver", "doTestSameNameResolver")
		    .expectComplete()
		    .verify(Duration.ofSeconds(5));

		assertThat(resolvers.get()).isNotNull();
		assertThat(resolvers.get().get(0)).isSameAs(resolvers.get().get(1));
	}

	@Test
	void testIssue1547() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("testIssue1547")))
				        .bindNow();

		EventLoopGroup loop = new MultithreadEventLoopGroup(1, NioHandler.newFactory());
		AtomicReference<List<AddressResolverGroup<?>>> resolvers = new AtomicReference<>(new ArrayList<>());
		AtomicReference<List<AddressResolverGroup<?>>> resolversInternal = new AtomicReference<>(new ArrayList<>());
		try {
			HttpClient client = createClientNewConnection(disposableServer.port()).runOn(useNative -> loop);

			Flux.range(0, 2)
			    .flatMap(i -> client.metrics(true, Function.identity())
			                        .doOnConnect(config -> {
			                            resolvers.get().add(config.resolver());
			                            resolversInternal.get().add(config.resolverInternal());
			                        })
			                       .get()
			                       .uri("/")
			                       .responseContent()
			                       .aggregate()
			                       .asString())
			    .as(StepVerifier::create)
			    .expectNext("testIssue1547", "testIssue1547")
			    .expectComplete()
			    .verify(Duration.ofSeconds(5));

			assertThat(resolvers.get()).isNotNull();
			assertThat(resolvers.get().get(0))
					.isSameAs(resolvers.get().get(1))
					.isInstanceOf(DnsAddressResolverGroup.class);

			assertThat(resolversInternal.get()).isNotNull();
			assertThat(resolversInternal.get().get(0)).isSameAs(resolversInternal.get().get(1));
			assertThat(resolversInternal.get().get(0).getClass().getSimpleName()).isEqualTo("MicrometerAddressResolverGroupMetrics");
		}
		finally {
			// Closing the executor cleans the AddressResolverGroup internal structures and closes the resolver
			loop.shutdownGracefully()
			    .asStage().get(500, TimeUnit.SECONDS);
		}

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() ->
						resolvers.get()
						         .get(0)
						         .getResolver(loop.next()))
				.withMessage("executor not accepting a task");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() ->
						resolversInternal.get()
						                 .get(0)
						                 .getResolver(loop.next()))
				.withMessage("executor not accepting a task");
	}

	@Test
	void testCustomUserAgentHeaderPreserved() {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just(req.requestHeaders()
				                                                          .get(HttpHeaderNames.USER_AGENT, "").toString())))
				        .bindNow();

		HttpClient client = createClient(disposableServer.port());
		Flux.just("User-Agent", "user-agent")
		    .flatMap(s -> client.headers(h -> h.set(s, "custom"))
		                        .get()
		                        .responseContent()
		                        .aggregate()
		                        .asString())
		    .collectList()
		    .as(StepVerifier::create)
		    .expectNext(Arrays.asList("custom", "custom"))
		    .expectComplete()
		    .verify(Duration.ofSeconds(5));
	}

	@Test
	void testCustomHandlerAddedOnChannelInitAlwaysAvailable() {
		doTestCustomHandlerAddedOnCallbackAlwaysAvailable(
				client -> client.doOnChannelInit((observer, channel, address) ->
						Connection.from(channel).addHandlerLast("custom", new ChannelHandlerAdapter(){})));
	}

	@Test
	void testCustomHandlerAddedOnChannelConnectedAlwaysAvailable() {
		doTestCustomHandlerAddedOnCallbackAlwaysAvailable(
				client -> client.doOnConnected(conn -> conn.addHandlerLast("custom", new ChannelHandlerAdapter(){})));
	}

	private void doTestCustomHandlerAddedOnCallbackAlwaysAvailable(Function<HttpClient, HttpClient> customizer) {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("testCustomHandlerAddedOnCallback")))
				        .bindNow();

		ConnectionProvider provider = ConnectionProvider.create("testCustomHandlerAddedOnCallback", 1);
		AtomicBoolean handlerExists = new AtomicBoolean();
		HttpClient client = customizer.apply(
				createHttpClientForContextWithPort(provider)
				        .doOnRequest((req, conn) -> handlerExists.set(conn.channel().pipeline().get("custom") != null)));
		try {
			Flux.range(0, 2)
			    .flatMap(i -> client.get()
			                        .uri("/")
			                        .responseContent()
			                        .aggregate()
			                        .asString())
			    .collectList()
			    .as(StepVerifier::create)
			    .expectNext(Arrays.asList("testCustomHandlerAddedOnCallback", "testCustomHandlerAddedOnCallback"))
			    .expectComplete()
			    .verify(Duration.ofSeconds(5));

			assertThat(handlerExists).isTrue();
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testIssue1697() {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("testIssue1697")))
				        .bindNow();

		AtomicBoolean onRequest = new AtomicBoolean();
		AtomicBoolean onResponse = new AtomicBoolean();
		AtomicBoolean onDisconnected = new AtomicBoolean();
		HttpClient client =
				createHttpClientForContextWithAddress()
				        .doOnRequest((req, conn) ->
				            onRequest.set(conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler) != null))
				        .doOnResponse((req, conn) ->
				            onResponse.set(conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler) != null))
				        .doOnDisconnected(conn ->
				            onDisconnected.set(conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler) != null))
						.responseTimeout(Duration.ofMillis(100));

		doTestIssue1697(client, true, onRequest, onResponse, onDisconnected);
		doTestIssue1697(client.responseTimeout(null), false, onRequest, onResponse, onDisconnected);
	}

	private void doTestIssue1697(HttpClient client, boolean hasTimeout, AtomicBoolean onRequest,
			AtomicBoolean onResponse, AtomicBoolean onDisconnected) {
		String response =
				client.post()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block(Duration.ofSeconds(10));

		assertThat(response).isEqualTo("testIssue1697");
		assertThat(onRequest.get()).isFalse();
		if (hasTimeout) {
			assertThat(onResponse.get()).isTrue();
		}
		else {
			assertThat(onResponse.get()).isFalse();
		}
		assertThat(onDisconnected.get()).isFalse();
	}

	@Test
	public void testSharedNameResolver_SharedClientWithConnectionPool() throws InterruptedException {
		doTestSharedNameResolver(HttpClient.create(), true);
	}

	@Test
	public void testSharedNameResolver_SharedClientNoConnectionPool() throws InterruptedException {
		doTestSharedNameResolver(HttpClient.newConnection(), true);
	}

	@Test
	public void testSharedNameResolver_NotSharedClientWithConnectionPool() throws InterruptedException {
		doTestSharedNameResolver(HttpClient.create(), false);
	}

	@Test
	public void testSharedNameResolver_NotSharedClientNoConnectionPool() throws InterruptedException {
		doTestSharedNameResolver(HttpClient.newConnection(), false);
	}

	@Test
	void testHttpClientCancelled() throws InterruptedException {
		// logged by the server when last http packet is sent and channel is terminated
		String serverCancelledLog = "[HttpServer] Channel inbound receiver cancelled (operation cancelled).";
		// logged by client when cancelled while receiving response
		String clientCancelledLog = HttpClientOperations.INBOUND_CANCEL_LOG;

		ConnectionProvider pool = ConnectionProvider.create("testHttpClientCancelled", 1);
		try (LogTracker lt = new LogTracker(ChannelOperations.class, serverCancelledLog);
		     LogTracker lt2 = new LogTracker(HttpClientOperations.class, clientCancelledLog)) {
			CountDownLatch serverClosed = new CountDownLatch(1);
			Sinks.Empty<Void> empty = Sinks.empty();
			CancelReceiverHandlerTest cancelReceiver = new CancelReceiverHandlerTest(empty::tryEmitEmpty, 1);

			disposableServer = createServer()
					.handle((in, out) -> {
						in.withConnection(connection -> connection.onDispose(serverClosed::countDown));
						return in.receive()
								.asString()
								.log("server.receive")
								.then(out.sendString(Mono.just("data")).neverComplete());
					})
					.bindNow();

			HttpClient httpClient = createHttpClientForContextWithPort(pool);
			CountDownLatch clientCancelled = new CountDownLatch(1);

			// Creates a client that should be cancelled by the Flix.zip (see below)
			Mono<String> client = httpClient
					.doOnRequest((req, conn) -> conn.addHandlerFirst(cancelReceiver))
					.get()
					.responseContent()
					.aggregate()
					.asString()
					.log("client")
					.doOnCancel(clientCancelled::countDown);

			// Zip client with a mono which completes with an empty value when the server receives the request.
			// The client should then be cancelled with a log message.
			StepVerifier.create(Flux.zip(client, empty.asMono())
							.log("zip"))
					.expectNextCount(0)
					.expectComplete()
					.verify(Duration.ofSeconds(30));

			assertThat(cancelReceiver.awaitAllReleased(30)).as("cancelReceiver").isTrue();
			assertThat(clientCancelled.await(30, TimeUnit.SECONDS)).as("latchClient await").isTrue();
			assertThat(serverClosed.await(30, TimeUnit.SECONDS)).as("latchServerClosed await").isTrue();
			assertThat(lt.latch.await(30, TimeUnit.SECONDS)).as("logTracker await").isTrue();
			assertThat(lt2.latch.await(30, TimeUnit.SECONDS)).as("logTracker2 await").isTrue();
		}
		finally {
			pool.disposeLater()
					.block(Duration.ofSeconds(30));
		}
	}

	private void doTestSharedNameResolver(HttpClient client, boolean sharedClient) throws InterruptedException {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.sendString(Mono.just("testNoOpenedFileDescriptors")))
				          .bindNow(Duration.ofSeconds(30));

		LoopResources loop = LoopResources.create("doTestSharedNameResolver", 4, true);
		AtomicReference<List<AddressResolverGroup<?>>> resolvers = new AtomicReference<>(new ArrayList<>());
		try {
			int count = 8;
			CountDownLatch latch = new CountDownLatch(count);
			HttpClient localClient = null;
			if (sharedClient) {
				localClient = client.runOn(loop)
				                    .port(disposableServer.port())
				                    .doOnConnect(config -> resolvers.get().add(config.resolver()))
				                    .doOnConnected(conn ->
				                            conn.onTerminate()
				                                .subscribe(null, t -> latch.countDown(), latch::countDown));
			}
			for (int i = 0; i < count; i++) {
				if (!sharedClient) {
					localClient = client.runOn(loop)
					                    .port(disposableServer.port())
					                    .doOnConnect(config -> resolvers.get().add(config.resolver()))
					                    .doOnConnected(conn ->
					                            conn.onTerminate()
					                                .subscribe(null, t -> latch.countDown(), latch::countDown));
				}
				localClient.get()
				           .uri("/")
				           .responseContent()
				           .aggregate()
				           .asString()
				           .subscribe();
			}

			assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

			assertThat(resolvers.get().size()).isEqualTo(count);
			AddressResolverGroup<?> resolver = resolvers.get().get(0);
			assertThat(resolvers.get()).allMatch(addressResolverGroup -> addressResolverGroup == resolver);
		}
		finally {
			loop.disposeLater()
			    .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testIssue1943Http11() {
		doTestIssue1943(HttpProtocol.HTTP11);
	}

	@Test
	void testIssue1943H2C() {
		doTestIssue1943(HttpProtocol.H2C);
	}

	private void doTestIssue1943(HttpProtocol protocol) {
		LoopResources serverLoop = LoopResources.create("testIssue1943");
		disposableServer =
				createServer()
				        .protocol(protocol)
				        .runOn(serverLoop)
				        .handle((req, res) -> res.sendString(Mono.just("testIssue1943")))
				        .bindNow();

		HttpClient client = createClient(disposableServer.port()).protocol(protocol);
		HttpClientConfig config = client.configuration();

		LoopResources loopResources1 = config.loopResources();
		ConnectionProvider provider1 = config.connectionProvider();
		AddressResolverGroup<?> resolverGroup1 = config.defaultAddressResolverGroup();

		try {
		client.get()
		      .uri("/")
		      .responseContent()
		      .aggregate()
		      .asString()
		      .as(StepVerifier::create)
		      .expectNext("testIssue1943")
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));

		HttpResources.reset();

		LoopResources loopResources2 = config.loopResources();
		ConnectionProvider provider2 = config.connectionProvider();
		AddressResolverGroup<?> resolverGroup2 = config.defaultAddressResolverGroup();

		assertThat(loopResources1).isNotSameAs(loopResources2);
		assertThat(provider1).isNotSameAs(provider2);
		assertThat(resolverGroup1).isNotSameAs(resolverGroup2);

		client.get()
		      .uri("/")
		      .responseContent()
		      .aggregate()
		      .asString()
		      .as(StepVerifier::create)
		      .expectNext("testIssue1943")
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
		}
		finally {
			serverLoop.disposeLater()
			          .block(Duration.ofSeconds(5));
		}
	}

	static final class TestMeterRegistrar implements ConnectionProvider.MeterRegistrar {

		AtomicReference<ConnectionPoolMetrics> m = new AtomicReference<>();

		@Override
		public void registerMetrics(String poolName, String id, SocketAddress remoteAddress, ConnectionPoolMetrics metrics) {
			m.set(metrics);
		}

		@Override
		public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
		}
	}
}
