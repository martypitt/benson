package org.springframework.roo.shell;

import org.springframework.roo.shell.Shell;

/**
 * Obtains the prompt used by a {@link Shell}.
 *
 * @author Ben Alex
 * @since 1.0
 */
public interface ShellPromptAccessor {

	/**
	 * @return the shell prompt (never null; the result may include special characters such as ANSI
	 * escape codes if the implementation is using them)
	 */
	String getShellPrompt();
}
