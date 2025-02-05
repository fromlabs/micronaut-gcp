/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.gcp.pubsub.intercept;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import javax.annotation.PreDestroy;

import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.PublisherInterface;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PubsubMessage;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.core.util.StringUtils;
import io.micronaut.gcp.GoogleCloudConfiguration;
import io.micronaut.gcp.pubsub.annotation.OrderingKey;
import io.micronaut.gcp.pubsub.annotation.PubSubClient;
import io.micronaut.gcp.pubsub.annotation.Topic;
import io.micronaut.gcp.pubsub.configuration.PubSubConfigurationProperties;
import io.micronaut.gcp.pubsub.exception.PubSubClientException;
import io.micronaut.gcp.pubsub.serdes.PubSubMessageSerDes;
import io.micronaut.gcp.pubsub.serdes.PubSubMessageSerDesRegistry;
import io.micronaut.gcp.pubsub.support.PubSubPublisherState;
import io.micronaut.gcp.pubsub.support.PubSubTopicUtils;
import io.micronaut.gcp.pubsub.support.PublisherFactory;
import io.micronaut.gcp.pubsub.support.PublisherFactoryConfig;
import io.micronaut.http.MediaType;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.messaging.annotation.MessageBody;
import io.micronaut.messaging.annotation.MessageHeader;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Implementation of {@link io.micronaut.gcp.pubsub.annotation.PubSubClient} advice annotation.
 *
 * @author Vinicius Carvalho
 * @since 2.0.0
 */
@Singleton
public class PubSubClientIntroductionAdvice implements MethodInterceptor<Object, Object>, AutoCloseable {

    private final Logger logger = LoggerFactory.getLogger(PubSubClientIntroductionAdvice.class);
    private final ConcurrentHashMap<ExecutableMethod, PubSubPublisherState> publisherStateCache = new ConcurrentHashMap<>();
    private final PublisherFactory publisherFactory;
    private final PubSubMessageSerDesRegistry serDesRegistry;
    private final ConversionService<?> conversionService;
    private final GoogleCloudConfiguration googleCloudConfiguration;
    private final PubSubConfigurationProperties pubSubConfigurationProperties;
    private final ExecutorService executorService;

