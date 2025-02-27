/*
 * Copyright (c) 2010-2022. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.axonframework.queryhandling;

import org.axonframework.common.Assert;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.Registration;
import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.messaging.DefaultInterceptorChain;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.MessageDispatchInterceptor;
import org.axonframework.messaging.MessageHandler;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.messaging.ResultMessage;
import org.axonframework.messaging.interceptors.TransactionManagingInterceptor;
import org.axonframework.messaging.responsetypes.ResponseType;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.monitoring.NoOpMessageMonitor;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

import static java.lang.String.format;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.ObjectUtils.getRemainingOfDeadline;
import static org.axonframework.queryhandling.GenericQueryResponseMessage.asNullableResponseMessage;

/**
 * Implementation of the QueryBus that dispatches queries to the handlers within the JVM. Any timeouts are ignored by
 * this implementation, as handlers are considered to answer immediately.
 * <p>
 * In case multiple handlers are registered for the same query and response type, the {@link #query(QueryMessage)}
 * method will invoke one of these handlers. Which one is unspecified.
 *
 * @author Marc Gathier
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author Milan Savic
 * @since 3.1
 */
public class SimpleQueryBus implements QueryBus {

    private static final Logger logger = LoggerFactory.getLogger(SimpleQueryBus.class);

    @SuppressWarnings("rawtypes")
    private final ConcurrentMap<String, CopyOnWriteArrayList<QuerySubscription>> subscriptions = new ConcurrentHashMap<>();
    private final MessageMonitor<? super QueryMessage<?, ?>> messageMonitor;
    private final QueryInvocationErrorHandler errorHandler;
    private final List<MessageHandlerInterceptor<? super QueryMessage<?, ?>>> handlerInterceptors = new CopyOnWriteArrayList<>();
    private final List<MessageDispatchInterceptor<? super QueryMessage<?, ?>>> dispatchInterceptors = new CopyOnWriteArrayList<>();

    private final QueryUpdateEmitter queryUpdateEmitter;

    /**
     * Instantiate a {@link SimpleQueryBus} based on the fields contained in the {@link Builder}.
     *
     * @param builder the {@link Builder} used to instantiate a {@link SimpleQueryBus} instance
     */
    protected SimpleQueryBus(Builder builder) {
        builder.validate();
        this.messageMonitor = builder.messageMonitor;
        this.errorHandler = builder.errorHandler;
        if (builder.transactionManager != NoTransactionManager.INSTANCE) {
            registerHandlerInterceptor(new TransactionManagingInterceptor<>(builder.transactionManager));
        }
        this.queryUpdateEmitter = builder.queryUpdateEmitter;
    }

