package org.jarego.junit.maven;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.ui.IStartup;

public class JUnitMavenInstaller implements IStartup {
	private static JUnitMavenLauncherListener junitLauncherListener = new JUnitMavenLauncherListener();
	@Override
	public void earlyStartup() {
		ILaunchManager launchMan = DebugPlugin.getDefault().getLaunchManager();
		launchMan.addLaunchListener(junitLauncherListener);
	}
}