    public PubSubClientIntroductionAdvice(PublisherFactory publisherFactory,
                                          PubSubMessageSerDesRegistry serDesRegistry,
                                          @Named(TaskExecutors.IO) ExecutorService executorService,
                                          ConversionService<?> conversionService,
                                          GoogleCloudConfiguration googleCloudConfiguration,
                                          PubSubConfigurationProperties pubSubConfigurationProperties) {
        this.publisherFactory = publisherFactory;
        this.executorService = executorService;
        this.serDesRegistry = serDesRegistry;
        this.conversionService = conversionService;
        this.googleCloudConfiguration = googleCloudConfiguration;
        this.pubSubConfigurationProperties = pubSubConfigurationProperties;
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {

        if (context.hasAnnotation(Topic.class)) {

            PubSubPublisherState publisherState = publisherStateCache.computeIfAbsent(context.getExecutableMethod(), method -> {
                String projectId = method.stringValue(PubSubClient.class).orElse(googleCloudConfiguration.getProjectId());
                Optional<Argument> orderingArgument = Arrays.stream(method.getArguments()).filter(argument -> argument.getAnnotationMetadata().hasAnnotation(OrderingKey.class)).findFirst();
                String topic = method.stringValue(Topic.class).orElse(context.getName());
                String endpoint = method.stringValue(Topic.class, "endpoint").orElse("");
                String configurationName = method.stringValue(Topic.class, "configuration").orElse("");
                String contentType = method.stringValue(Topic.class, "contentType").orElse(MediaType.APPLICATION_JSON);
                ProjectTopicName projectTopicName = PubSubTopicUtils.toProjectTopicName(topic, projectId);
                Map<String, String> staticMessageAttributes = new HashMap<>();
                List<AnnotationValue<MessageHeader>> headerAnnotations = context.getAnnotationValuesByType(MessageHeader.class);
                headerAnnotations.forEach((header) -> {
                    String name = header.stringValue("name").orElse(null);
                    String value = header.stringValue().orElse(null);
                    if (StringUtils.isNotEmpty(name) && StringUtils.isNotEmpty(value)) {
                        staticMessageAttributes.put(name, value);
                    }
                });
                Argument<?> bodyArgument = findBodyArgument(method)
                        .orElseThrow(() -> new PubSubClientException("No valid message body argument found for method: " + context.getExecutableMethod()));

                PubSubPublisherState.TopicState topicState = new PubSubPublisherState.TopicState(contentType, projectTopicName, configurationName, endpoint, orderingArgument.isPresent());
                logger.debug("Created a new publisher[{}] for topic: {}", context.getExecutableMethod().getName(), topic);
                PublisherInterface publisher = publisherFactory.createPublisher(new PublisherFactoryConfig(topicState, pubSubConfigurationProperties.getPublishingExecutor()));
                return new PubSubPublisherState(topicState, staticMessageAttributes, bodyArgument, publisher, orderingArgument);
            });

            Map<String, String> messageAttributes = new HashMap<>(publisherState.getStaticMessageAttributes());
            String contentType = publisherState.getTopicState().getContentType();
            Argument<?> bodyArgument = publisherState.getBodyArgument();
            Map<String, Object> parameterValues = context.getParameterValueMap();
            final ReturnType<Object> returnTypeInfo = context.getReturnType();
            ReturnType<Object> returnType = returnTypeInfo;
            Class<?> javaReturnType = returnType.getType();

            Argument[] arguments = context.getArguments();
            for (Argument arg : arguments) {
                AnnotationValue<MessageHeader> headerAnn = arg.getAnnotation(MessageHeader.class);
                if (headerAnn != null) {
                    Map.Entry<String, String> entry = getNameAndValue(arg, headerAnn, parameterValues);
                    messageAttributes.put(entry.getKey(), entry.getValue());
                }
            }

            PublisherInterface publisher = publisherState.getPublisher();

            Object body = parameterValues.get(bodyArgument.getName());
            PubsubMessage pubsubMessage = null;
            if (body.getClass() == PubsubMessage.class) {
                pubsubMessage = (PubsubMessage) body;
            } else {
                //if target type is byte[] we bypass serdes completely
                byte[] serialized = null;
                if (body.getClass() == byte[].class) {
                    serialized = (byte[]) body;
                } else {
                    PubSubMessageSerDes serDes = serDesRegistry.find(contentType)
                            .orElseThrow(() -> new PubSubClientException("Could not locate a valid SerDes implementation for type: " + contentType));
                    serialized = serDes.serialize(body);
                }
                messageAttributes.put("Content-Type", contentType);
                PubsubMessage.Builder messageBuilder = PubsubMessage.newBuilder();
                messageBuilder.setData(ByteString.copyFrom(serialized))
                        .putAllAttributes(messageAttributes);
                if (publisherState.getOrderingArgument().isPresent()) {
                    String orderingKey = conversionService.convert(parameterValues.get(publisherState.getOrderingArgument().get().getName()), String.class)
                            .orElseThrow(() -> new PubSubClientException("Could not convert argument annotated with @OrderingKey to String type"));
                    messageBuilder.setOrderingKey(orderingKey);
                }
                pubsubMessage = messageBuilder.build();
            }

            PubsubMessage finalPubsubMessage = pubsubMessage;
            Mono<String> reactiveResult = Mono.create(sink -> {
                ApiFuture<String> future = publisher.publish(finalPubsubMessage);
                future.addListener(() -> {
                    try {
                        final String result = future.get();
                        sink.success(result);
                    } catch (Throwable e) {
                        sink.error(e);
                    }
                }, executorService);
            });
            if (javaReturnType == void.class || javaReturnType == Void.class) {
                String result = reactiveResult.block();
                return null;
            } else {
                if (returnTypeInfo.isReactive()) {
                    return Publishers.convertPublisher(reactiveResult, javaReturnType);
                } else if (returnTypeInfo.isAsync()) {
                    return reactiveResult.toFuture();
                } else {
                    String result = reactiveResult.block();
                    return conversionService.convert(result, javaReturnType)
                            .orElseThrow(() -> new PubSubClientException("Could not convert publisher result to method return type: " + javaReturnType));
                }
            }
        } else {
            return context.proceed();
        }

    }

    private Optional<Argument<?>> findBodyArgument(ExecutableMethod<?, ?> method) {
        return Optional.ofNullable(Arrays.stream(method.getArguments())
                .filter(argument -> argument.getAnnotationMetadata().hasAnnotation(MessageBody.class))
                .findFirst()
                .orElseGet(
                        () -> Arrays.stream(method.getArguments())
                                .findFirst()
                                .orElse(null)
                )
        );
    }

    private Map.Entry<String, String> getNameAndValue(Argument argument, AnnotationValue<?> annotationValue, Map<String, Object> parameterValues) {
        String argumentName = argument.getName();
        String name = annotationValue.stringValue("name").orElse(annotationValue.getValue(String.class).orElse(argumentName));
        String value = String.valueOf(parameterValues.get(argumentName));

        return new AbstractMap.SimpleEntry<>(name, value);
    }

    @Override
    @PreDestroy
    public void close() throws Exception {
        for (PubSubPublisherState publisherState : publisherStateCache.values()) {
            publisherState.close();
        }
    }
}
