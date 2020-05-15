package com.health_insurance.integration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.health_insurance.phm_model.Response;
import com.health_insurance.phm_model.Trigger;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Camel REST DSL route that implements 
 * the integration between Kafka and Drools/jBPM.
 * 
 */
@Component
public class CamelRouter extends RouteBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(CamelRouter.class);

    @Value("${kie.decision.container.id}") 
    String decisionContainerId;
    @Value("${kie.decision.session.name}") 
	String decisionSessionName;
    @Value("${kie.process.container.id}") 
    String processContainerId;
    @Value("${kie.process.definition.id}") 
	String processDefinitionId;
    
    @Value("${kafka.topic:test}") 
	String kafkaTopic;
    @Value("${kafka.host:localhost}") 
	String kafkaHost;
    @Value("${kafka.port:9092}") 
    String kafkaPort;

    @Value("${camel.seda.consumers}")
    String sedaConsumers;
    
    private static final String KAFKA_SERIALIZER_CLASS_CONFIG = "org.apache.kafka.common.serialization.ByteArraySerializer";
    private static final String KAFKA_DESERIALIZER_CLASS_CONFIG = "org.apache.kafka.common.serialization.ByteArrayDeserializer";
    
    @Override
    public void configure() throws Exception {

        // @formatter:off
        restConfiguration()
                .apiContextPath("/api-doc")
                .apiProperty("api.title", "Integration Service REST API")
                .apiProperty("api.version", "1.0")
                .apiProperty("cors", "true")
                .apiProperty("base.path", "camel/")
                .apiProperty("api.path", "/")
                .apiProperty("host", "")
                .apiContextRouteId("doc-api")
            .component("servlet")
            .bindingMode(RestBindingMode.json);
        
        rest("/trigger").description("Create a new Trigger and send it to Kafka Topic")
            .consumes("application/json")
            .produces("application/json")
            .post().type(Trigger.class)
                .route().routeId("trigger-api")
                .to("direct:publishToKafka");

    // Direct routes
        from("direct:greetingsImpl").description("Greetings REST service implementation route")
            .routeId("greetings")
            .streamCaching()
            .to("bean:greetingsService?method=getGreetings"); 
            
        from("direct:runKieCommand")
            .routeId("kieServerClient")
            .log("calling kie-server")
            .to("bean:decisionServiceClient?method=listContainers")
            .log("${body}");
        
        from("direct:publishToKafka")
            .routeId("kafkaPublisher")
            .marshal().json(JsonLibrary.Jackson, Trigger.class)
            .log("publishing [ ${body} ] to kafka topic}")
            .setHeader(KafkaConstants.KEY, constant("phm-trigger")) // Key of the message
            .toF("kafka:%s?brokers=%s:%s&serializerClass=%s", kafkaTopic, kafkaHost, kafkaPort, KAFKA_SERIALIZER_CLASS_CONFIG);
            
        fromF("kafka:%s?brokers=%s:%s&valueDeserializer=%s", kafkaTopic, kafkaHost, kafkaPort, KAFKA_DESERIALIZER_CLASS_CONFIG)
            .routeId("kafkaSubscriber")
            .unmarshal().json(JsonLibrary.Jackson, Trigger.class)
            .log("Message received from Kafka : ${body}")
            .log("    on the topic ${headers[kafka.TOPIC]}")
            .log("    on the partition ${headers[kafka.PARTITION]}")
            .log("    with the offset ${headers[kafka.OFFSET]}")
            .log("    with the key ${headers[kafka.KEY]}")  
            .log("\n Call the decision server")
            .to("seda:makeDecision");

        fromF("seda:makeDecision?concurrentConsumers=%s", sedaConsumers)
            .routeId("makeDecision")
            .process(e -> {
                LOG.debug("Decision request Body: " + e.getIn().getBody());
                Trigger trigger = e.getIn().getBody(Trigger.class);

                Map<String, Object> decisionFacts = new HashMap<>();
                decisionFacts.put(Integer.toString(trigger.getTriggerId()), trigger);
                e.getIn().setBody(decisionFacts);
            }) // call decision service
            .toF("bean:decisionServiceClient?method=executeCommands(%s, %s, ${body})", decisionContainerId, decisionSessionName)
            .log("Decision Results: [ ${body} ]")
            .to("seda:startProcess");

        fromF("seda:startProcess?concurrentConsumers=%s", sedaConsumers)
            .routeId("startProcess")
            .process(e -> {
                LOG.debug("Process request Body: " + e.getIn().getBody());

                Map<String, Object> resultFactObjects = e.getIn().getBody(Map.class);
                List<?> factsList = (List<?>)resultFactObjects.get("resultFactObjects");
                List<?> responsesList = factsList.stream().filter(o -> o instanceof Response).collect(Collectors.toList());

                Map<String, Object> processVariables = new HashMap<>();
                processVariables.put("pDataList", responsesList);

                e.getIn().setBody(processVariables);
            }) // start a new Process instance
            .toF("bean:processServiceClient?method=startProcess(%s, %s, ${body})", processContainerId, processDefinitionId)
            .log("a process instance has been created with Id ${body}");
        
        // @formatter:on
    }

}