package org.springframework.roo.shell;

import static org.springframework.roo.support.util.StringUtils.LINE_SEPARATOR;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

import org.springframework.roo.shell.AbstractShell;
import org.springframework.roo.shell.CliCommand;
import org.springframework.roo.shell.CliOption;
import org.springframework.roo.shell.ExecutionStrategy;
import org.springframework.roo.shell.ExitShellRequest;
import org.springframework.roo.shell.ParseResult;
import org.springframework.roo.shell.Parser;
import org.springframework.roo.shell.Shell;
import org.springframework.roo.shell.event.AbstractShellStatusPublisher;
import org.springframework.roo.shell.event.ShellStatus;
import org.springframework.roo.shell.event.ShellStatus.Status;
import org.springframework.roo.support.logging.HandlerUtils;
import org.springframework.roo.support.util.Assert;
import org.springframework.roo.support.util.IOUtils;
import org.springframework.roo.support.util.MathUtils;
import org.springframework.roo.support.util.StringUtils;


/**
 * Provides a base {@link Shell} implementation.
 *
 * @author Ben Alex
 */
public abstract class AbstractShell extends AbstractShellStatusPublisher implements Shell {

	// Constants
	private static final String MY_SLOT = AbstractShell.class.getName();
	protected static final String BENSON_PROMPT = "benson> ";

	// Public static fields; don't rename, make final, or make non-public, as
	// they are part of the public API, e.g. are changed by STS.
	public static String completionKeys = "TAB";
	public static String shellPrompt = BENSON_PROMPT;

	// Instance fields
	protected final Logger logger = HandlerUtils.getLogger(getClass());
	protected boolean inBlockComment;
	protected ExitShellRequest exitShellRequest;

	/**
	 * Returns any classpath resources with the given path
	 * 
	 * @param path the path for which to search (never null)
	 * @return <code>null</code> if the search can't be performed
	 * @since 1.2.0
	 */
	protected abstract Collection<URL> findResources(String path);

	protected abstract String getHomeAsString();

	protected abstract ExecutionStrategy getExecutionStrategy();

	protected abstract Parser getParser();

