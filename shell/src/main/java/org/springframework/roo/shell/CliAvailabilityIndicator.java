package org.springframework.roo.shell;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.roo.shell.CliAvailabilityIndicator;
import org.springframework.roo.shell.CliCommand;


/**
 * Annotates a method that can indicate whether a particular command is presently
 * available or not.
 *
 * <p>
 * This annotation must only be applied to a public no-argument method that returns primitive boolean.
 * The method should be inexpensive to evaluate, as this method can be called very
 * frequently. If expensive operations are necessary to compute command availability,
 * it is suggested the method return a boolean field that is maintained using the observer
 * pattern.
 *
 * <p>
 * It is possible that a particular availability method might be able to represent the
 * availability status of multiple commands. As such, an availability indicator annotation
 * will indicate the commands that it applies to. If a specific command has multiple
 * aliases (ie by using an array for {@link CliCommand#value()}), only one of the commands
 * need to be specified in the {@link CliAvailabilityIndicator} annotation.
 *
 * @author Ben Alex
 * @since 1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CliAvailabilityIndicator {

	/**
	 * @return the name of the command or commands that this availability indicator represents
	 */
	String[] value();
}
