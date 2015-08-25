package aredee.mesos.frameworks.accumulo.process;

import aredee.mesos.frameworks.accumulo.configuration.Environment;

import com.google.common.base.Joiner;

import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.impl.VFSClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class AccumuloProcessFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccumuloProcessFactory.class);

    private List<LogWriter> logWriters = new ArrayList<>(5);
    private List<Process> cleanup = new ArrayList<>();
    private Map<String, String> processEnv = Maps.newHashMap();
    private final String memory;

    public AccumuloProcessFactory(String memory){
        if( memory.endsWith("M")){
            this.memory = memory;
        } else {
            this.memory = memory + "M";
        }

        initializeEnvironment();
    }

    public Process exec(Class<?> clazz, List<String> jvmArgs, String... args) throws IOException {

        ArrayList<String> jvmArgs2 = new ArrayList<>(2 + (jvmArgs == null ? 0 : jvmArgs.size()));
        jvmArgs2.add("-Xmx" + this.memory);
        if (jvmArgs != null)
            jvmArgs2.addAll(jvmArgs);
        
        Process proc = _exec(clazz, jvmArgs2, args);
        cleanup.add(proc);

        return proc;
    }

    private void initializeEnvironment(){
        String accumuloHome = System.getenv(Environment.ACCUMULO_HOME);
        processEnv.put(Environment.ACCUMULO_HOME, System.getenv(Environment.ACCUMULO_HOME));
        processEnv.put(Environment.ACCUMULO_LOG_DIR, accumuloHome+File.separator+"logs");
        processEnv.put(Environment.ACCUMULO_CLIENT_CONF_PATH, System.getenv(Environment.ACCUMULO_CLIENT_CONF_PATH));
        processEnv.put(Environment.ACCUMULO_CONF_DIR, accumuloHome+"/conf/");

        String nativePaths = System.getenv(Environment.NATIVE_LIB_PATHS);
        String ldLibraryPath = "";
        if(!StringUtils.isEmpty(nativePaths)) {
            // change comma for a :
            ldLibraryPath = Joiner.on(File.pathSeparator).join(Arrays.asList(nativePaths.split(",")));
        }
        processEnv.put(Environment.LD_LIBRARY_PATH, ldLibraryPath);
        processEnv.put(Environment.DYLD_LIBRARY_PATH, ldLibraryPath);

        // if we're running under accumulo.start, we forward these env vars
        String hadoopPrefix = System.getenv(Environment.HADOOP_PREFIX);
        processEnv.put(Environment.HADOOP_PREFIX, hadoopPrefix);
        processEnv.put(Environment.ZOOKEEPER_HOME, System.getenv(Environment.ZOOKEEPER_HOME));

        // hadoop-2.2 puts error messages in the logs if this is not set
        processEnv.put(Environment.HADOOP_HOME, hadoopPrefix);
        processEnv.put(Environment.HADOOP_CONF_DIR, hadoopPrefix);
    }

    private Process _exec(Class<?> clazz, List<String> extraJvmOpts, String... args) throws IOException {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        
        LOGGER.info("_exec: Java Bin? " + javaBin);

        String classpath = getClasspath();
        String className = clazz.getName();

        ArrayList<String> argList = new ArrayList<>();
        argList.addAll(Arrays.asList(javaBin, "-Dproc=" + clazz.getSimpleName(),"-cp",classpath));
        argList.addAll(extraJvmOpts);
        
        String prop;
        //for (Map.Entry<String, String> sysProp : profile.getSystemProperties().entrySet()) {
        // TODO follow the system properties... this might be accumulo properties list.
        for (Map.Entry<Object, Object> sysProp : System.getProperties().entrySet()) {
            
            String svar;
//            if (sysProp.getKey().equals("java.class.path")){
//                svar = String.format("-D%s=%s", sysProp.getKey(), classpath+":"+sysProp.getValue());
//            } else {
                svar = String.format("-D%s=%s", sysProp.getKey(), sysProp.getValue());
//            }
            argList.add(svar);
        }
        // @formatter:off
       
        argList.addAll(Arrays.asList(
                "-XX:+UseConcMarkSweepGC",
                "-XX:CMSInitiatingOccupancyFraction=75",
                "-Dapple.awt.UIElement=true",
                "-Djava.net.preferIPv4Stack=true",
                "-XX:+PerfDisableSharedMem",
                "-XX:+AlwaysPreTouch",
                org.apache.accumulo.start.Main.class.getName(), className));
        // @formatter:on

        argList.addAll(Arrays.asList(args));

        LOGGER.info("Launching with args? " + argList);
        
        ProcessBuilder builder = new ProcessBuilder(argList);

        // copy environment into builder environment
        Map<String, String> environment = builder.environment();
        for( String key : processEnv.keySet()){
            environment.put(key, processEnv.get(key));
        }

        Process process = builder.start();
        addLogWriter(processEnv.get(Environment.ACCUMULO_LOG_DIR),
                process.getErrorStream(), clazz.getSimpleName(), process.hashCode(), ".err");
        addLogWriter(processEnv.get(Environment.ACCUMULO_LOG_DIR),
                process.getInputStream(), clazz.getSimpleName(), process.hashCode(), ".out");

        return process;
    }

    private void addLogWriter(String accumuloLogDir, InputStream stream, String className, int hash, String ext) throws IOException {
        File f = new File(accumuloLogDir, className + "_" + hash + ext);
        logWriters.add(new LogWriter(stream,f));       
    }
    
    private String getClasspath() throws IOException {

        try {
            ArrayList<ClassLoader> classloaders = new ArrayList<ClassLoader>();

            ClassLoader cl = this.getClass().getClassLoader();

            while (cl != null) {
                classloaders.add(cl);
                cl = cl.getParent();
            }

            Collections.reverse(classloaders);

            StringBuilder classpathBuilder = new StringBuilder();
            classpathBuilder.append(getProcessEnvPath(Environment.ACCUMULO_CONF_DIR));

            if (processEnv.get(Environment.HADOOP_CONF_DIR) != null) {
                classpathBuilder.append(File.pathSeparator).append(getProcessEnvPath(Environment.HADOOP_CONF_DIR));
            }

            //if (config.getClasspathItems() == null) {  // JLK - classpathItems is not needed here.

                // assume 0 is the system classloader and skip it
                for (int i = 1; i < classloaders.size(); i++) {
                    ClassLoader classLoader = classloaders.get(i);

                    if (classLoader instanceof URLClassLoader) {

                        for (URL u : ((URLClassLoader) classLoader).getURLs()) {
                            append(classpathBuilder, u);
                        }

                    } else if (classLoader instanceof VFSClassLoader) {

                        VFSClassLoader vcl = (VFSClassLoader) classLoader;
                        for (FileObject f : vcl.getFileObjects()) {
                            append(classpathBuilder, f.getURL());
                        }
                    } else {
                        throw new IllegalArgumentException("Unknown classloader type : " + classLoader.getClass().getName());
                    }
                }
            /*
            } else {
                for (Object s : config.getClasspathItems())
                    classpathBuilder.append(File.pathSeparator).append(s.toString());
            }
            */

            LOGGER.info("Creating classpath: " + classpathBuilder.toString());

            return classpathBuilder.toString();

        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private String getProcessEnvPath(final String envVar){
        return new File(
                processEnv.get(envVar)
        ).getAbsolutePath();
    }

    private void append(StringBuilder classpathBuilder, URL url) throws URISyntaxException {
        File file = new File(url.toURI());
        // do not include dirs containing hadoop or accumulo site files
        if (!containsSiteFile(file))
            classpathBuilder.append(File.pathSeparator).append(file.getAbsolutePath());
    }

    private boolean containsSiteFile(File f) {
        return f.isDirectory() && f.listFiles(new FileFilter() {

            @Override
            public boolean accept(File pathname) {
                return pathname.getName().endsWith("site.xml");
            }
        }).length > 0;
    }

    /*
    private List<String> buildRemoteDebugParams(int port) {
        return Arrays.asList(new String[] {"-Xdebug", String.format("-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=%d", port)});
    }
    */
    
    
    public static class LogWriter extends Thread {
        private BufferedReader in;
        private BufferedWriter out;

        public LogWriter(InputStream stream, File logFile) throws IOException {
            setDaemon(true);
            this.in = new BufferedReader(new InputStreamReader(stream));
            this.out = new BufferedWriter(new FileWriter(logFile));

           new Timer().schedule(new TimerTask() {public void run() {
               try {
                   flush();
               } catch (IOException e) {
                   LOGGER.error("Exception while attempting to flush.", e);
               }      
           }}, 1000,1000);
           start();
        }

        public synchronized void flush() throws IOException {
            if (out != null)
                out.flush();
        }

        @Override
        public void run() {
            String line;

            try {
                while ((line = in.readLine()) != null) {
                    out.append(line);
                    out.append("\n");
                }

                synchronized (this) {
                    out.close();
                    out = null;
                    in.close();
                }

            } catch (IOException e) {}
        }
    }

}