    /**
     * Instantiate a Builder to be able to create a {@link SimpleQueryBus}.
     * <p>
     * The {@link MessageMonitor} is defaulted to {@link NoOpMessageMonitor}, {@link TransactionManager} to {@link
     * NoTransactionManager}, {@link QueryInvocationErrorHandler} to {@link LoggingQueryInvocationErrorHandler}, and
     * {@link QueryUpdateEmitter} to {@link SimpleQueryUpdateEmitter}.
     *
     * @return a Builder to be able to create a {@link SimpleQueryBus}
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public <R> Registration subscribe(@Nonnull String queryName,
                                      @Nonnull Type responseType,
                                      @Nonnull MessageHandler<? super QueryMessage<?, R>> handler) {
        //noinspection rawtypes
        CopyOnWriteArrayList<QuerySubscription> handlers =
                subscriptions.computeIfAbsent(queryName, k -> new CopyOnWriteArrayList<>());
        QuerySubscription<R> querySubscription = new QuerySubscription<>(responseType, handler);
        handlers.addIfAbsent(querySubscription);

        return () -> unsubscribe(queryName, querySubscription);
    }

    private <R> boolean unsubscribe(String queryName, QuerySubscription<R> querySubscription) {
        subscriptions.computeIfPresent(queryName, (key, handlers) -> {
            handlers.remove(querySubscription);
            if (handlers.isEmpty()) {
                return null;
            }
            return handlers;
        });
        return true;
    }

    @Override
    public <Q, R> CompletableFuture<QueryResponseMessage<R>> query(@Nonnull QueryMessage<Q, R> query) {
        Assert.isFalse(Publisher.class.isAssignableFrom(query.getResponseType().getExpectedResponseType()),
                       () -> "Direct query does not support Flux as a return type.");
        MessageMonitor.MonitorCallback monitorCallback = messageMonitor.onMessageIngested(query);
        QueryMessage<Q, R> interceptedQuery = intercept(query);
        List<MessageHandler<? super QueryMessage<?, ?>>> handlers = getHandlersForMessage(interceptedQuery);
        CompletableFuture<QueryResponseMessage<R>> result = new CompletableFuture<>();
        try {
            ResponseType<R> responseType = interceptedQuery.getResponseType();
            if (handlers.isEmpty()) {
                throw noHandlerException(interceptedQuery);
            }
            Iterator<MessageHandler<? super QueryMessage<?, ?>>> handlerIterator = handlers.iterator();
            boolean invocationSuccess = false;
            while (!invocationSuccess && handlerIterator.hasNext()) {
                DefaultUnitOfWork<QueryMessage<Q, R>> uow = DefaultUnitOfWork.startAndGet(interceptedQuery);
                ResultMessage<CompletableFuture<QueryResponseMessage<R>>> resultMessage =
                        interceptAndInvoke(uow, handlerIterator.next());
                if (resultMessage.isExceptional()) {
                    if (!(resultMessage.exceptionResult() instanceof NoHandlerForQueryException)) {
                        GenericQueryResponseMessage<R> queryResponseMessage =
                                responseType.convertExceptional(resultMessage.exceptionResult())
                                            .map(GenericQueryResponseMessage::new)
                                            .orElse(new GenericQueryResponseMessage<>(
                                                    responseType.responseMessagePayloadType(),
                                                    resultMessage.exceptionResult()));


                        result.complete(queryResponseMessage);
                        monitorCallback.reportFailure(resultMessage.exceptionResult());
                        return result;
                    }
                } else {
                    result = resultMessage.getPayload();
                    invocationSuccess = true;
                }
            }
            if (!invocationSuccess) {
                throw noSuitableHandlerException(interceptedQuery);
            }
            monitorCallback.reportSuccess();
        } catch (Exception e) {
            result.completeExceptionally(e);
            monitorCallback.reportFailure(e);
        }
        return result;
    }

    @Override
    public <Q, R> Publisher<QueryResponseMessage<R>> streamingQuery(StreamingQueryMessage<Q, R> query) {
        return Mono.just(intercept(query))
                   .flatMapMany(interceptedQuery -> Mono.just(interceptedQuery)
                                                   .flatMapMany(this::getStreamingHandlersForMessage)
                                                   .switchIfEmpty(Flux.error(noHandlerException(interceptedQuery)))
                                                   .map(handler -> interceptAndInvokeStreaming(interceptedQuery, handler))
                                                   .skipWhile(ResultMessage::isExceptional)
                                                   .switchIfEmpty(Flux.error(noSuitableHandlerException(interceptedQuery)))
                                                   .next()
                                                   .doOnEach(this::monitorCallback)
                                                   .flatMapMany(Message::getPayload))
                   .contextWrite(ctx -> ctx.put(MessageMonitor.MonitorCallback.class,
                                                messageMonitor.onMessageIngested(query)));
    }

    private NoHandlerForQueryException noHandlerException(QueryMessage<?, ?> intercepted) {
        return new NoHandlerForQueryException(format("No handler found for [%s] with response type [%s]",
                                                     intercepted.getQueryName(),
                                                     intercepted.getResponseType()));
    }

    private NoHandlerForQueryException noSuitableHandlerException(QueryMessage<?, ?> intercepted) {
        return new NoHandlerForQueryException(format("No suitable handler was found for [%s] with response type [%s]",
                                                     intercepted.getQueryName(),
                                                     intercepted.getResponseType()));
    }

    private void monitorCallback(Signal<?> signal) {
        MessageMonitor.MonitorCallback m = signal.getContextView()
                                                 .get(MessageMonitor.MonitorCallback.class);
        if (signal.isOnNext()) {
            m.reportSuccess();
        } else if (signal.isOnError()) {
            m.reportFailure(signal.getThrowable());
        }
    }

    @Override
    public <Q, R> Stream<QueryResponseMessage<R>> scatterGather(@Nonnull QueryMessage<Q, R> query, long timeout,
                                                                @Nonnull TimeUnit unit) {
        Assert.isFalse(Publisher.class.isAssignableFrom(query.getResponseType().getExpectedResponseType()),
                       () -> "Scatter-Gather query does not support Flux as a return type.");
        MessageMonitor.MonitorCallback monitorCallback = messageMonitor.onMessageIngested(query);
        QueryMessage<Q, R> interceptedQuery = intercept(query);
        List<MessageHandler<? super QueryMessage<?, ?>>> handlers = getHandlersForMessage(interceptedQuery);
        if (handlers.isEmpty()) {
            monitorCallback.reportIgnored();
            return Stream.empty();
        }

        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        return handlers.stream()
                       .map(handler -> {
                           long leftTimeout = getRemainingOfDeadline(deadline);
                           ResultMessage<CompletableFuture<QueryResponseMessage<R>>> resultMessage =
                                   interceptAndInvoke(DefaultUnitOfWork.startAndGet(interceptedQuery),
                                                      handler);
                           QueryResponseMessage<R> response = null;
                           if (resultMessage.isExceptional()) {
                               monitorCallback.reportFailure(resultMessage.exceptionResult());
                               errorHandler.onError(resultMessage.exceptionResult(), interceptedQuery, handler);
                           } else {
                               try {
                                   response = resultMessage.getPayload().get(leftTimeout, TimeUnit.MILLISECONDS);
                                   monitorCallback.reportSuccess();
                               } catch (Exception e) {
                                   monitorCallback.reportFailure(e);
                                   errorHandler.onError(e, interceptedQuery, handler);
                               }
                           }
                           return response;
                       }).filter(Objects::nonNull);
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated in favor use of {{@link #subscriptionQuery(SubscriptionQueryMessage, int)}
     */
    @Deprecated
    @Override
    public <Q, I, U> SubscriptionQueryResult<QueryResponseMessage<I>, SubscriptionQueryUpdateMessage<U>> subscriptionQuery(
            @Nonnull SubscriptionQueryMessage<Q, I, U> query,
            SubscriptionQueryBackpressure backpressure,
            int updateBufferSize
    ) {
        assertSubQueryResponseTypes(query);
        if (queryUpdateEmitter.queryUpdateHandlerRegistered(query)) {
            throw new IllegalArgumentException("There is already a subscription with the given message identifier");
        }

        Mono<QueryResponseMessage<I>> initialResult = Mono.fromFuture(() -> query(query))
                                                          .doOnError(error -> logger.error(format(
                                                                  "An error happened while trying to report an initial result. Query: %s",
                                                                  query), error));

        UpdateHandlerRegistration<U> updateHandlerRegistration =
                queryUpdateEmitter.registerUpdateHandler(query, backpressure, updateBufferSize);

        return getSubscriptionQueryResult(initialResult, updateHandlerRegistration);
    }

