package azkaban.viewer.hdfs;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.log4j.Logger;

import azkaban.user.User;
import azkaban.utils.Props;
import azkaban.webapp.servlet.LoginAbstractAzkabanServlet;
import azkaban.webapp.servlet.Page;
import azkaban.webapp.session.Session;

public class HdfsBrowserServlet extends LoginAbstractAzkabanServlet {
	private static final long serialVersionUID = 1L;
	private static final String PROXY_USER_SESSION_KEY = "hdfs.browser.proxy.user";
	private static Logger logger = Logger.getLogger(HdfsBrowserServlet.class);
	private static UserGroupInformation loginUser = null;

	private ArrayList<HdfsFileViewer> viewers = new ArrayList<HdfsFileViewer>();

	// Default viewer will be a text viewer
	private HdfsFileViewer defaultViewer;

	private Props props;
	private String proxyUser;
	private String keytabLocation;
	private boolean shouldProxy;
	private boolean allowGroupProxy;
	private Configuration conf;
	
	private String viewerName;
	private String viewerPath;
	
	public HdfsBrowserServlet(Props props) {
		this.props = props;
		
		viewerName = props.getString("viewer.name");
		viewerPath = props.getString("viewer.path");
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		conf = new Configuration();
		try {
			ClassLoader loader = getHadoopClassLoader();
			conf.setClassLoader(loader);
		} catch (MalformedURLException e) {
			logger.error("Error loading class loader with Hadoop confs", e);
		}
		
		shouldProxy = props.getBoolean("azkaban.should.proxy", false);
		logger.info("Hdfs browser should proxy: " + shouldProxy);
		if (shouldProxy) {
			proxyUser = props.getString("proxy.user");
			keytabLocation = props.getString("proxy.keytab.location");
			allowGroupProxy = props.getBoolean("allow.group.proxy", false);

			logger.info("No login user. Creating login user");
			UserGroupInformation.setConfiguration(conf);
			try {
				UserGroupInformation.loginUserFromKeytab(proxyUser, keytabLocation);
				loginUser = UserGroupInformation.getLoginUser();
			} catch (IOException e) {
				logger.error("Error setting up hdfs browser security", e);
			}
		}
		
		defaultViewer = new TextFileViewer();
		viewers.add(new HdfsAvroFileViewer());
		viewers.add(new JsonSequenceFileViewer());
		viewers.add(defaultViewer);

		logger.info("HDFS Browser initiated");
	}

	private ClassLoader getHadoopClassLoader() throws MalformedURLException {
		ClassLoader loader;
		String hadoopHome = System.getenv("HADOOP_HOME");
		String hadoopConfDir = System.getenv("HADOOP_CONF_DIR");

		if (hadoopConfDir != null) {
			logger.info("Using hadoop config found in " + hadoopConfDir);
			loader = new URLClassLoader(new URL[] { new File(hadoopConfDir).toURI().toURL() }, getClass()
					.getClassLoader());
		} else if (hadoopHome != null) {
			logger.info("Using hadoop config found in " + hadoopHome);
			loader = new URLClassLoader(new URL[] { new File(hadoopHome, "conf").toURI().toURL() }, getClass().getClassLoader());
		} else {
			logger.info("HADOOP_HOME not set, using default hadoop config.");
			loader = getClass().getClassLoader();
		}
		
		return loader;
	}
	
