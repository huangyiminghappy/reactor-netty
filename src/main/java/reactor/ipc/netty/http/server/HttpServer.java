/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.server;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.time.Duration;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.Attribute;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * An HttpServer allows to build in a safe immutable way an HTTP server that is
 * materialized and connecting when {@link #bind(TcpServer)} is ultimately called.
 * <p> Internally, materialization happens in three phases, first {@link
 * #tcpConfiguration()} is called to retrieve a ready to use {@link TcpServer}, then
 * {@link HttpServer#tcpConfiguration()} ()} retrieve a usable {@link TcpServer} for the final
 * {@link #bind(TcpServer)} is called. <p> Examples:
 * <pre>
 * {@code
 * HttpServer.create()
 * .host("0.0.0.0")
 * .tcpConfiguration(TcpServer::secure)
 * .handler((req, res) -> res.sendString(Flux.just("hello"))
 * .bind()
 * .block();
 * }
 *
 * @author Stephane Maldini
 */
public abstract class HttpServer {

	/**
	 * Prepare a pooled {@link HttpServer}
	 *
	 * @return a {@link HttpServer}
	 */
	public static HttpServer create() {
		return HttpServerBind.INSTANCE;
	}

	/**
	 * Prepare a pooled {@link HttpServer}
	 *
	 * @return a {@link HttpServer}
	 */
	public static HttpServer from(TcpServer tcpServer) {
		return new HttpServerBind(tcpServer);
	}

	/**
	 * Bind the {@link HttpServer} and return a {@link Mono} of {@link Connection}. If
	 * {@link Mono} is cancelled, the underlying binding will be aborted. Once the {@link
	 * Connection} has been emitted and is not necessary anymore, disposing main server
	 * loop must be done by the user via {@link Connection#dispose()}.
	 *
	 * If updateConfiguration phase fails, a {@link Mono#error(Throwable)} will be returned;
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public final Mono<? extends DisposableServer> bind() {
		return bind(tcpConfiguration());
	}

	/**
	 * Start a Server in a blocking fashion, and wait for it to finish initializing. The
	 * returned {@link Connection} offers simple server API, including to {@link
	 * Connection#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @return a {@link Connection}
	 */
	public final DisposableServer bindNow() {
		return bindNow(Duration.ofSeconds(45));
	}


	/**
	 * Start a Server in a blocking fashion, and wait for it to finish initializing. The
	 * returned {@link Connection} offers simple server API, including to {@link
	 * Connection#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @param timeout max startup timeout
	 *
	 * @return a {@link Connection}
	 */
	public final DisposableServer bindNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		return Objects.requireNonNull(bind().block(timeout), "aborted");
	}

	/**
	 * Start a Server in a fully blocking fashion, not only waiting for it to initialize
	 * but also blocking during the full lifecycle of the client/server. Since most
	 * servers will be long-lived, this is more adapted to running a server out of a main
	 * method, only allowing shutdown of the servers through sigkill.
	 * <p>
	 * Note that a {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} is added by
	 * this method in order to properly disconnect the client/server upon receiving a
	 * sigkill signal.
	 *
	 * @param timeout a timeout for server shutdown
	 * @param onStart an optional callback on server start
	 */
	public final void bindUntilJavaShutdown(Duration timeout,
	                                        @Nullable Consumer<DisposableServer> onStart) {

		Objects.requireNonNull(timeout, "timeout");
		DisposableServer facade = bindNow();

		Objects.requireNonNull(facade, "facade");

		if (onStart != null) {
			onStart.accept(facade);
		}
		Runtime.getRuntime()
		       .addShutdownHook(new Thread(() -> facade.disposeNow(timeout)));

		facade.onDispose()
		      .block();
	}

	/**
	 * The port to which this server should bind.
	 *
	 * @param port The port to bind to.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer port(int port) {
		return tcpConfiguration(tcpServer -> tcpServer.port(port));
	}

	/**
	 * Apply a wire logger configuration using {@link HttpServer} category
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer wiretap() {
		return tcpConfiguration(tcpServer ->
		        tcpServer.bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER)));
	}

	/**
	 * Enable GZip response compression if the client request presents accept encoding
	 * headers.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer compress() {
		return tcpConfiguration(COMPRESS_ATTR_CONFIG);
	}

	/**
	 * Enable GZip response compression if the client request presents accept encoding
	 * headers
	 * AND the response reaches a minimum threshold
	 *
	 * @param minResponseSize compression is performed once response size exceeds given
	 * value in byte
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer compress(int minResponseSize) {
		if (minResponseSize < 0) {
			throw new IllegalArgumentException("minResponseSize must be positive");
		}
		return tcpConfiguration(tcp -> tcp.selectorAttr(HttpServerBind.PRODUCE_GZIP, minResponseSize));
	}

	/**
	 * Enable GZip response compression if the client request presents accept encoding
	 * headers and the passed {@link java.util.function.Predicate} matches.
	 * <p>
	 *     note the passed {@link HttpServerRequest} and {@link HttpServerResponse}
	 *     should be considered read-only and the implement SHOULD NOT consume or
	 *     write the request/response in this predicate.
	 *
	 * @param predicate that returns true to compress the response.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer compress(BiPredicate<HttpServerRequest, HttpServerResponse> predicate) {
		Objects.requireNonNull(predicate, "compressionPredicate");
		return tcpConfiguration(tcp -> tcp.attr(HttpServerBind.PRODUCE_GZIP_PREDICATE, predicate));
	}

	/**
	 * Attach an IO handler to react on connected server
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates. Only the first registered handler will subscribe to the
	 * returned {@link Publisher} while other will immediately cancel given a same
	 * {@link Connection}
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer handler(BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return tcpConfiguration(tcp -> tcp.doOnConnection(c -> {
			if (log.isDebugEnabled()) {
				log.debug("{} handler is being applied: {}", c.channel(), handler);
			}
			try {
				Mono.fromDirect(handler.apply((HttpServerRequest) c, (HttpServerResponse) c))
				    .subscribe(c.disposeSubscriber());
			} catch (Throwable t) {
				log.error("", t);
				c.channel().close();
			}
		}));
	}

	/**
	 * Disable gzip compression
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer noCompression() {
		return tcpConfiguration(COMPRESS_ATTR_DISABLE);
	}

	/**
	 * Apply {@link ServerBootstrap} configuration given mapper taking currently
	 * configured one and returning a new one to be ultimately used for socket binding.
	 * <p> Configuration will apply during {@link #tcpConfiguration()} phase.
	 *
	 * @param tcpMapper A tcpServer mapping function to update tcp configuration and
	 * return an enriched tcp server to use.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer tcpConfiguration(Function<? super TcpServer, ? extends TcpServer> tcpMapper) {
		return new HttpServerTcpConfig(this, tcpMapper);
	}

	/**
	 * Define routes for the server through the provided {@link HttpServerRoutes} builder.
	 *
	 * @param routesBuilder provides a route builder to be mutated in order to define routes.
	 * @return a new {@link HttpServer} starting the router on subscribe
	 */
	public final HttpServer router(Consumer<? super HttpServerRoutes> routesBuilder) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		return handler(routes);
	}

	/**
	 * Bind the {@link HttpServer} and return a {@link Mono} of {@link Connection}
	 *
	 * @param b the {@link TcpServer} to bind
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	protected abstract Mono<? extends DisposableServer> bind(TcpServer b);

	/**
	 * Materialize a TcpServer from the parent {@link HttpServer} chain to use with
	 * {@link #bind(TcpServer)} or separately
	 *
	 * @return a configured {@link TcpServer}
	 */
	protected TcpServer tcpConfiguration() {
		return DEFAULT_TCP_SERVER;
	}



	static final ChannelOperations.OnSetup HTTP_OPS = new ChannelOperations.OnSetup() {
		@Nullable
		@Override
		public ChannelOperations<?, ?> create(Connection c, ConnectionEvents listener, Object msg) {
				Attribute<BiPredicate<HttpServerRequest, HttpServerResponse>> predicate =
						c.channel().attr(HttpServerBind.PRODUCE_GZIP_PREDICATE);
				final BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate;
				if (predicate != null && predicate.get() != null) {
					compressionPredicate = predicate.get();
				}
				else {
					compressionPredicate = null;
				}
			return HttpServerOperations.bindHttp(c, listener, compressionPredicate, msg);
		}

		@Override
		public boolean createOnConnected() {
			return false;
		}
	};

	static final int DEFAULT_PORT =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;

	static final Function<ServerBootstrap, ServerBootstrap> HTTP_OPS_CONF = b -> {
		BootstrapHandlers.channelOperationFactory(b, HTTP_OPS);
		return b;
	};

	static final TcpServer DEFAULT_TCP_SERVER = TcpServer.create()
			                                             .bootstrap(HTTP_OPS_CONF)
			                                             .port(DEFAULT_PORT);

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(HttpServer.class);
	static final Logger         log             = Loggers.getLogger(HttpServer.class);

	static final Function<TcpServer, TcpServer> COMPRESS_ATTR_CONFIG =
			tcp -> tcp.selectorAttr(HttpServerBind.PRODUCE_GZIP, 0);

	static final Function<TcpServer, TcpServer> COMPRESS_ATTR_DISABLE =
			tcp -> tcp.selectorAttr(HttpServerBind.PRODUCE_GZIP, null)
			          .selectorAttr(HttpServerBind.PRODUCE_GZIP_PREDICATE, null);
}
