/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.resources;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.netty5.channel.ChannelOption;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.nio.NioHandler;
import io.netty5.handler.logging.LoggingHandler;
import io.netty5.resolver.AddressResolverGroup;
import io.netty5.resolver.DefaultAddressResolverGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.channel.ChannelMetricsRecorder;
import reactor.netty5.transport.ClientTransportConfig;
import reactor.util.annotation.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class PooledConnectionProviderCustomMetricsTest {

	private Supplier<? extends SocketAddress> remoteAddress;

	private EventLoopGroup group;

	private ConnectionProvider pool;

	private static final int MAX_ALLOC_SIZE = 100;

	private static final int MAX_PENDING_ACQUIRE_SIZE = 1000;

	@BeforeEach
	void setUp() {
		remoteAddress = () -> InetSocketAddress.createUnresolved("localhost", 0);
		group = new MultithreadEventLoopGroup(2, NioHandler.newFactory());
	}

	@AfterEach
	void tearDown() throws Exception {
		group.shutdownGracefully()
		     .asStage().get(10L, TimeUnit.SECONDS);
		pool.dispose();
	}


	@Test
	void customRegistrarIsUsed() {
		AtomicBoolean registered = new AtomicBoolean();
		AtomicBoolean deRegistered = new AtomicBoolean();

		triggerAcquisition(true, () -> new MeterRegistrarImpl(registered, deRegistered, null));
		assertThat(registered.get()).isTrue();
		assertThat(deRegistered.get()).isFalse();

		pool.dispose();
		assertThat(deRegistered.get()).isTrue();
	}

	@Test
	void connectionPoolMaxMetrics() {
		AtomicInteger customMetric = new AtomicInteger();

		triggerAcquisition(true, () -> new MeterRegistrarImpl(null, null, customMetric));
		assertThat(customMetric.get()).isEqualTo(MAX_ALLOC_SIZE);
	}

	@Test
	void customRegistrarSupplierNotInvokedWhenMetricsDisabled() {
		AtomicBoolean registered = new AtomicBoolean();

		triggerAcquisition(false, () -> new MeterRegistrarImpl(registered, null, null));
		assertThat(registered.get()).isFalse();
	}

	@Test
	void disposeWhenMetricsDeregistered() {
		AtomicBoolean registered = new AtomicBoolean();
		AtomicBoolean deRegistered = new AtomicBoolean();

		triggerAcquisition(true, () -> new MeterRegistrarImpl(registered, deRegistered, null));
		assertThat(registered.get()).isTrue();
		assertThat(deRegistered.get()).isFalse();

		pool.disposeWhen(remoteAddress.get());
		assertThat(deRegistered.get()).isTrue();
	}

	private void triggerAcquisition(boolean metricsEnabled, Supplier<ConnectionProvider.MeterRegistrar> registrarSupplier) {
		pool = ConnectionProvider.builder("test")
		                         .metrics(metricsEnabled, registrarSupplier)
		                         .maxConnections(MAX_ALLOC_SIZE)
		                         .pendingAcquireMaxCount(MAX_PENDING_ACQUIRE_SIZE)
		                         .build();

		ClientTransportConfigImpl config =
				new ClientTransportConfigImpl(group, pool, Collections.emptyMap(), remoteAddress);

		try {
			pool.acquire(config, ConnectionObserver.emptyListener(), remoteAddress, config.resolverInternal())
			    .block(Duration.ofSeconds(10L));
			fail("Exception is expected");
		}
		catch (Exception expected) {
			// ignore
		}
	}

	static final class MeterRegistrarImpl implements ConnectionProvider.MeterRegistrar {
		AtomicBoolean registered;
		AtomicBoolean deRegistered;
		AtomicInteger customMetric;

		MeterRegistrarImpl(
				@Nullable AtomicBoolean registered,
				@Nullable AtomicBoolean deRegistered,
				@Nullable AtomicInteger customMetric) {
			this.registered = registered;
			this.deRegistered = deRegistered;
			this.customMetric = customMetric;
		}

		@Override
		public void registerMetrics(String poolName, String id, SocketAddress remoteAddress, ConnectionPoolMetrics metrics) {
			if (registered != null) {
				registered.set(true);
			}
			if (customMetric != null) {
				customMetric.set(metrics.maxAllocatedSize());
			}
		}

		@Override
		public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
			if (deRegistered != null) {
				deRegistered.set(true);
			}
		}
	}

	static final class ClientTransportConfigImpl extends ClientTransportConfig<ClientTransportConfigImpl> {

		final EventLoopGroup group;

		ClientTransportConfigImpl(EventLoopGroup group, ConnectionProvider connectionProvider,
				Map<ChannelOption<?>, ?> options, Supplier<? extends SocketAddress> remoteAddress) {
			super(connectionProvider, options, remoteAddress);
			this.group = group;
		}

		@Override
		protected LoggingHandler defaultLoggingHandler() {
			return null;
		}

		@Override
		protected LoopResources defaultLoopResources() {
			return preferNative -> group;
		}

		@Override
		protected ChannelMetricsRecorder defaultMetricsRecorder() {
			return null;
		}

		@Override
		protected AddressResolverGroup<?> defaultAddressResolverGroup() {
			return DefaultAddressResolverGroup.INSTANCE;
		}

		@Override
		protected EventLoopGroup eventLoopGroup() {
			return group;
		}

		@Override
		protected AddressResolverGroup<?> resolverInternal() {
			return super.resolverInternal();
		}
	}
}
