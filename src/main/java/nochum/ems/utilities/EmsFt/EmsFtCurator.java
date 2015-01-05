package nochum.ems.utilities.EmsFt;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessLock;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
/**
 * https://www.altamiracorp.com/blog/employee-posts/building-a-distributed-lock-revisited-using-curator-s-interprocessmutex
 * 
 * This class uses a Zookeeper instance (pair for fault-tolerance) to ensure
 * proper synchronization between EMS instances.
 * 
 * This approach uses a hot-cold standby approach vis-a-vis the hot-warm
 * approach  used in traditional EMS FT implementations.  It assumes that
 * blocks written by the primary EMS instance are being replicated using
 * DRBD (http://www.drbd.org/).  This completely removes any dependence on
 * expensive SAN infrastructure albeit at some cost to reliability
 * assuming that asynchronous replication is configured.
 * 
 * This approach also allows more than two EMS instances to join a "fault-
 * tolerant group".  Zookeeper ensures that only one instance is "live"
 * at any given point in time.
 * 
 * The class gets its configuration from a java properties file.  The 
 * following properties are supported:
 * - zk_connect_string  A list of comma-separated host:port values
 * - zk_timeout         Zookeeper session timeout in milliseconds.
 * - zk_root_node       The zookeeper root node
 * - zk_ems_root        The zookeeper node holding all EMS FT group
 *                      nodes.  This is immediately below the root
 *                      node.
 * - ems_host           The current hostname where this is running
 * - ems_name           The name of the EMS instance which is the same as
 *                      the FT group name.  This corresponds to the "server"
 *                      configuration parameter in tibemsd.conf.  This becomes
 *                      the actual znode name within zk_ems_root to be locked.
 * - ems_up_script      The full path to the script used to set the local 
 *                      filesystem as "primary", and start the EMS server 
 * - ems_down_script    The full path to the script used to set the local 
 *                      filesystem as "secondary" 
 * - failover_delay     Number of seconds to wait for graceful release of
 *                      resources before attempting to become primary
 * 
 * This class will attempt to lock znode <zk_root_node>/<zk_ems_root/<ems_name>.
 * If any of the parent nodes do not exist they will be created.
 * 
 * https://zookeeper.apache.org/doc/r3.1.2/javaExample.html
 * http://nofluffjuststuff.com/blog/scott_leberknight/2013/07/distributed_coordination_with_zookeeper_part_5_building_a_distributed_lock
 * 
 * 
 * @author Nochum Klein
 * @version %I%, %G%
 */
public class EmsFtCurator {
    Properties prop       = null;

    EmsFtCurator(String propertiesFile) throws FileNotFoundException, IOException {
		// load the properties file
		prop = new Properties();
		prop.load(new FileInputStream(propertiesFile));
		
		// connect to zookeeper
		CuratorFramework client = getClient();
		
		// the first call must be start()
		client.start();
		
		// now get the lock iteratively.  If we lose the lock
		// we want to get back in line to acquire it again.
		while (true)
			acquireLock(client);
    }
    
    private CuratorFramework getClient() {
		String zkConnectString = prop.getProperty("zk_connect_string");
    	int baseSleepTimeMills = 1000;
    	int maxRetries = 3;

    	RetryPolicy retryPolicy = new ExponentialBackoffRetry(baseSleepTimeMills, maxRetries);
    	CuratorFramework client = CuratorFrameworkFactory.newClient(zkConnectString, retryPolicy);
    	    	
    	return client;
    }
    
    private void acquireLock(CuratorFramework client) {
    	String emsNodePath = getPath();
    	String emsUpScript = prop.getProperty("ems_up_script");
    	String emsDownScript = prop.getProperty("ems_down_script");

    	if ( (null != emsNodePath) && (null != emsUpScript) && (null != emsDownScript) ) {
	    	InterProcessLock lock = new InterProcessMutex(client, emsNodePath);

	    	try {
	    		// acquire the lock
		    	lock.acquire();
		    	
		    	// start EMS
		    	DefaultExecuteResultHandler resultHandler = startEms(emsUpScript);
		    	
		    	// block as long as we still have the lock and EMS is still running
		    	while (lock.isAcquiredInThisProcess() && !resultHandler.hasResult())
		    		Thread.sleep(1000);
		    	
		    	if (resultHandler.hasResult()) {
			    	if (resultHandler.getExitValue() == 0) {
			    		System.out.println("EMS execution complete, exit code = 0");
			    	} else {
			    		ExecuteException ex = resultHandler.getException();
			    		System.err.println("EMS execution failed: " + ex);
			    	}
		    	}
	    	} catch (Exception ex) {
	    	  ex.printStackTrace();
	    	} finally {
	    		// if we got here we either lost the lock, or EMS is no longer
	    		// running.  Run the emsDown script to kill the EMS instance and
	    		// change the drbd role to secondary
	    		System.err.println("Lock was lost.  Running cleanup...");
	    		cleanup(emsDownScript);
	    			
				if (lock.isAcquiredInThisProcess()) {
		    		try {
						lock.release();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
	    	}
    	}
    }

	private void cleanup(String emsDownScript) {
		// move the filesystem from primary to secondary mode
		DefaultExecutor exec = new DefaultExecutor();
		CommandLine cl = new CommandLine(emsDownScript);
		try {
			exec.execute(cl);
		} catch (IOException e) {
			e.printStackTrace();
		}		
	}

	private DefaultExecuteResultHandler startEms(String emsUpScript) throws ExecuteException, IOException {
		/*
		 * The call to start EMS will block. We therefore want to run it
		 * asynchronously.
		 */
		DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
				
		// start EMS
		DefaultExecutor exec = new DefaultExecutor();
		
		CommandLine cl = new CommandLine(emsUpScript);
		String ftDelay = prop.getProperty("failover_delay");
		if (null != ftDelay)
			cl.addArgument(ftDelay);
		exec.execute(cl, resultHandler);
		
		return resultHandler;
	}

	/**
     * Before instantiating the BlockingWriteLock we want to ensure that the parent
     * nodes of the znode that we want to lock exist.  If not we will create
     * them 
     */
	private String getPath() {
		final String KEY_ROOT_NODE = "zk_root_node";
		final String KEY_EMS_ROOT = "zk_ems_root";
		final String KEY_EMS_NAME = "ems_name";
		
		String emsNodePath = null;
		if (prop.containsKey(KEY_ROOT_NODE) && prop.containsKey(KEY_EMS_ROOT) && prop.containsKey(KEY_EMS_NAME)) {
			emsNodePath = new String(prop.getProperty(KEY_ROOT_NODE) + "/" + prop.getProperty(KEY_EMS_ROOT) + "/" + prop.getProperty(KEY_EMS_NAME));
		}
        		
        return emsNodePath;
	}

	public static void main(String args[]) throws Exception {
        new EmsFtCurator(args[0]);
    }

}