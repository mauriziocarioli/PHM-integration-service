package com.health_insurance.kie;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.health_insurance.phm_model.Reminder;
import com.health_insurance.phm_model.Response;
import com.health_insurance.phm_model.Result;
import com.health_insurance.phm_model.Task;
import com.health_insurance.phm_model.TaskActorAssignment;
import com.health_insurance.phm_model.Trigger;

import org.kie.server.api.marshalling.MarshallingFormat;
import org.kie.server.api.model.KieContainerResource;
import org.kie.server.api.model.KieContainerResourceList;
import org.kie.server.api.model.KieServerInfo;
import org.kie.server.api.model.definition.ProcessDefinition;
import org.kie.server.client.KieServicesClient;
import org.kie.server.client.KieServicesConfiguration;
import org.kie.server.client.KieServicesFactory;
import org.kie.server.client.ProcessServicesClient;
import org.kie.server.client.QueryServicesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Service;

@Configuration
@PropertySource("classpath:servers.properties")
@Service("processServiceClient")
public class ProcessClient {
  private static final Logger LOG = LoggerFactory.getLogger(ProcessClient.class);

  //TODO @Value doesn't read from the properties file

  //@Value("${kie.processkieserver.url}")
  //static String kieServerUrl;
  //static String kieServerUrl = "http://localhost:8080/kie-server/services/rest/server";
  static String kieServerUrl = "http://localhost:8091/rest/server";
  //@Value("${kie.processkieserver.user}")
  //static String kieServerUser;
  static String kieServerUser = "kieserver";
  //@Value("${kie.processkieserver.password}")
  //static String kieServerPassword;
  static String kieServerPassword = "kieserver1!";

  private static final MarshallingFormat FORMAT = MarshallingFormat.JSON;
  private static KieServicesClient kieServicesClient;

  public ProcessClient() {
    // Empty constructor.
  }

  @PostConstruct
  public static void initialize() {
    final KieServicesConfiguration conf;
    LOG.info("\n=== Initializing Kie Client ===\n");
    LOG.info("\t connecting to {}", kieServerUrl);
    conf = KieServicesFactory.newRestConfiguration(kieServerUrl, kieServerUser, kieServerPassword);

    // If you use custom classes, such as Obj.class, add them to the configuration.
    Set<Class<?>> extraClassList = new HashSet<Class<?>>();

    //TODO: encapsulate this and expose to the callers
    extraClassList.add(Task.class);
    extraClassList.add(Reminder.class);
    extraClassList.add(Result.class);
    extraClassList.add(TaskActorAssignment.class);
    extraClassList.add(Trigger.class);
    extraClassList.add(Response.class);
    conf.addExtraClasses(extraClassList);

    conf.setMarshallingFormat(FORMAT);
    kieServicesClient = KieServicesFactory.newKieServicesClient(conf);

    listCapabilities();

    LOG.info("=== Kie Client initialization done ===\n");
  }

  public static List<String> listCapabilities() {
    KieServerInfo serverInfo = kieServicesClient.getServerInfo().getResult();
    LOG.info("Kie Server capabilities:");
    serverInfo.getCapabilities().forEach(c -> LOG.info("\t" + c));
    return serverInfo.getCapabilities();
  }

  public List<KieContainerResource> listContainers() {
    KieContainerResourceList containersList = kieServicesClient.listContainers().getResult();
    List<KieContainerResource> kieContainers = containersList.getContainers();
    LOG.info("Available containers: ");
    kieContainers.forEach(container ->
      LOG.info("\t" + container.getContainerId() + " (" + container.getReleaseId() + ")")
    );

    return kieContainers;
  }

  public List<ProcessDefinition> listProcesses() {
    LOG.info("== Listing Business Processes ==");
    QueryServicesClient queryClient = kieServicesClient.getServicesClient(QueryServicesClient.class);
    List<ProcessDefinition> processDefinitions = queryClient.findProcessesByContainerId("rewards", 0, 1000);
    LOG.info("Available process: ");
    processDefinitions.forEach(def -> 
      LOG.info(def.getName() + " - " + def.getId() + " v" + def.getVersion())
    );

    return processDefinitions;
  }  

  /**
   * Start a new process instance given a process definition and a map of process' variables
   * 
   * @param containerId kie container Id
   * @param processDefinitionId process definition Id
   * @param variables map of process input variables
   * @return {@link Long} process instance id created
   */
  public Long startProcess(String containerId, String processDefinitionId, Map<String, Object> variables) {
    LOG.info("== Sending commands to the kie-server [" + containerId + "] ==");
    ProcessServicesClient processClient = kieServicesClient.getServicesClient(ProcessServicesClient.class);

    LOG.info("\t Starting process [" + processDefinitionId + "] with the following input variables: ");
    LOG.info("\t\t" + variables);
    
    return processClient.startProcess(containerId, processDefinitionId, variables);
  }

  @PreDestroy
  public void closeResources(){
    LOG.info("=== Kie Client finalization ===\n");
    kieServicesClient.close();
  }
}