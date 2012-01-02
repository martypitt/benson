package org.springframework.roo.shell.converters;

import java.io.File;
import java.util.List;

import org.springframework.roo.shell.Completion;
import org.springframework.roo.shell.Converter;
import org.springframework.roo.shell.MethodTarget;
import org.springframework.roo.support.util.Assert;
import org.springframework.roo.support.util.FileUtils;



/**
 * {@link Converter} for {@link File}.
 *
 * @author Stefan Schmidt
 * @author Roman Kuzmik
 * @author Ben Alex
 * @since 1.0
 */
public abstract class FileConverter implements Converter<File> {

	private static final String HOME_DIRECTORY_SYMBOL = "~";
	// Constants
	private static final String home = System.getProperty("user.home");

	// Fields

	/**
	 * @return the "current working directory" this {@link FileConverter} should use if the user fails to provide
	 * an explicit directory in their input (required)
	 */
	protected abstract File getWorkingDirectory();

	public File convertFromText(final String value, final Class<?> requiredType, final String optionContext) {
		return new File(convertUserInputIntoAFullyQualifiedPath(value));
	}

	public boolean getAllPossibleValues(final List<Completion> completions, final Class<?> requiredType, final String originalUserInput, final String optionContext, final MethodTarget target) {
		String adjustedUserInput = convertUserInputIntoAFullyQualifiedPath(originalUserInput);

		String directoryData = adjustedUserInput.substring(0, adjustedUserInput.lastIndexOf(File.separator) + 1);
		adjustedUserInput = adjustedUserInput.substring(adjustedUserInput.lastIndexOf(File.separator) + 1);

		populate(completions, adjustedUserInput, originalUserInput, directoryData);

		return false;
	}

	protected void populate(final List<Completion> completions, final String adjustedUserInput, final String originalUserInput, final String directoryData) {
		File directory = new File(directoryData);

		if (!directory.isDirectory()) {
			return;
		}

		for (File file : directory.listFiles()) {
			if (adjustedUserInput == null || adjustedUserInput.length() == 0 ||
				file.getName().toLowerCase().startsWith(adjustedUserInput.toLowerCase())) {

				String completion = "";
				if (directoryData.length() > 0)
					completion += directoryData;
				completion += file.getName();

				completion = convertCompletionBackIntoUserInputStyle(originalUserInput, completion);

				if (file.isDirectory()) {
					completions.add(new Completion(completion + File.separator));
				} else {
					completions.add(new Completion(completion));
				}
			}
		}
	}

	public boolean supports(final Class<?> requiredType, final String optionContext) {
		return File.class.isAssignableFrom(requiredType);
	}

	private String convertCompletionBackIntoUserInputStyle(final String originalUserInput, final String completion) {
		if (FileUtils.denotesAbsolutePath(originalUserInput)) {
			// Input was originally as a fully-qualified path, so we just keep the completion in that form
			return completion;
		}
		if (originalUserInput.startsWith(HOME_DIRECTORY_SYMBOL)) {
			// Input originally started with this symbol, so replace the user's home directory with it again
			Assert.notNull(home, "Home directory could not be determined from system properties");
			return HOME_DIRECTORY_SYMBOL + completion.substring(home.length());
		}
		// The path was working directory specific, so strip the working directory given the user never typed it
		return completion.substring(getWorkingDirectoryAsString().length());
	}

	/**
	 * If the user input starts with a tilde character (~), replace the tilde character with the
	 * user's home directory. If the user input does not start with a tilde, simply return the original
	 * user input without any changes if the input specifies an absolute path, or return an absolute path
	 * based on the working directory if the input specifies a relative path.
	 *
	 * @param userInput the user input, which may commence with a tilde (required)
	 * @return a string that is guaranteed to no longer contain a tilde as the first character (never null)
	 */
	private String convertUserInputIntoAFullyQualifiedPath(final String userInput) {
		if (FileUtils.denotesAbsolutePath(userInput)) {
			// Input is already in a fully-qualified path form
			return userInput;
		}
		if (userInput.startsWith(HOME_DIRECTORY_SYMBOL)) {
			// Replace this symbol with the user's actual home directory
			Assert.notNull(home, "Home directory could not be determined from system properties");
			if (userInput.length() > 1) {
				return home + userInput.substring(1);
			}
		}
		// The path is working directory specific, so prepend the working directory
		String fullPath = getWorkingDirectoryAsString() + userInput;
		return fullPath;
	}

	private String getWorkingDirectoryAsString() {
		try {
			return getWorkingDirectory().getCanonicalPath() + File.separator;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}