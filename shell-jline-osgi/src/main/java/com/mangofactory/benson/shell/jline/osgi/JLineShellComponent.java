package com.mangofactory.benson.shell.jline.osgi;

import java.net.URL;
import java.util.Collection;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.osgi.service.component.ComponentContext;
import org.springframework.roo.shell.ExecutionStrategy;
import org.springframework.roo.shell.Parser;
import org.springframework.roo.shell.jline.JLineShell;
import org.springframework.roo.support.osgi.OSGiUtils;



/**
 * OSGi component launcher for {@link JLineShell}.
 *
 * @author Ben Alex
 * @since 1.1
 */
@Component(immediate = true)
@Service
public class JLineShellComponent extends JLineShell {

	// Fields
	@Reference private ExecutionStrategy executionStrategy;
	@Reference private Parser parser;
	private ComponentContext context;

	protected void activate(final ComponentContext context) {
		this.context = context;
		Thread thread = new Thread(this, "Benson JLine Shell");
		thread.start();
	}

	protected void deactivate(final ComponentContext context) {
		this.context = null;
		closeShell();
	}

	@Override
	protected Collection<URL> findResources(final String path) {
		// For an OSGi bundle search, we add the root prefix to the given path
		return OSGiUtils.findEntriesByPath(context.getBundleContext(), OSGiUtils.ROOT_PATH + path);
	}

	@Override
	protected ExecutionStrategy getExecutionStrategy() {
		return executionStrategy;
	}

	@Override
	protected Parser getParser() {
		return parser;
	}

	@Override
	public String getStartupNotifications() {
		return "";
	}
}