    @Override
    public <Q, I, U> SubscriptionQueryResult<QueryResponseMessage<I>, SubscriptionQueryUpdateMessage<U>> subscriptionQuery(
            @Nonnull SubscriptionQueryMessage<Q, I, U> query,
            int updateBufferSize
    ) {
        assertSubQueryResponseTypes(query);
        if (queryUpdateEmitter.queryUpdateHandlerRegistered(query)) {
            throw new IllegalArgumentException("There is already a subscription with the given message identifier");
        }

        Mono<QueryResponseMessage<I>> initialResult = Mono.fromFuture(() -> query(query))
                                                          .doOnError(error -> logger.error(format(
                                                                  "An error happened while trying to report an initial result. Query: %s",
                                                                  query), error));
        UpdateHandlerRegistration<U> updateHandlerRegistration =
                queryUpdateEmitter.registerUpdateHandler(query, updateBufferSize);

        return getSubscriptionQueryResult(initialResult, updateHandlerRegistration);
    }

    private <Q, I, U> void assertSubQueryResponseTypes(SubscriptionQueryMessage<Q, I, U> query) {
        Assert.isFalse(Publisher.class.isAssignableFrom(query.getResponseType().getExpectedResponseType()),
                       () -> "Subscription Query query does not support Flux as a return type.");
        Assert.isFalse(Publisher.class.isAssignableFrom(query.getUpdateResponseType().getExpectedResponseType()),
                       () -> "Subscription Query query does not support Flux as an update type.");
    }

