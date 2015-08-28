package aredee.mesos.frameworks.accumulo.model;


import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;


public class Accumulo  {

    private String instance = null;
    private String rootUser = null;
    private String rootPassword = null;
    //private String initLocation = null;
    private String zkServers = null;
    private Integer executorMemory = 128;
    private String tarballUri = null;
    private List<ServerGroup> serverGroups = new ArrayList<ServerGroup>();
    private String hdfsUri = null;
    private String siteXml = null;

    /**
     **/
    @JsonProperty("instance")
    public String getInstance() {
      return instance;
    }
    public void setInstance(String instance) {
      this.instance = instance;
    }


    /**
     **/
    @JsonProperty("rootUser")
    public String getRootUser() {
      return rootUser;
    }
    public void setRootUser(String rootUser) {
      this.rootUser = rootUser;
    }


    /**
     **/
    @JsonProperty("rootPassword")
    public String getRootPassword() {
      return rootPassword;
    }
    public void setRootPassword(String rootPassword) {
      this.rootPassword = rootPassword;
    }


    /**
     * URI of the rfiles for this instance\n
     **/
    //@JsonProperty("initLocation")
    //public String getInitLocation() {
    //  return initLocation;
    //}
    //public void setInitLocation(String initLocation) {
    //  this.initLocation = initLocation;
    //}


    /**
     **/
    @JsonProperty("zkServers")
    public String getZkServers() {
      return zkServers;
    }
    public void setZkServers(String zkServers) {
      this.zkServers = zkServers;
    }


    /**
     * Maximum memory in MB to launch executors with. This does not include\nthe memory for the server processes.\n
     **/
    @JsonProperty("executorMemory")
    public Integer getExecutorMemory() {
      return executorMemory;
    }
    public void setExecutorMemory(Integer executorMemory) {
      this.executorMemory = executorMemory;
    }


    /**
     * URI for Accumulo distribution code.\n
     **/
    @JsonProperty("tarballUri")
    public String getTarballUri() {
      return tarballUri;
    }
    public void setTarballUri(String tarballUri) {
      this.tarballUri = tarballUri;
    }

    /**
     **/
    @JsonProperty("servers")
    public List<ServerGroup> getServerGroups() {
      return serverGroups;
    }
    public void setServerGroups(List<ServerGroup> serverGroups) {
      this.serverGroups = serverGroups;
    }

    /**
     * HDFS URI where accumulo can be initialized (hdfs://localhost:9000/accumulo-mesos)
     *
     * @return
     */
    @JsonProperty("hdfsUri")
    public String getHdfsUri(){return this.hdfsUri; }
    public void setHdfsUri(String uri){ this.hdfsUri = uri; }

    @JsonProperty("siteXml")
    public String getSiteXml(){ return this.siteXml; }
    public void setSiteXml(String siteXml){ this.siteXml = siteXml; }
    public boolean hasSiteXml(){
        boolean hasXml = false;
        if( siteXml != null ){
            hasXml = !siteXml.isEmpty();
        }
        return hasXml;
    }

    @Override
    public String toString()  {
      StringBuilder sb = new StringBuilder();
      sb.append("class Accumulo {\n");

      sb.append("  instance: ").append(instance).append("\n");
      sb.append("  rootUser: ").append(rootUser).append("\n");
      sb.append("  rootPassword: ").append(rootPassword).append("\n");
      //sb.append("  initLocation: ").append(initLocation).append("\n");
      sb.append("  zkServers: ").append(zkServers).append("\n");
      sb.append("  executorMemory: ").append(executorMemory).append("\n");
      sb.append("  tarballUri: ").append(tarballUri).append("\n");
      sb.append("  hdfsUri: ").append(hdfsUri).append("\n");
      sb.append("  serverGroups: ").append(serverGroups).append("\n");
      sb.append("}\n");
      return sb.toString();
    }
}
