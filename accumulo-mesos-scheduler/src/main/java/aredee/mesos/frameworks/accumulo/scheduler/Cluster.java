package aredee.mesos.frameworks.accumulo.scheduler;

import aredee.mesos.frameworks.accumulo.configuration.ServerType;
import aredee.mesos.frameworks.accumulo.configuration.process.ProcessConfiguration;
import aredee.mesos.frameworks.accumulo.model.Framework;
import aredee.mesos.frameworks.accumulo.scheduler.launcher.Launcher;
import aredee.mesos.frameworks.accumulo.scheduler.matcher.Match;
import aredee.mesos.frameworks.accumulo.scheduler.matcher.Matcher;
import aredee.mesos.frameworks.accumulo.scheduler.matcher.OperationalCheck;
import aredee.mesos.frameworks.accumulo.scheduler.server.*;
import com.google.common.collect.Sets;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.state.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public enum Cluster {
    INSTANCE;

    private static final Logger LOGGER = LoggerFactory.getLogger(Cluster.class);

    private Framework config;
    private State state;

    private String frameworkId;

    private Master master = null;
    private GarbageCollector gc = null;
    private Monitor monitor = null;
    private Tracer tracer = null;
    private Set<AccumuloServer> tservers = Sets.newConcurrentHashSet(); //new HashSet<AccumuloServer>();
    
    private Map<String, Map<ServerType,AccumuloServer>> launchedServers = new HashMap<String, Map<ServerType,AccumuloServer>>();
    
    private Set<Protos.TaskStatus> runningServers = new HashSet<Protos.TaskStatus>();
    private Set<AccumuloServer> serversToLaunch = new HashSet<AccumuloServer>();
    private Map<ServerType, ProcessConfiguration> clusterServers;

    private Matcher matcher;
    private Launcher launcher;


    public void initialize(Framework config, State state){
        this.config = config;
        this.state = state;
    }


/*
    @SuppressWarnings("unchecked")
    public Cluster(AccumuloInitializer initializer){

        this.state = initializer.getFrameworkState();
        this.config = initializer.getClusterConfiguration();
        this.launcher = new AccumuloStartExecutorLauncher(initializer);
        this.matcher = new MinCpuMinRamFIFOMatcher(config);
        
        clusterServers = config.getProcessorConfigurations();
           
        LOGGER.info("Servers in the cluster? " + clusterServers);
        
        // Take the cluster configuration from the input cluster configuration.
        for(Entry<ServerType, ProcessConfiguration> entry : clusterServers.entrySet()) {
            if (entry.getKey() == ServerType.TABLET_SERVER) {
                for(int ii = 0; ii < config.getMinTservers(); ii++) {
                    ServerUtils.addServer(serversToLaunch, entry.getValue());              
                }
            } else {
                ServerUtils.addServer(serversToLaunch, entry.getValue());
            }
        }
     }
*/
    public void setFrameworkId(String fid){
        this.frameworkId = fid;
        config.getFrameworkName();
        //TODO persist configuration
    }

    @SuppressWarnings("unchecked")
    public void handleOffers(SchedulerDriver driver, List<Protos.Offer> offers){

        LOGGER.debug("Mesos Accumulo Cluster handling offers: for servers {}", serversToLaunch);
        
        OperationalCheck opCheck =  new OperationalCheck() {
            public boolean accept(AccumuloServer server, String slaveId) {
                boolean accepted = !isServerLaunched(slaveId, server);
                // This is a bit pre-mature but if not done here the offers just keep 
                // getting accepted.
                if (accepted) {
                    addLaunchedServer(slaveId,server);
                }
                return accepted;
            }
        };
        
        List<Match> matchedServers = matcher.matchOffers(serversToLaunch, offers, opCheck);
        
        LOGGER.debug("Found {} matches for servers from {} offers", matchedServers.size(), offers.size());

        // Launch all the matched servers.
        for (Match match: matchedServers){
             
            LOGGER.info("Launching Server: {} on {}", match.getServer().getType().getName(), 
                    match.getOffer().getSlaveId().getValue() );
            
            Protos.TaskInfo taskInfo = launcher.launch(driver, match);
            
            LOGGER.info("Created Task {} on {}", taskInfo.getTaskId(), taskInfo.getSlaveId().getValue());
            
            serversToLaunch.remove(match.getServer());
        }

        declineUnmatchedOffers(driver, offers, matchedServers);
        // TODO call restore here?
    }


    public void restore(SchedulerDriver driver) {

        // reconcileTasks causes the framework to call updateTaskStatus, which
        // will update the tasks list.
        // TODO handle return of reconcileTasks
        Protos.Status reconcileStatus = driver.reconcileTasks(runningServers);
        clearServers();
        
        String slaveId;
        String taskId;
        
        // process the existing tasks
        for (Protos.TaskStatus status : runningServers ){
            slaveId = status.getSlaveId().getValue();
            taskId = status.getTaskId().getValue();

            if( Master.isMaster(taskId)){
                master = (Master)ServerUtils.newServer(clusterServers.get(ServerType.MASTER), taskId, slaveId);
            } else if( TabletServer.isTabletServer(taskId)) {
                tservers.add((TabletServer)ServerUtils.newServer(clusterServers.get(ServerType.TABLET_SERVER), taskId, slaveId));
            } else if( GarbageCollector.isGarbageCollector(taskId)) {
                gc = (GarbageCollector)ServerUtils.newServer(clusterServers.get(ServerType.GARBAGE_COLLECTOR), taskId, slaveId);
            } else if(Monitor.isMonitor(taskId)){
                monitor = (Monitor)ServerUtils.newServer(clusterServers.get(ServerType.MONITOR), taskId, slaveId);
            } else if (Tracer.isTacer(taskId)) {
                tracer = (Tracer)ServerUtils.newServer(clusterServers.get(ServerType.TRACER), taskId, slaveId);
            }
        }

        //TODO save cluster state
    }

    /**
     * Updates Cluster state based on task status.
     *
     * @param status
     */
    public void updateTaskStatus(Protos.TaskStatus status){

        String slaveId = status.getSlaveId().getValue();
        String taskId = status.getTaskId().getValue();
        AccumuloServer serverToLaunch = null;
        
        LOGGER.info("Task Status Update: Status: {} Slave: {} Task: {}", status.getState(), slaveId, taskId);

        switch (status.getState()){
            case TASK_RUNNING:
                runningServers.add(status);
                break;
            case TASK_FINISHED:
            case TASK_FAILED:
            case TASK_KILLED:
            case TASK_LOST:
                runningServers.remove(status);
                
                // re-queue tasks when servers are lost.
                if( Master.isMaster(taskId)){
                    // Don't save the slave id, it maybe re-assigned to a new slave 
                    serverToLaunch = ServerUtils.newServer(clusterServers.get(ServerType.MASTER), taskId, null);
                    clearMaster();
                } else if (TabletServer.isTabletServer(taskId)) {
                    tservers.remove(new TabletServer(taskId, slaveId));
                    serverToLaunch = ServerUtils.newServer(clusterServers.get(ServerType.TABLET_SERVER), taskId, null);
                } else if (Monitor.isMonitor(taskId)) {
                    serverToLaunch = ServerUtils.newServer(clusterServers.get(ServerType.MONITOR), taskId, null);
                    clearMonitor();
                } else if (GarbageCollector.isGarbageCollector(taskId)) {
                    serverToLaunch = ServerUtils.newServer(clusterServers.get(ServerType.GARBAGE_COLLECTOR), taskId, null);
                    clearGC();
                }
                if (serverToLaunch != null) {
                    removeLaunchedServer(slaveId, serverToLaunch.getType());
                    serversToLaunch.add(serverToLaunch);
                }
                break;
            case TASK_STARTING:
            case TASK_STAGING:
                break;
            default:
                LOGGER.info("Unknown Task Status received: {}", status.getState().toString());

        }
    }

    public boolean isMasterRunning(){
        return master == null;
    }
    public boolean isGCRunning(){
        return gc == null;
    }
    public boolean isMonitorRunning(){
        return monitor == null;
    }
    public int numTserversRunning(){
        return tservers.size();
    }
    
    // Remove used offers from the available offers and decline the rest.
    private void declineUnmatchedOffers(SchedulerDriver driver, List<Protos.Offer> offers, List<Match> matches){
        List<Protos.Offer> usedOffers = new ArrayList<>(matches.size());
        for(Match match: matches){
            if(match.hasOffer()){
                usedOffers.add(match.getOffer());
            }
        }
        offers.removeAll(usedOffers);
        for( Protos.Offer offer: offers){
            driver.declineOffer(offer.getId());
        }
    }

    private void addLaunchedServer(String slaveId, AccumuloServer server) {
        synchronized(launchedServers) {
            Map<ServerType,AccumuloServer> servers = launchedServers.get(slaveId);
            if (servers == null) {
                servers = new HashMap<ServerType,AccumuloServer>();
                launchedServers.put(slaveId, servers);              
            }
            servers.put(server.getType(),server);
        }
    }
    
    private boolean isServerLaunched(String slaveId, AccumuloServer server) {
        boolean launched = false;
        synchronized(launchedServers) {
            Map<ServerType,AccumuloServer> servers = launchedServers.get(slaveId);
            if (servers != null) {
               launched = servers.containsKey(server.getType());
            }
        }
        return launched;
    }
    
    private void removeLaunchedServer(String slaveId, ServerType type) {
        synchronized(launchedServers) {
            Map<ServerType,AccumuloServer> servers = launchedServers.get(slaveId);
            if (servers != null) {
                servers.remove(type);
            }
        }
    }
    
    private void clearServers(){
        clearMaster();
        clearMonitor();
        clearGC();
        clearTservers();
    }

    private void clearMaster(){ master = null; }
    private void clearMonitor(){ monitor = null; }
    private void clearGC(){ gc = null; }
    private void clearTservers(){ tservers.clear(); }

}