	private FileSystem getFileSystem(String username) throws IOException {
		UserGroupInformation ugi = getProxiedUser(username, new Configuration(), shouldProxy);
		FileSystem fs = ugi.doAs(new PrivilegedAction<FileSystem>(){
			@Override
			public FileSystem run() {
				try {
					return FileSystem.get(conf);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		});
		
		return fs;
	}
	
	@Override
	protected void handleGet(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		User user = session.getUser();
		String username = user.getUserId();
		
		if (allowGroupProxy) {
			String proxyName = (String)session.getSessionData(PROXY_USER_SESSION_KEY);
			if (proxyName != null) {
				username = proxyName;
			}
		}

		try {
			FileSystem fs = getFileSystem(username);
			try {
				handleFSDisplay(fs, username, req, resp, session);
			} catch (IOException e) {
				throw e;
			} finally {
				fs.close();
			}
		}
		catch (Exception e) {
			Page page = newPage(req, resp, session, "azkaban/viewer/hdfs/hdfsbrowserpage.vm");
			page.add("error_message", "Error: " + e.getMessage());
			page.add("user", username);
			page.add("allowproxy", allowGroupProxy);
			page.add("no_fs", "true");
			page.add("viewerName", viewerName);
			page.render();
		}
	}

	@Override
	protected void handlePost(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		User user = session.getUser();
		if (hasParam(req, "action")) {
			HashMap<String,String> results = new HashMap<String,String>();			
			String action = getParam(req, "action");
			
			if (action.equals("changeProxyUser")) {
				if (hasParam(req, "proxyname")) {
					String newProxyname = getParam(req, "proxyname");
								
					if (user.getUserId().equals(newProxyname) || user.isInGroup(newProxyname)) {
						session.setSessionData(PROXY_USER_SESSION_KEY, newProxyname);
					}
					else {
						results.put("error", "User '" + user.getUserId() + "' cannot proxy as '" + newProxyname + "'");
					}
				}
			}
			else {
				results.put("error", "changeProxyUser param is not set");
			}
			
			this.writeJSON(resp, results);
			return;
		}

	}

	private void handleFSDisplay(FileSystem fs, String user, HttpServletRequest req, HttpServletResponse resp,
			Session session) throws IOException {
		String prefix = req.getContextPath() + req.getServletPath();
		String fsPath = req.getRequestURI().substring(prefix.length());
		if (fsPath.length() == 0)
			fsPath = "/";

		if (logger.isDebugEnabled())
			logger.debug("path=" + fsPath);

		Path path = new Path(fsPath);
		if (!fs.exists(path)) {
			throw new IllegalArgumentException(path.toUri().getPath() + " does not exist.");
		} else if (fs.isFile(path)) {
			displayFile(fs, req, resp, session, path);
		} else if (fs.getFileStatus(path).isDir()) {
			displayDir(fs, user, req, resp, session, path);
		} else {
			throw new IllegalStateException(
					"It exists, it is not a file, and it is not a directory, what is it precious?");
		}
	}

	private void displayDir(FileSystem fs, String user, HttpServletRequest req, HttpServletResponse resp,
			Session session, Path path) throws IOException {

		Page page = newPage(req, resp, session, "azkaban/viewer/hdfs/hdfsbrowserpage.vm");
		page.add("allowproxy", allowGroupProxy);
		page.add("viewerPath", viewerPath);
		page.add("viewerName", viewerName);
		
		List<Path> paths = new ArrayList<Path>();
		List<String> segments = new ArrayList<String>();
		Path curr = path;
		while (curr.getParent() != null) {
			paths.add(curr);
			segments.add(curr.getName());
			curr = curr.getParent();
		}

		Collections.reverse(paths);
		Collections.reverse(segments);

		page.add("paths", paths);
		page.add("segments", segments);
		page.add("user", user);

		try {
			page.add("subdirs", fs.listStatus(path)); // ??? line
		} catch (AccessControlException e) {
			page.add("error_message", "Permission denied. User cannot read file or directory");
		} catch (IOException e) {
			page.add("error_message", "Error: " + e.getMessage());
		}
		page.render();
	}

	private void displayFile(FileSystem fs, HttpServletRequest req, HttpServletResponse resp, Session session, Path path)
			throws IOException {
		int startLine = getIntParam(req, "start_line", 1);
		int endLine = getIntParam(req, "end_line", 1000);

		// use registered viewers to show the file content
		boolean outputed = false;
		OutputStream output = resp.getOutputStream();
		for (HdfsFileViewer viewer : viewers) {
			if (viewer.canReadFile(fs, path)) {
				viewer.displayFile(fs, path, output, startLine, endLine);
				outputed = true;
				break; // don't need to try other viewers
			}
		}

		// use default text viewer
		if (!outputed) {
			if (defaultViewer.canReadFile(fs, path)) {
				defaultViewer.displayFile(fs, path, output, startLine, endLine);
			} else {
				output.write(("Sorry, no viewer available for this file. ").getBytes("UTF-8"));
			}
		}
	}

	/**
	 * Create a proxied user based on the explicit user name, taking other
	 * parameters necessary from properties file.
	 */
	public static synchronized UserGroupInformation getProxiedUser(String toProxy, Configuration conf, boolean shouldProxy) throws IOException {
		if (toProxy == null) {
			throw new IllegalArgumentException("toProxy can't be null");
		}
		if (conf == null) {
			throw new IllegalArgumentException("conf can't be null");
		}

		if (shouldProxy) {
			logger.info("loginUser (" + loginUser + ") already created, refreshing tgt.");
			loginUser.checkTGTAndReloginFromKeytab();
			return UserGroupInformation.createProxyUser(toProxy, loginUser);
		}
		
		return UserGroupInformation.createRemoteUser(toProxy);
	}

}