    private <I, U> DefaultSubscriptionQueryResult<QueryResponseMessage<I>, SubscriptionQueryUpdateMessage<U>> getSubscriptionQueryResult(
            Mono<QueryResponseMessage<I>> initialResult,
            UpdateHandlerRegistration<U> updateHandlerRegistration
    ) {
        return new DefaultSubscriptionQueryResult<>(initialResult,
                                                    updateHandlerRegistration.getUpdates(),
                                                    () -> {
                                                        updateHandlerRegistration.complete();
                                                        return true;
                                                    });
    }

    @Override
    public QueryUpdateEmitter queryUpdateEmitter() {
        return queryUpdateEmitter;
    }

    private <Q, R> ResultMessage<CompletableFuture<QueryResponseMessage<R>>> interceptAndInvoke(
            UnitOfWork<QueryMessage<Q, R>> uow,
            MessageHandler<? super QueryMessage<?, R>> handler
    ) {
        return uow.executeWithResult(() -> {
            ResponseType<R> responseType = uow.getMessage().getResponseType();
            Object queryResponse = new DefaultInterceptorChain<>(uow, handlerInterceptors, handler).proceed();
            if (queryResponse instanceof CompletableFuture) {
                return ((CompletableFuture<?>) queryResponse).thenCompose(
                        result -> buildCompletableFuture(responseType, result));
            } else if (queryResponse instanceof Future) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return asNullableResponseMessage(
                                responseType.responseMessagePayloadType(),
                                responseType.convert(((Future<?>) queryResponse).get()));
                    } catch (InterruptedException | ExecutionException e) {
                        throw new QueryExecutionException("Error happened while trying to execute query handler", e);
                    }
                });
            }
            return buildCompletableFuture(responseType, queryResponse);
        });
    }

    private <Q, R> ResultMessage<Publisher<QueryResponseMessage<R>>> interceptAndInvokeStreaming(
            StreamingQueryMessage<Q, R> query,
            MessageHandler<? super StreamingQueryMessage<?, R>> handler) {
        DefaultUnitOfWork<StreamingQueryMessage<Q, R>> uow = DefaultUnitOfWork.startAndGet(query);
        return uow.executeWithResult(() -> {
            Object queryResponse = new DefaultInterceptorChain<>(uow, handlerInterceptors, handler).proceed();
            return query.getResponseType()
                        .convert(queryResponse)
                        .map(GenericQueryResponseMessage::asResponseMessage);
        });
    }

    private <R> CompletableFuture<QueryResponseMessage<R>> buildCompletableFuture(ResponseType<R> responseType,
                                                                                  Object queryResponse) {
        return CompletableFuture.completedFuture(asNullableResponseMessage(
                responseType.responseMessagePayloadType(),
                responseType.convert(queryResponse)));
    }

    @SuppressWarnings("unchecked")
    private <Q, R, T extends QueryMessage<Q, R>> T intercept(T query) {
        T intercepted = query;
        for (MessageDispatchInterceptor<? super QueryMessage<?, ?>> interceptor : dispatchInterceptors) {
            intercepted = (T) interceptor.handle(intercepted);
        }
        return intercepted;
    }

    /**
     * Returns the subscriptions for this query bus. While the returned map is unmodifiable, it may or may not reflect
     * changes made to the subscriptions after the call was made.
     *
     * @return the subscriptions for this query bus
     */
    protected Map<String, Collection<QuerySubscription>> getSubscriptions() {
        return Collections.unmodifiableMap(subscriptions);
    }

    /**
     * Registers an interceptor that is used to intercept Queries before they are passed to their respective handlers.
     * The interceptor is invoked separately for each handler instance (in a separate unit of work).
     *
     * @param interceptor the interceptor to invoke before passing a Query to the handler
     * @return handle to unregister the interceptor
     */
    @Override
    public Registration registerHandlerInterceptor(
            @Nonnull MessageHandlerInterceptor<? super QueryMessage<?, ?>> interceptor) {
        handlerInterceptors.add(interceptor);
        return () -> handlerInterceptors.remove(interceptor);
    }

    /**
     * Registers an interceptor that intercepts Queries as they are sent. Each interceptor is called once, regardless of
     * the type of query (point-to-point or scatter-gather) executed.
     *
     * @param interceptor the interceptor to invoke when sending a Query
     * @return handle to unregister the interceptor
     */
    @Override
    public @Nonnull
    Registration registerDispatchInterceptor(
            @Nonnull MessageDispatchInterceptor<? super QueryMessage<?, ?>> interceptor) {
        dispatchInterceptors.add(interceptor);
        return () -> dispatchInterceptors.remove(interceptor);
    }

    @SuppressWarnings("unchecked") // Suppresses 'queryHandler' cast to `MessageHandler<? super QueryMessage<?, ?>>`
    private <Q, R> List<MessageHandler<? super QueryMessage<?, ?>>> getHandlersForMessage(
            QueryMessage<Q, R> queryMessage) {
        ResponseType<R> responseType = queryMessage.getResponseType();
        return subscriptions.computeIfAbsent(queryMessage.getQueryName(), k -> new CopyOnWriteArrayList<>())
                            .stream()
                            .filter(querySubscription -> responseType.matches(querySubscription.getResponseType()))
                            .map(QuerySubscription::getQueryHandler)
                            .map(queryHandler -> (MessageHandler<? super QueryMessage<?, ?>>) queryHandler)
                            .collect(Collectors.toList());
    }

    private <Q, R> Publisher<MessageHandler<? super QueryMessage<?, ?>>> getStreamingHandlersForMessage(
            StreamingQueryMessage<Q, R> queryMessage) {
        ResponseType<?> responseType = queryMessage.getResponseType();
        return Flux.fromStream(subscriptions.computeIfAbsent(queryMessage.getQueryName(), k -> new CopyOnWriteArrayList<>())
                                            .stream()
                                            .filter(subscription -> responseType.matches(subscription.getResponseType()))
                                            .sorted(new StreamingQueryHandlerComparator())
                                            .map(QuerySubscription::getQueryHandler)
                                            .map(queryHandler -> (MessageHandler<? super QueryMessage<?, ?>>) queryHandler));
    }

    /**
     * Comparator to prioritize handlers based on the response type.
     * <p>
     * Because {@code FluxResponseType} can handle any response type, this comparator is used to prioritize the
     * Flux(high priority) over {@code List}/{@code Stream} handlers (medium priority). All other handlers are
     * prioritized after the {@code List}/{@code Stream} handlers (low priority).
     */
    private static class StreamingQueryHandlerComparator implements Comparator<QuerySubscription> {

        private enum Priority {
            HIGH,
            MEDIUM,
            LOW
        }

        @Override
        public int compare(QuerySubscription o1, QuerySubscription o2) {
            return getPriority(o1) - getPriority(o2);
        }

        @SuppressWarnings("rawtypes")
        private int getPriority(QuerySubscription handler) {
            if (handler.getResponseType().getTypeName().contains("reactor.core.publisher.Flux")) {
                return Priority.HIGH.ordinal();
            } else if (handler.getResponseType().getTypeName().contains("java.util.")) {
                return Priority.MEDIUM.ordinal();
            } else {
                return Priority.LOW.ordinal();
            }
        }
    }

    /**
     * Builder class to instantiate a {@link SimpleQueryBus}.
     * <p>
     * The {@link MessageMonitor} is defaulted to {@link NoOpMessageMonitor}, {@link TransactionManager} to {@link
     * NoTransactionManager}, {@link QueryInvocationErrorHandler} to {@link LoggingQueryInvocationErrorHandler}, and
     * {@link QueryUpdateEmitter} to {@link SimpleQueryUpdateEmitter}.
     */
    public static class Builder {

        private MessageMonitor<? super QueryMessage<?, ?>> messageMonitor = NoOpMessageMonitor.INSTANCE;
        private TransactionManager transactionManager = NoTransactionManager.instance();
        private QueryInvocationErrorHandler errorHandler = LoggingQueryInvocationErrorHandler.builder()
                                                                                             .logger(logger)
                                                                                             .build();
        private QueryUpdateEmitter queryUpdateEmitter = SimpleQueryUpdateEmitter.builder().build();

        /**
         * Sets the {@link MessageMonitor} used to monitor query messages. Defaults to a {@link NoOpMessageMonitor}.
         *
         * @param messageMonitor a {@link MessageMonitor} used to monitor query messages
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder messageMonitor(@Nonnull MessageMonitor<? super QueryMessage<?, ?>> messageMonitor) {
            assertNonNull(messageMonitor, "MessageMonitor may not be null");
            this.messageMonitor = messageMonitor;
            return this;
        }

        /**
         * Sets the {@link TransactionManager} used to manage the query handling transactions. Defaults to a {@link
         * NoTransactionManager}.
         *
         * @param transactionManager a {@link TransactionManager} used to manage the query handling transactions
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder transactionManager(@Nonnull TransactionManager transactionManager) {
            assertNonNull(transactionManager, "TransactionManager may not be null");
            this.transactionManager = transactionManager;
            return this;
        }

        /**
         * Sets the {@link QueryInvocationErrorHandler} to handle exceptions during query handler invocation. Defaults
         * to a {@link LoggingQueryInvocationErrorHandler} using this instance it's {@link Logger}.
         *
         * @param errorHandler a {@link QueryInvocationErrorHandler} to handle exceptions during query handler
         *                     invocation
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder errorHandler(@Nonnull QueryInvocationErrorHandler errorHandler) {
            assertNonNull(errorHandler, "QueryInvocationErrorHandler may not be null");
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * Sets the {@link QueryUpdateEmitter} used to emits updates for the {@link QueryBus#subscriptionQuery(SubscriptionQueryMessage)}.
         * Defaults to a {@link SimpleQueryUpdateEmitter}.
         *
         * @param queryUpdateEmitter the {@link QueryUpdateEmitter} used to emits updates for the {@link
         *                           QueryBus#subscriptionQuery(SubscriptionQueryMessage)}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder queryUpdateEmitter(@Nonnull QueryUpdateEmitter queryUpdateEmitter) {
            assertNonNull(queryUpdateEmitter, "QueryUpdateEmitter may not be null");
            this.queryUpdateEmitter = queryUpdateEmitter;
            return this;
        }

        /**
         * Initializes a {@link SimpleQueryBus} as specified through this Builder.
         *
         * @return a {@link SimpleQueryBus} as specified through this Builder
         */
        public SimpleQueryBus build() {
            return new SimpleQueryBus(this);
        }

        /**
         * Validate whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            // Method kept for overriding
        }
    }
}