	@CliCommand(value = { "script" }, help = "Parses the specified resource file and executes its commands")
	public void script(
			@CliOption(key = { "", "file" }, help = "The file to locate and execute", mandatory = true) final File script, 
			@CliOption(key = "lineNumbers", mandatory = false, specifiedDefaultValue = "true", unspecifiedDefaultValue = "false", help = "Display line numbers when executing the script") final boolean lineNumbers) {

		Assert.notNull(script, "Script file to parse is required");
		double startedNanoseconds = System.nanoTime();
		final InputStream inputStream = openScript(script);

		BufferedReader in = null;
		try {
			in = new BufferedReader(new InputStreamReader(inputStream));
			String line;
			int i = 0;
			while ((line = in.readLine()) != null) {
				i++;
				if (lineNumbers) {
					logger.fine("Line " + i + ": " + line);
				} else {
					logger.fine(line);
				}
				if (!"".equals(line.trim())) {
					boolean success = executeScriptLine(line);
					if (success && ((line.trim().startsWith("q") || line.trim().startsWith("ex")))) {
						break;
					} else if (!success) {
						// Abort script processing, given something went wrong
						throw new IllegalStateException("Script execution aborted");
					}
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		} finally {
			IOUtils.closeQuietly(inputStream, in);
			double executionDurationInSeconds = (System.nanoTime() - startedNanoseconds) / 1000000000D;
			logger.fine("Script required " + MathUtils.round(executionDurationInSeconds, 3) + " seconds to execute");
		}
	}

	/**
	 * Opens the given script for reading
	 * 
	 * @param script the script to read (required)
	 * @return a non-<code>null</code> input stream
	 */
	private InputStream openScript(final File script) {
		try {
			return new BufferedInputStream(new FileInputStream(script));
		} catch (final FileNotFoundException fnfe) {
			// Try to find the script via the classloader
			final Collection<URL> urls = findResources(script.getName());

			// Handle search failure
			Assert.notNull(urls, "Unexpected error looking for '" + script.getName() + "'");

			// Handle the search being OK but the file simply not being present
			Assert.notEmpty(urls, "Script '" + script + "' not found on disk or in classpath");
			Assert.isTrue(urls.size() == 1, "More than one '" + script + "' was found in the classpath; unable to continue");
			try {
				return urls.iterator().next().openStream();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/**
	 * Execute the single line from a script.
	 * <p>
	 * This method can be overridden by sub-classes to pre-process script lines.
	 */
	protected boolean executeScriptLine(final String line) {
		return executeCommand(line);
	}

	public boolean executeCommand(String line) {
		// Another command was attempted
		setShellStatus(ShellStatus.Status.PARSING);

		final ExecutionStrategy executionStrategy = getExecutionStrategy();
		boolean flashedMessage = false;
		while (executionStrategy == null || !executionStrategy.isReadyForCommands()) {
			// Wait
			try {
				Thread.sleep(500);
			} catch (InterruptedException ignore) {}
			if (!flashedMessage) {
				flash(Level.INFO, "Please wait - still loading", MY_SLOT);
				flashedMessage = true;
			}
		}
		if (flashedMessage) {
			flash(Level.INFO, "", MY_SLOT);
		}

		ParseResult parseResult = null;
		try {
			// We support simple block comments; ie a single pair per line
			if (!inBlockComment && line.contains("/*") && line.contains("*/")) {
				blockCommentBegin();
				String lhs = line.substring(0, line.lastIndexOf("/*"));
				if (line.contains("*/")) {
					line = lhs + line.substring(line.lastIndexOf("*/") + 2);
					blockCommentFinish();
				} else {
					line = lhs;
				}
			}
			if (inBlockComment) {
				if (!line.contains("*/")) {
					return true;
				}
				blockCommentFinish();
				line = line.substring(line.lastIndexOf("*/") + 2);
			}
			// We also support inline comments (but only at start of line, otherwise valid
			// command options like http://www.helloworld.com will fail as per ROO-517)
			if (!inBlockComment && (line.trim().startsWith("//") || line.trim().startsWith("#"))) { // # support in ROO-1116
				line = "";
			}
			// Convert any TAB characters to whitespace (ROO-527)
			line = line.replace('\t', ' ');
			if ("".equals(line.trim())) {
				setShellStatus(Status.EXECUTION_SUCCESS);
				return true;
			}
			parseResult = getParser().parse(line);
			if (parseResult == null) {
				return false;
			}

			setShellStatus(Status.EXECUTING);
			Object result = executionStrategy.execute(parseResult);
			setShellStatus(Status.EXECUTION_RESULT_PROCESSING);
			if (result != null) {
				if (result instanceof ExitShellRequest) {
					exitShellRequest = (ExitShellRequest) result;
					// Give ProcessManager a chance to close down its threads before the overall OSGi framework is terminated (ROO-1938)
					executionStrategy.terminate();
				} else if (result instanceof Iterable<?>) {
					for (Object o : (Iterable<?>) result) {
						logger.info(o.toString());
					}
				} else {
					logger.info(result.toString());
				}
			}

			logCommandIfRequired(line, true);
			setShellStatus(Status.EXECUTION_SUCCESS, line, parseResult);
			return true;
		} catch (RuntimeException e) {
			setShellStatus(Status.EXECUTION_FAILED, line, parseResult);
			// We rely on execution strategy to log it
			try {
				logCommandIfRequired(line, false);
			} catch (Exception ignored) {}
			return false;
		} finally {
			setShellStatus(Status.USER_INPUT);
		}
	}

	/**
	 * Allows a subclass to log the execution of a well-formed command. This is invoked after a command
	 * has completed, and indicates whether the command returned normally or returned an exception. Note
	 * that attempted commands that are not well-formed (eg they are missing a mandatory argument) will
	 * never be presented to this method, as the command execution is never actually attempted in those
	 * cases. This method is only invoked if an attempt is made to execute a particular command.
	 *
	 * <p>
	 * Implementations should consider specially handling the "script" commands, and also
	 * indicating whether a command was successful or not. Implementations that wish to behave
	 * consistently with other {@link AbstractShell} subclasses are encouraged to simply override
	 * {@link #logCommandToOutput(String)} instead, and only override this method if you actually
	 * need to fine-tune the output logic.
	 *
	 * @param line the parsed line (any comments have been removed; never null)
	 * @param successful if the command was successful or not
	 */
	protected void logCommandIfRequired(final String line, final boolean successful) {
		if (line.startsWith("script")) {
			logCommandToOutput((successful ? "// " : "// [failed] ") + line);
		} else {
			logCommandToOutput((successful ? "" : "// [failed] ") + line);
		}
	}

	/**
	 * Allows a subclass to actually write the resulting logged command to some form of output. This
	 * frees subclasses from needing to implement the logic within {@link #logCommandIfRequired(String, boolean)}.
	 *
	 * <p>
	 * Implementations should invoke {@link #getExitShellRequest()} to monitor any attempts to exit the shell and
	 * release resources such as output log files.
	 *
	 * @param processedLine the line that should be appended to some type of output (excluding the \n character)
	 */
	protected void logCommandToOutput(final String processedLine) {}

	/**
	 * Base implementation of the {@link Shell#setPromptPath(String)} method, designed for simple shell
	 * implementations. Advanced implementations (eg those that support ANSI codes etc) will likely want
	 * to override this method and set the {@link #shellPrompt} variable directly.
	 *
	 * @param path to set (can be null or empty; must NOT be formatted in any special way eg ANSI codes)
	 */
	public void setPromptPath(final String path) {
		if (path == null || "".equals(path)) {
			shellPrompt = BENSON_PROMPT;
		} else {
			shellPrompt = path + " " + BENSON_PROMPT;
		}
	}

	/**
	 * Default implementation of {@link Shell#setPromptPath(String, boolean))} method to satisfy STS compatibility.
	 * 
	 * @param path to set (can be null or empty)
	 * @param overrideStyle
	 */
	public void setPromptPath(String path, boolean overrideStyle) {
		setPromptPath(path);
	}

	public ExitShellRequest getExitShellRequest() {
		return exitShellRequest;
	}

	@CliCommand(value = { "//", ";" }, help = "Inline comment markers (start of line only)")
	public void inlineComment() {}

	@CliCommand(value = { "/*" }, help = "Start of block comment")
	public void blockCommentBegin() {
		Assert.isTrue(!inBlockComment, "Cannot open a new block comment when one already active");
		inBlockComment = true;
	}

	@CliCommand(value = { "*/" }, help = "End of block comment")
	public void blockCommentFinish() {
		Assert.isTrue(inBlockComment, "Cannot close a block comment when it has not been opened");
		inBlockComment = false;
	}

	@CliCommand(value = { "system properties" }, help = "Shows the shell's properties")
	public String props() {
		final Set<String> data = new TreeSet<String>(); // For repeatability
		for (final Entry<Object, Object> entry : System.getProperties().entrySet()) {
			data.add(entry.getKey() + " = " + entry.getValue());
		}

		return StringUtils.collectionToDelimitedString(data, LINE_SEPARATOR) + LINE_SEPARATOR;
	}

	@CliCommand(value = { "date" }, help = "Displays the local date and time")
	public String date() {
		return DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL).format(new Date());
	}

	@CliCommand(value = { "flash test" }, help = "Tests message flashing")
	public void flashCustom() throws Exception {
		flash(Level.FINE, "Hello world", "a");
		Thread.sleep(150);
		flash(Level.FINE, "Short world", "a");
		Thread.sleep(150);
		flash(Level.FINE, "Small", "a");
		Thread.sleep(150);
		flash(Level.FINE, "Downloading xyz", "b");
		Thread.sleep(150);
		flash(Level.FINE, "", "a");
		Thread.sleep(150);
		flash(Level.FINE, "Downloaded xyz", "b");
		Thread.sleep(150);
		flash(Level.FINE, "System online", "c");
		Thread.sleep(150);
		flash(Level.FINE, "System ready", "c");
		Thread.sleep(150);
		flash(Level.FINE, "System farewell", "c");
		Thread.sleep(150);
		flash(Level.FINE, "", "c");
		Thread.sleep(150);
		flash(Level.FINE, "", "b");
	}

	@CliCommand(value = { "version" }, help = "Displays shell version")
	public String version(@CliOption(key = "", help = "Special version flags") final String extra) {
		StringBuilder sb = new StringBuilder();
//
//		if ("jaime".equals(extra)) {
//			sb.append("               /\\ /l").append(LINE_SEPARATOR);
//			sb.append("               ((.Y(!").append(LINE_SEPARATOR);
//			sb.append("                \\ |/").append(LINE_SEPARATOR);
//			sb.append("                /  6~6,").append(LINE_SEPARATOR);
//			sb.append("                \\ _    +-.").append(LINE_SEPARATOR);
//			sb.append("                 \\`-=--^-' \\").append(LINE_SEPARATOR);
//			sb.append("                  \\   \\     |\\--------------------------+").append(LINE_SEPARATOR);
//			sb.append("                 _/    \\    |  Thanks for loading Roo!  |").append(LINE_SEPARATOR);
//			sb.append("                (  .    Y   +---------------------------+").append(LINE_SEPARATOR);
//			sb.append("               /\"\\ `---^--v---.").append(LINE_SEPARATOR);
//			sb.append("              / _ `---\"T~~\\/~\\/").append(LINE_SEPARATOR);
//			sb.append("             / \" ~\\.      !").append(LINE_SEPARATOR);
//			sb.append("       _    Y      Y.~~~ /'").append(LINE_SEPARATOR);
//			sb.append("      Y^|   |      | Roo 7").append(LINE_SEPARATOR);
//			sb.append("      | l   |     / .   /'").append(LINE_SEPARATOR);
//			sb.append("      | `L  | Y .^/   ~T").append(LINE_SEPARATOR);
//			sb.append("      |  l  ! | |/  | |               ____  ____  ____").append(LINE_SEPARATOR);
//			sb.append("      | .`\\/' | Y   | !              / __ \\/ __ \\/ __ \\").append(LINE_SEPARATOR);
//			sb.append("      l  \"~   j l   j L______       / /_/ / / / / / / /").append(LINE_SEPARATOR);
//			sb.append("       \\,____{ __\"\" ~ __ ,\\_,\\_    / _, _/ /_/ / /_/ /").append(LINE_SEPARATOR);
//			sb.append("    ~~~~~~~~~~~~~~~~~~~~~~~~~~~   /_/ |_|\\____/\\____/").append(" ").append(versionInfo()).append(LINE_SEPARATOR);
//			return sb.toString();
//		}

		sb.append("    ____                            ").append(LINE_SEPARATOR);
		sb.append("   / __ )___  ____  _________  ____ ").append(LINE_SEPARATOR);
		sb.append("  / __  / _ \\/ __ \\/ ___/ __ \\/ __ \\").append(LINE_SEPARATOR);
		sb.append(" / /_/ /  __/ / / (__  ) /_/ / / / /").append(LINE_SEPARATOR);
		sb.append("/_____/\\___/_/ /_/____/\\____/_/ /_/ ").append(LINE_SEPARATOR);
		sb.append(versionInfo()).append(LINE_SEPARATOR);
		sb.append(LINE_SEPARATOR);

		return sb.toString();
	}

	public static String versionInfo() {
		// Try to determine the bundle version
		String bensonVersion = null;
		String gitCommitHash = null;
		String springRooVersion = null;
		JarFile jarFile = null;
		try {
			URL classContainer = AbstractShell.class.getProtectionDomain().getCodeSource().getLocation();
			if (classContainer.toString().endsWith(".jar")) {
				// Attempt to obtain the "Bundle-Version" version from the manifest
				jarFile = new JarFile(new File(classContainer.toURI()), false);
				ZipEntry manifestEntry = jarFile.getEntry("META-INF/MANIFEST.MF");
				Manifest manifest = new Manifest(jarFile.getInputStream(manifestEntry));
				bensonVersion = manifest.getMainAttributes().getValue("Benson-Version");
				gitCommitHash = manifest.getMainAttributes().getValue("Git-Commit-Hash");
				springRooVersion = manifest.getMainAttributes().getValue("Spring-Roo-Version");
			}
		} catch (IOException ignoreAndMoveOn) {
		} catch (URISyntaxException ignoreAndMoveOn) {
		} finally {
			IOUtils.closeQuietly(jarFile);
		}

		StringBuilder sb = new StringBuilder();

		if (bensonVersion != null) {
			sb.append("Version ").append(bensonVersion);
		}

		if (gitCommitHash != null && gitCommitHash.length() > 7) {
			if (sb.length() > 0) {
				sb.append(" "); // to separate from version
			}
			sb.append("[rev ");
			sb.append(gitCommitHash.substring(0,7));
			sb.append("]");
		}
		if (springRooVersion != null)
		{
			sb.append(LINE_SEPARATOR);
			sb.append("Powered by Spring Roo version ").append(springRooVersion);
		}
		if (sb.length() == 0) {
			sb.append("UNKNOWN VERSION");
		}

		return sb.toString();
	}

	public String getShellPrompt() {
		return shellPrompt;
	}

	/**
	 * Obtains the home directory for the current shell instance.
	 *
	 * <p>
	 * Note: calls the {@link #getHomeAsString()} method to allow subclasses to provide the home directory location as
	 * string using different environment-specific strategies.
	 *
	 * <p>
	 * If the path indicated by {@link #getHomeAsString()} exists and refers to a directory, that directory
	 * is returned.
	 *
	 * <p>
	 * If the path indicated by {@link #getHomeAsString()} exists and refers to a file, an exception is thrown.
	 *
	 * <p>
	 * If the path indicated by {@link #getHomeAsString()} does not exist, it will be created as a directory.
	 * If this fails, an exception will be thrown.
	 *
	 * @return the home directory for the current shell instance (which is guaranteed to exist and be a directory)
	 */
	public File getHome() {
		String rooHome = getHomeAsString();
		File f = new File(rooHome);
		Assert.isTrue(!f.exists() || (f.exists() && f.isDirectory()), "Path '" + f.getAbsolutePath() + "' must be a directory, or it must not exist");
		if (!f.exists()) {
			f.mkdirs();
		}
		Assert.isTrue(f.exists() && f.isDirectory(), "Path '" + f.getAbsolutePath() + "' is not a directory; please specify roo.home system property correctly");
		return f;
	}

	/**
	 * Simple implementation of {@link #flash(Level, String, String)} that simply displays the message via the logger. It is
	 * strongly recommended shell implementations override this method with a more effective approach.
	 */
	public void flash(final Level level, final String message, final String slot) {
		Assert.notNull(level, "Level is required for a flash message");
		Assert.notNull(message, "Message is required for a flash message");
		Assert.hasText(slot, "Slot name must be specified for a flash message");
		if (!("".equals(message))) {
			logger.log(level, message);
		}
	}
}
