package org.jarego.junit.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class JUnitMavenLauncherListener implements ILaunchesListener2 {
	private static final Logger LOG = Logger.getLogger(JUnitMavenLauncherListener.class.getName());
	
	private static final String VMARGS_ATTR = "org.eclipse.jdt.launching.VM_ARGUMENTS";
	private static final String ENV_ATTR = "org.eclipse.debug.core.environmentVariables";
	private static final String PROJ_ATTR = "org.eclipse.jdt.launching.PROJECT_ATTR";
	private static final String CLASSPATH_ATTR = "org.eclipse.jdt.launching.CLASSPATH";
	private static final String DEFAULT_CLASSPATH_ATTR = "org.eclipse.jdt.launching.DEFAULT_CLASSPATH";
	
	private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>";
	
	private final List<String> originalVmArgs = new ArrayList<>();
	private final List<List<String>> originalClasspaths = new ArrayList<>();
	private final List<Map<String, String>> originalEnvMap = new ArrayList<>();
	private final List<ILaunch> modifiedLaunches = new ArrayList<>();
	
	public void integrateMaven(IProject project, ILaunch launch, ILaunchConfiguration conf)
			throws CoreException, IOException, XmlPullParserException {
		boolean hasChanges = false;
		
		File projectPath = project.getLocation().toFile().getCanonicalFile();
		
		// leer archivos pom
		List<Model> models = new ArrayList<>();
		
		MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
		File pomFile = new File(projectPath, "pom.xml");
		Model model = xpp3Reader.read(new FileReader(pomFile));
		models.add(model);
		
		// recuperar el modelodel arbol del proyecto
		Parent parent = model.getParent();
		File parentProjectPath = projectPath;
		while (parent != null) {
			String parentPomFileName = parent.getRelativePath();
			if (parentPomFileName == null)
				parentPomFileName = "../pom.xml";
			File parentPomFile = new File(parentProjectPath, parentPomFileName);
			parentProjectPath = parentPomFile.getParentFile().getCanonicalFile();
			Model parentModel = xpp3Reader.read(new FileReader(parentPomFile));
			parentModel.setPomFile(parentPomFile.getCanonicalFile());
			models.add(0, parentModel);
			parent = parentModel.getParent();
		}
		
		// recuperar el groupId, version y nombre
		Model lastModel = null;
		for (Model m : models) {
			if (lastModel != null) {
				if (m.getGroupId() == null)
					m.setGroupId(lastModel.getGroupId());
				if (m.getVersion() == null)
					m.setVersion(lastModel.getVersion());
			}
			if (m.getName() == null)
				m.setName(m.getArtifactId());
			lastModel = m;
		}
		
		// resolver variables
		MavenProperties mavenProperties = new MavenProperties();
		mavenProperties.setProperty("basedir", projectPath.getAbsolutePath());
		mavenProperties.setProperty("project.basedir", projectPath.getAbsolutePath());
		mavenProperties.setProperty("project.groupId", model.getGroupId());
		mavenProperties.setProperty("project.artifactId", model.getArtifactId());
		mavenProperties.setProperty("project.version", model.getVersion());
		mavenProperties.setProperty("project.name", model.getName());
		
		StringBuilder sbParent = new StringBuilder();
		for (int i=models.size()-2;i>=0;i--) {
			Model m = models.get(i);
			String parentsKey = sbParent.append(".parent").toString();
			mavenProperties.setProperty("project"+parentsKey+".groupId", m.getGroupId());
			mavenProperties.setProperty("project"+parentsKey+".artifactId", m.getArtifactId());
			mavenProperties.setProperty("project"+parentsKey+".version", m.getVersion());
			mavenProperties.setProperty("project"+parentsKey+".name", m.getName());
			mavenProperties.setProperty("project"+parentsKey+".basedir", m.getProjectDirectory().getCanonicalPath());
		}
		
		// variables de repositorio
		File m2Home;
		if (System.getenv("M2_HOME") != null) {
			m2Home = new File(System.getenv("M2_HOME"));
		} else {
			m2Home = new File(System.getProperty("user.home"), ".m2");
		}
		File settingsFile = new File(m2Home, "settings.xml");
		File localRepo = null;
		if (settingsFile.exists()) {
			try {
				DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
				dbFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
				DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
				Document doc = dBuilder.parse(settingsFile);
				doc.getDocumentElement().normalize();
				NodeList localRepoNodes = doc.getDocumentElement().getElementsByTagName("localRepository");
				for (int i=0;i<localRepoNodes.getLength();i++) {
					Node localRepoNode = localRepoNodes.item(i);
					if (localRepoNode.getNodeType() == Node.ELEMENT_NODE) {
						localRepo = new File(mavenProperties.parse(localRepoNode.getNodeValue()));
					}
				}
			} catch (Exception e) {
				LOG.log(Level.SEVERE, "unable to read setting.xml", e);
			}
		}
		if (localRepo == null)
			localRepo = new File(m2Home, "repository");
		mavenProperties.setProperty("settings.localRepository", localRepo.getAbsolutePath());
		
		// variables de proyectos
		for (Model m : models) {
			if (m.getProperties() != null)
				mavenProperties.putAllProperties(m.getProperties());
		}
		for (Model m : models) {
			if (m.getBuild() != null) {
				Plugin plugin;
				if ("pom".equals(m.getPackaging()) && m.getBuild().getPluginManagement() != null)
					plugin = m.getBuild().getPluginManagement().getPluginsAsMap().get(
							"org.codehaus.mojo:properties-maven-plugin");
				else
					plugin = m.getBuild().getPluginsAsMap().get(
							"org.codehaus.mojo:properties-maven-plugin");
				if (plugin != null) {
					List<PluginExecution> executions = plugin.getExecutions();
					if (executions != null) {
						for (PluginExecution execution : executions) {
							if ("initialize".equals(execution.getPhase()) &&
									execution.getGoals() != null && 
									execution.getGoals().contains("read-project-properties")) {
								Xpp3Dom configuration = (Xpp3Dom)execution.getConfiguration();
								if (configuration != null) {
									Xpp3Dom files = configuration.getChild("files");
									if (files != null && files.getChildren() != null) {
										for (Xpp3Dom file : files.getChildren()) {
											String filePath = mavenProperties.parse(file.getValue());
											mavenProperties.putAllProperties(filePath);
										}
									}
								}
							}
						}
					}
				}
			}
		}
		
		// encontrar plugin de pruebas
		List<Xpp3Dom> surefireConfigurations = new ArrayList<>();
		for (Model m : models) {
			if (m.getBuild() != null) {
				Plugin plugin;
				if ("pom".equals(m.getPackaging()) && m.getBuild().getPluginManagement() != null)
					plugin = m.getBuild().getPluginManagement().getPluginsAsMap().get(
							"org.apache.maven.plugins:maven-surefire-plugin");
				else
					plugin = m.getBuild().getPluginsAsMap().get(
							"org.apache.maven.plugins:maven-surefire-plugin");
				if (plugin != null && plugin.getConfiguration() != null)
					surefireConfigurations.add((Xpp3Dom)plugin.getConfiguration());
			}
		}
		
		// leer variables
		List<String> mavenVmArgs = new ArrayList<>();
		List<String> mavenClasspath = new ArrayList<>();
		Map<String, String> mavenEnvMap = new HashMap<>(); 
		for (Xpp3Dom configuration : surefireConfigurations) {
			// cargar lina de argumentos de JVM
			if (configuration.getChild("argLine") != null) {
				mavenVmArgs.add(mavenProperties.parse(
						configuration.getChild("argLine").getValue()));
				hasChanges = true;
			}
			
			// cargar archivo de propiedades de JVM
			if (configuration.getChild("systemPropertiesFile") != null) {
				String sysPropFileName = mavenProperties.parse(
						configuration.getChild("systemPropertiesFile").getValue());
				File sysPropFile;
				if (sysPropFileName.startsWith("/"))
					sysPropFile = new File(sysPropFileName);
				else
					sysPropFile = new File(projectPath, sysPropFileName);
				if (sysPropFile.exists()) {
					Properties prop = new Properties();
					try (InputStream input = new FileInputStream(sysPropFile)) {
						prop.load(input);
					}
					for (String key : prop.stringPropertyNames()) {
						String value = mavenProperties.parse(prop.getProperty(key));
						if (value != null && (value.contains(" ") || value.contains("\t")))
							value = "\""+value+"\"";
						mavenVmArgs.add("-D"+key+"="+value);
						hasChanges = true;
					}
				}
			}
			// cargar propiedades de JVM
			if (configuration.getChild("systemPropertyVariables") != null) {
				for (Xpp3Dom child : configuration.getChild("systemPropertyVariables").getChildren()) {
					String value = mavenProperties.parse(child.getValue());
					if (value != null && (value.contains(" ") || value.contains("\t")))
						value = "\""+value+"\"";
					mavenVmArgs.add("-D"+child.getName()+"="+value);
					hasChanges = true;
				}
			}
			
			// cargar variables de ambiente
			if (configuration.getChild("environmentVariables") != null) {
				for (Xpp3Dom child : configuration.getChild("environmentVariables").getChildren()) {
					String value = mavenProperties.parse(child.getValue());
					if (value != null && (value.contains(" ") || value.contains("\t")))
						value = "\""+value+"\"";
					mavenEnvMap.put(child.getName(), value);
					hasChanges = true;
				}
			}
			
			// cargar classpath adicional
			if (configuration.getChild("additionalClasspathElements") != null) {
				for (Xpp3Dom child : configuration.getChild("additionalClasspathElements").getChildren()) {
					String additionalClasspath = mavenProperties.parse(child.getValue());
					mavenClasspath.add(XML_HEADER
							+ "<runtimeClasspathEntry externalArchive=\""+additionalClasspath+"\" "
							+ "path=\"3\" type=\"2\"/>");
					hasChanges = true;
				}
			}
		}
		
		if (!mavenClasspath.isEmpty() && project.hasNature(JavaCore.NATURE_ID)) {
			mavenClasspath.add(0, XML_HEADER
					+ "<runtimeClasspathEntry projectName=\""+project.getName()+"\" "
					+ "path=\"3\" type=\"1\"/>");
			IJavaProject jproject = JavaCore.create(project);
			for (IClasspathEntry cp : jproject.readRawClasspath()) {
				if (cp.getEntryKind() == 5) {
					if (cp.getPath().toString().contains("JRE_CONTAINER"))
						mavenClasspath.add(0, XML_HEADER
								+ "<runtimeClasspathEntry containerPath=\""+cp.getPath().toString()+"\" "
								+ "path=\"1\" type=\"4\"/>");
					else
						mavenClasspath.add(0, XML_HEADER
								+ "<runtimeClasspathEntry containerPath=\""+cp.getPath().toString()+"\" "
								+ "path=\"3\" type=\"4\"/>");
				}
			}
		}
		
		String origVmArgs = conf.getAttribute(VMARGS_ATTR, "");
		List<String> origClasspath = conf.getAttribute(CLASSPATH_ATTR, Collections.emptyList());
		Map<String, String> origEnvMap = conf.getAttribute(ENV_ATTR, Collections.emptyMap());
		if (hasChanges) {
			originalVmArgs.add(origVmArgs);
			originalClasspaths.add(origClasspath);
			originalEnvMap.add(origEnvMap);
			
			ILaunchConfigurationWorkingCopy confwc = conf.getWorkingCopy();
			if (!mavenVmArgs.isEmpty()) {
				if (!"".equals(origVmArgs)) mavenVmArgs.add(0, origVmArgs);
				confwc.setAttribute(VMARGS_ATTR, mavenVmArgs.stream().collect(Collectors.joining(" ")));
			}
			if (!mavenClasspath.isEmpty()) {
				if (!origClasspath.isEmpty()) mavenClasspath.addAll(0, origClasspath);
				confwc.setAttribute(CLASSPATH_ATTR, mavenClasspath);
				confwc.setAttribute(DEFAULT_CLASSPATH_ATTR, false);
			}
			if (!mavenEnvMap.isEmpty()) {
				if (origEnvMap.isEmpty()) mavenEnvMap.putAll(origEnvMap);
				confwc.setAttribute(ENV_ATTR, mavenEnvMap);
			}
			confwc.doSave();
		}
		
		
		
		if (hasChanges)
			modifiedLaunches.add(launch);
	}
	
	@Override
	public void launchesAdded(ILaunch[] launches) {
		for (ILaunch launch : launches) {
			ILaunchConfiguration conf = launch.getLaunchConfiguration();
			try {
				String pluginId = conf.getType().getPluginIdentifier();
				if ("org.eclipse.jdt.junit.core".equals(pluginId)) {
					String projectName = conf.getAttribute(PROJ_ATTR, "");
					IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
					IProject project = workspaceRoot.getProject(projectName);
					if (project.hasNature("org.eclipse.m2e.core.maven2Nature"))
						integrateMaven(project, launch, conf);
				}
			} catch (CoreException | IOException | XmlPullParserException e) {
				LOG.log(Level.SEVERE, "unable to read maven-surefire-plugin configuration", e);
			}
		}
	}
	
	@Override
	public void launchesChanged(ILaunch[] launches) {
		// no se requiere hacer ninguna accion aqui
	}
	@Override
	public void launchesRemoved(ILaunch[] launches) {
		// no se requiere hacer ninguna accion aqui
	}
	
	@Override
	public void launchesTerminated(ILaunch[] launches) {
		for (int i=0;i<modifiedLaunches.size();i++) {
			ILaunch launch = modifiedLaunches.get(i);
			ILaunchConfiguration conf = launch.getLaunchConfiguration();
			try {
				ILaunchConfigurationWorkingCopy confwc = conf.getWorkingCopy();
				confwc.setAttribute(VMARGS_ATTR, originalVmArgs.get(i));
				if (originalClasspaths.get(i).isEmpty()) {
					confwc.removeAttribute(CLASSPATH_ATTR);
					confwc.removeAttribute(DEFAULT_CLASSPATH_ATTR);
				} else {
					confwc.setAttribute(CLASSPATH_ATTR, originalClasspaths.get(i));
					confwc.setAttribute(DEFAULT_CLASSPATH_ATTR, false);
				}
				if (originalEnvMap.get(i).isEmpty())
					confwc.removeAttribute(ENV_ATTR);
				else
					confwc.setAttribute(ENV_ATTR, originalEnvMap.get(i));
				confwc.doSave();
			} catch (CoreException e) {
				LOG.log(Level.SEVERE, "unable to read maven-surefire-plugin configuration", e);
			}
		}
		originalVmArgs.clear();
		originalEnvMap.clear();
		originalClasspaths.clear();
		modifiedLaunches.clear();
	}
}
