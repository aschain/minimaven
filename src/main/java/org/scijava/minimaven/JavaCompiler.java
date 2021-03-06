/*
 * #%L
 * MiniMaven build system for small Java projects.
 * %%
 * Copyright (C) 2011 - 2017 Board of Regents of the University of
 * Wisconsin-Madison.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.minimaven;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Method;

import javax.tools.ToolProvider;

import org.scijava.util.ClassUtils;
import org.scijava.util.FileUtils;
import org.scijava.util.ProcessUtils;

/**
 * Encapsulates the Java compiler, falling back to command-line {@code javac}.
 *
 * @author Johannes Schindelin
 * @author Mark Hiner
 */
public class JavaCompiler {

	protected PrintStream err, out;
	protected static Method javac;
	private final static String CLASS_NAME = "com.sun.tools.javac.Main";

	public JavaCompiler(final PrintStream err, final PrintStream out) {
		this.err = err;
		this.out = out;
	}

	// this function handles the javac singleton
	public void call(final String[] arguments, final boolean verbose)
		throws CompileError
	{
		call(arguments, verbose, false);
	}

	public void call(final String[] arguments, final boolean verbose,
		final boolean debug) throws CompileError
	{
		synchronized (this) {
			try {
				final javax.tools.JavaCompiler sysc = ToolProvider
					.getSystemJavaCompiler();
				if (sysc != null) {
					if (debug) {
						err.print("Found tools compiler: " + sysc.getClass());
						err.print(ClassUtils.getLocation(sysc.getClass()));
					}
					sysc.run(null, out, err, arguments);
					return;
				}

				if (verbose) {
					err.println(
						"No javax.tools.JavaCompiler available. Checking for explicit javac.");
				}

				if (javac == null) {
					final JarClassLoader loader = discoverJavac();
					final Class<?> main = loader == null ? Thread.currentThread()
						.getContextClassLoader().loadClass(CLASS_NAME) : loader
							.forceLoadClass(CLASS_NAME);
					final Class<?>[] argsType = new Class[] { arguments.getClass(),
						PrintWriter.class };
					javac = main.getMethod("compile", argsType);
				}

				final Writer writer = new PrintWriter(err);
				final Object result = javac.invoke(null, new Object[] { arguments,
					writer });
				writer.flush();
				if (!result.equals(new Integer(0))) throw new CompileError(result);
				return;
			}
			catch (final CompileError e) {
				/* re-throw */
				throw e;
			}
			catch (final Exception e) {
				if (verbose) {
					e.printStackTrace(err);
					err.println("Could not find javac " + e +
						", falling back to system javac");
				}
			}
		}

		// fall back to calling javac
		final String[] newArguments = new String[arguments.length + 1];
		newArguments[0] = "javac";
		System.arraycopy(arguments, 0, newArguments, 1, arguments.length);
		try {
			execute(newArguments, new File("."), verbose);
		}
		catch (final Exception e) {
			throw new RuntimeException("Could not even fall back " +
				" to javac in the PATH", e);
		}
	}

	public static class CompileError extends Exception {

		private static final long serialVersionUID = 1L;
		protected Object result;

		public CompileError(final Object result) {
			super("Compile error: " + result);
			this.result = result;
		}

		public Object getResult() {
			return result;
		}
	}

	protected void execute(final String[] args, final File dir,
		final boolean verbose) throws IOException
	{
		if (verbose) {
			String output = "Executing:";
			for (int i = 0; i < args.length; i++)
				output += " '" + args[i] + "'";
			err.println(output);
		}

		/* stupid, stupid Windows... */
		if (System.getProperty("os.name").startsWith("Windows")) {
			for (int i = 0; i < args.length; i++)
				args[i] = quoteArg(args[i]);
			// stupid, stupid, stupid Windows taking all my time!!!
			if (args[0].startsWith("../")) {
				args[0] = new File(dir, args[0]).getAbsolutePath();
			}
		}

		ProcessUtils.exec(dir, err, out, args);
	}

	private static String quotables = " \"\'";

	public static String quoteArg(final String arg) {
		return quoteArg(arg, quotables);
	}

	public static String quoteArg(final String arg, final String quotables) {
		String result = arg;
		for (int j = 0; j < result.length(); j++) {
			final char c = result.charAt(j);
			if (quotables.indexOf(c) >= 0) {
				String replacement;
				if (c == '"') {
					if (System.getenv("MSYSTEM") != null) replacement = "\\" + c;
					else replacement = "'" + c + "'";
				}
				else replacement = "\"" + c + "\"";
				result = result.substring(0, j) + replacement + result.substring(j + 1);
				j += replacement.length() - 1;
			}
		}
		return result;
	}

	protected static JarClassLoader discoverJavac() throws IOException {
		final String ijDir = System.getProperty("ij.dir");
		if (ijDir == null) return null;
		final File jars = new File(ijDir, "jars");
		final File[] javacVersions = FileUtils.getAllVersions(jars, "javac.jar");
		if (javacVersions.length == 0) {
			System.err.println("No javac.jar found (looked in " + jars + ")!");
			return null;
		}
		long newest = Long.MIN_VALUE;
		File javac = null;
		for (final File file : javacVersions) {
			final long mtime = file.lastModified();
			if (newest < mtime) {
				newest = mtime;
				javac = file;
			}
		}
		return new JarClassLoader(javac == null ? null : javac.getPath());
	}
}
