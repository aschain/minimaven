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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The build environment of MiniMaven.
 * <p>
 * This class contains settings and methods that are not specific to a single
 * {@link MavenProject}, but rather specific to a tree of projects.
 * </p>
 *
 * @author Johannes Schindelin
 */
public class BuildEnvironment {

	public final static String IMAGEJ_APP_DIRECTORY = "imagej.app.directory";

	protected String endLine = isInteractiveConsole() ? "\033[K\r" : "\n";
	protected boolean verbose, debug = false, downloadAutomatically, offlineMode,
			ignoreMavenRepositories;

	// by default, check once per 24h for new snapshot versions
	protected int updateInterval = 24 * 60;

	protected PrintStream err;
	protected JavaCompiler javac;
	protected Map<String, MavenProject> localPOMCache =
		new HashMap<String, MavenProject>();
	protected Map<File, MavenProject> file2pom =
		new HashMap<File, MavenProject>();
	protected Stack<File> multiProjectRoots = new Stack<File>();
	protected Set<File> excludedFromMultiProjects = new HashSet<File>();
	protected final static File mavenRepository;
	private final static boolean isWindows;

	static {
		File repository = new File(System.getProperty("user.home"),
			".m2/repository");
		try {
			repository = repository.getCanonicalFile();
		}
		catch (final IOException e) {
			e.printStackTrace();
		}
		mavenRepository = repository;

		final String osName = System.getProperty("os.name").toLowerCase();
		isWindows = osName.startsWith("win");
	}

	public void setVerbose(final boolean verbose) {
		this.verbose = verbose;
	}

	public void setDebug(final boolean debug) {
		this.debug = debug;
	}

	public boolean getDownloadAutomatically() {
		return downloadAutomatically && !offlineMode;
	}

	protected static boolean isInteractiveConsole() {
		// We want to compile/run with Java5, so we cannot test System.console()
		// directly
		try {
			return null != System.class.getMethod("console").invoke(null);
		}
		catch (final Throwable t) {
			return false;
		}
	}

	public BuildEnvironment(final PrintStream err,
		final boolean downloadAutomatically, final boolean verbose,
		final boolean debug)
	{
		this.err = err == null ? System.err : err;
		javac = new JavaCompiler(this.err, this.err);
		this.downloadAutomatically = downloadAutomatically;
		this.verbose = verbose;
		this.debug = debug;
		if ("true".equalsIgnoreCase(System.getProperty("minimaven.offline")))
			offlineMode = true;
		if ("ignore".equalsIgnoreCase(System.getProperty("minimaven.repositories")))
			ignoreMavenRepositories = true;
		final String updateInterval = System.getProperty(
			"minimaven.updateinterval");
		if (updateInterval != null && !updateInterval.equals("")) try {
			this.updateInterval = Integer.parseInt(updateInterval);
			if (verbose) {
				this.err.println("Setting update interval to " + this.updateInterval +
					" minutes");
			}
		}
		catch (final NumberFormatException e) {
			this.err.println("Warning: ignoring invalid update interval " +
				updateInterval);
		}
	}

	public PrintStream getErr() {
		return err;
	}

	protected void print80(final String string) {
		final int length = string.length();
		err.print((verbose || length < 80 ? string : string.substring(0, 80)) +
			endLine);
	}

	public MavenProject parse(final File file) throws IOException,
		ParserConfigurationException, SAXException
	{
		return parse(file, null);
	}

	public MavenProject parse(final File file, final MavenProject parent)
		throws IOException, ParserConfigurationException, SAXException
	{
		return parse(file, parent, null);
	}

	public MavenProject parse(final File file, final MavenProject parent,
		final String classifier) throws IOException, ParserConfigurationException,
			SAXException
	{
		if (file2pom.containsKey(file)) {
			final MavenProject result = file2pom.get(file);
			if (classifier == null) {
				if (result.coordinate.classifier == null) {
					return result;
				}
			}
			else if (classifier.equals(result.coordinate.classifier)) {
				return result;
			}
		}

		if (!file.exists()) return null;
		if (verbose) print80("Parsing " + file);
		final File directory = file.getCanonicalFile().getParentFile();
		final MavenProject pom = parse(new FileInputStream(file), directory, parent,
			classifier);
		file2pom.put(file, pom);
		return pom;
	}

	public MavenProject parse(final InputStream in, final File directory,
		final MavenProject parent, final String classifier) throws SAXException,
			ParserConfigurationException, IOException
	{
		final MavenProject pom = new MavenProject(this, directory, parent);
		pom.coordinate.classifier = classifier;
		if (parent != null) {
			pom.sourceDirectory = parent.sourceDirectory;
			pom.includeImplementationBuild = parent.includeImplementationBuild;
		}
		pom.parse(in);
		if (pom.coordinate.artifactId == null || pom.coordinate.artifactId.equals(
			""))
		{
			throw new SAXException("Missing artifactId: " + new File(directory,
				"pom.xml"));
		}
		if (pom.coordinate.groupId == null || pom.coordinate.groupId.equals("")) {
			throw new SAXException("Missing groupId: " + new File(directory,
				"pom.xml"));
		}
		final String version = pom.coordinate.getVersion();
		if (version == null || version.equals("")) {
			throw new SAXException("Missing version: " + new File(directory,
				"pom.xml"));
		}

		pom.children = new MavenProject[pom.modules.size()];
		for (int i = 0; i < pom.children.length; i++) {
			final File child = new File(directory, pom.modules.get(i) + "/pom.xml");
			pom.children[i] = parse(child, pom);
		}

		if (pom.target == null) {
			final String fileName = pom.coordinate.getJarName();
			pom.target = new File(directory, fileName);
		}

		final String key = pom.expand(pom.coordinate).getKey();
		if (!localPOMCache.containsKey(key)) localPOMCache.put(key, pom);

		if (pom.isJAR() && !directory.getPath().startsWith(mavenRepository
			.getPath()))
		{
			pom.buildFromSource = true;
			pom.target = new File(directory, "target/classes");
		}

		if (pom.parentCoordinate != null && pom.parent == null) {
			final Coordinate dependency = pom.expand(pom.parentCoordinate);
			pom.parent = pom.findPOM(dependency, true, false);

			if (pom.parent == null) {
				File parentDirectory = pom.directory.getParentFile();
				if (parentDirectory == null) try {
					parentDirectory = pom.directory.getCanonicalFile().getParentFile();
				}
				catch (final IOException e) {
					e.printStackTrace();
				}
				if (parentDirectory != null) {
					final File parentFile = new File(parentDirectory, "pom.xml");
					if (parentFile.exists()) pom.parent = parse(parentFile, null, null);
				}
			}

			if (pom.parent == null && downloadAutomatically) {
				if (pom.maybeDownloadAutomatically(pom.parentCoordinate, !verbose,
					downloadAutomatically))
				{
					pom.parent = pom.findPOM(dependency, !verbose, downloadAutomatically);
				}
			}
			if (pom.parent == null) {
				throw new RuntimeException("Parent not found: " + pom.parentCoordinate +
					(downloadAutomatically ? ""
						: " (please call MiniMaven's 'download'"));
			}
			// prevent infinite loops (POMs without parents get the current root as
			// parent)
			if (pom.parent.parent == pom) pom.parent.parent = null;
			if (pom.parent.includeImplementationBuild) {
				pom.includeImplementationBuild = true;
			}
			pom.parent.addChild(pom);
		}

		return pom;
	}

	public MavenProject fakePOM(final File target, final Coordinate dependency) {
		final MavenProject pom = new MavenProject(this, target, null);
		pom.directory = target.getParentFile();
		pom.target = target;
		pom.children = new MavenProject[0];
		pom.coordinate = dependency;
		if (dependency.artifactId.equals("ij")) {
			final String javac = pom.expand("${java.home}/../lib/tools.jar");
			if (new File(javac).exists()) {
				pom.dependencies.add(new Coordinate("com.sun", "tools", "1.4.2", null,
					false, javac, null, null));
			}
		}
		else if (dependency.artifactId.equals("imglib2-io")) {
			pom.dependencies.add(new Coordinate("loci", "bio-formats",
				"${bio-formats.version}"));
		}
		else if (dependency.artifactId.equals("jfreechart")) {
			pom.dependencies.add(new Coordinate("jfree", "jcommon", "1.0.17"));
		}

		final String key = dependency.getKey();
		if (debug && localPOMCache.containsKey(key)) {
			err.println("Warning: " + target + " overrides " + localPOMCache.get(
				key));
		}
		localPOMCache.put(key, pom);

		return pom;
	}

	public boolean containsProject(final String groupId,
		final String artifactId)
	{
		return containsProject(new Coordinate(groupId, artifactId, null));
	}

	public boolean containsProject(final Coordinate coordinate) {
		return localPOMCache.containsKey(coordinate.getKey());
	}

	public void addMultiProjectRoot(final File root) {
		try {
			multiProjectRoots.push(root.getCanonicalFile());
		}
		catch (final IOException e) {
			multiProjectRoots.push(root);
		}
	}

	public void excludeFromMultiProjects(final File directory) {
		try {
			excludedFromMultiProjects.add(directory.getCanonicalFile());
		}
		catch (final IOException e) {
			excludedFromMultiProjects.add(directory);
		}
	}

	public void parseMultiProjects() throws IOException,
		ParserConfigurationException, SAXException
	{
		while (!multiProjectRoots.empty()) {
			final File root = multiProjectRoots.pop();
			if (root == null || !root.exists()) continue;
			final File[] list = root.listFiles();
			if (list == null) continue;
			Arrays.sort(list);
			for (final File directory : list) {
				if (excludedFromMultiProjects.contains(directory)) continue;
				final File file = new File(directory, "pom.xml");
				if (!file.exists()) continue;
				parse(file, null);
			}
		}
	}

	protected void downloadAndVerify(final String repositoryURL,
		final Coordinate dependency, final boolean quiet)
			throws MalformedURLException, IOException, NoSuchAlgorithmException,
			ParserConfigurationException, SAXException
	{
		String path = "/" + dependency.groupId.replace('.', '/') + "/" +
			dependency.artifactId + "/" + dependency.version + "/";
		File directory = new File(mavenRepository, path);
		if (dependency.version.endsWith("-SNAPSHOT")) {
			// Only check snapshots once per day
			final File snapshotMetaData = new File(directory,
				"maven-metadata-snapshot.xml");
			if (System.currentTimeMillis() - snapshotMetaData
				.lastModified() < updateInterval * 60 * 1000l)
			{
				return;
			}

			final String message = quiet ? null : "Checking for new snapshot of " +
				dependency.artifactId;
			final String metadataURL = repositoryURL + path + "maven-metadata.xml";
			downloadAndVerify(metadataURL, directory, snapshotMetaData.getName(),
				message);
			final String snapshotVersion = SnapshotPOMHandler.parse(snapshotMetaData);
			if (snapshotVersion == null) {
				throw new IOException("No version found in " + metadataURL);
			}
			dependency.setSnapshotVersion(snapshotVersion);
			if (new File(directory, dependency.getJarName()).exists() && new File(
				directory, dependency.getPOMName()).exists())
			{
				return;
			}
		}
		else if (dependency.version.startsWith("[")) {
			path = "/" + dependency.groupId.replace('.', '/') + "/" +
				dependency.artifactId + "/";
			directory = new File(mavenRepository, path);

			// Only check versions once per day
			final File versionMetaData = new File(directory,
				"maven-metadata-version.xml");
			if (System.currentTimeMillis() - versionMetaData
				.lastModified() < updateInterval * 60 * 1000l)
			{
				return;
			}

			final String message = quiet ? null : "Checking for new version of " +
				dependency.artifactId;
			final String metadataURL = repositoryURL + path + "maven-metadata.xml";
			downloadAndVerify(metadataURL, directory, versionMetaData.getName(),
				message);
			dependency.snapshotVersion = VersionPOMHandler.parse(versionMetaData);
			if (dependency.snapshotVersion == null) {
				throw new IOException("No version found in " + metadataURL);
			}
			path = "/" + dependency.groupId.replace('.', '/') + "/" +
				dependency.artifactId + "/" + dependency.snapshotVersion + "/";
			directory = new File(mavenRepository, path);
			if (new File(directory, dependency.getJarName()).exists() && new File(
				directory, dependency.getPOMName()).exists())
			{
				return;
			}
		}
		final String message = quiet ? null : "Downloading " +
			dependency.artifactId;
		final String baseURL = repositoryURL + path;
		downloadAndVerify(baseURL + dependency.getPOMName(), directory, null);
		if (!isAggregatorPOM(new File(directory, dependency.getPOMName()))) {
			downloadAndVerify(baseURL + dependency.getJarName(), directory, message);
		}
	}

	protected void downloadAndVerify(final String url, final File directory,
		final String message) throws IOException, NoSuchAlgorithmException
	{
		downloadAndVerify(url, directory, null, message);
	}

	protected void downloadAndVerify(final String url, final File directory,
		String fileName, final String message) throws IOException,
			NoSuchAlgorithmException
	{
		if (fileName == null) {
			fileName = url.substring(url.lastIndexOf('/') + 1);
		}
		final File sha1 = download(new URL(url + ".sha1"), directory, fileName +
			".sha1.new", null);
		final File file = download(new URL(url), directory, fileName + ".new",
			message);
		final MessageDigest digest = MessageDigest.getInstance("SHA-1");
		FileInputStream fileStream = new FileInputStream(file);
		final DigestInputStream digestStream = new DigestInputStream(fileStream,
			digest);
		final byte[] buffer = new byte[131072];
		while (digestStream.read(buffer) >= 0) {
			/* do nothing */
		}
		digestStream.close();

		final byte[] digestBytes = digest.digest();
		fileStream = new FileInputStream(sha1);
		for (int i = 0; i < digestBytes.length; i++) {
			final int value = (hexNybble(fileStream.read()) << 4) | hexNybble(
				fileStream.read());
			final int d = digestBytes[i] & 0xff;
			if (value != d) {
				String actual = "";
				for (final byte b : digestBytes)
					actual += String.format("%02x", b & 0xff);
				fileStream.close();
				throw new IOException("SHA1 mismatch: " + sha1 + ": " + Integer
					.toHexString(value) + " != " + Integer.toHexString(d) +
					" (actual SHA-1: " + actual + ")");
			}
		}
		fileStream.close();
		rename(file, new File(directory, fileName));
		rename(sha1, new File(directory, fileName + ".sha1"));
	}

	protected void rename(final File source, final File target)
		throws IOException
	{
		if (isWindows && target.exists()) {
			if (!target.delete()) {
				throw new IOException("Could not delete " + target);
			}
		}
		if (!source.renameTo(target)) {
			throw new IOException("Could not rename " + source + " to " + target);
		}
	}

	protected boolean isAggregatorPOM(final File xml) {
		if (!xml.exists()) return false;
		try {
			return isAggregatorPOM(new FileInputStream(xml));
		}
		catch (final IOException e) {
			e.printStackTrace(err);
			return false;
		}
	}

	protected boolean isAggregatorPOM(final InputStream in) {
		final RuntimeException yes = new RuntimeException(), no =
			new RuntimeException();
		try {
			final DefaultHandler handler = new AbstractPOMHandler() {

				protected int level = 0;

				@Override
				public void startElement(final String uri, final String localName,
					final String qName, final Attributes attributes)
				{
					super.startElement(uri, localName, qName, attributes);
					if ((level == 0 && "project".equals(qName)) || (level == 1 &&
						"packaging".equals(qName)))
					{
						level++;
					}
				}

				@Override
				public void endElement(final String uri, final String localName,
					final String qName) throws SAXException
				{
					super.endElement(uri, localName, qName);
					if ((level == 1 && "project".equals(qName)) || (level == 2 &&
						"packaging".equals(qName)))
					{
						level--;
					}
				}

				@Override
				public void processCharacters(final StringBuilder sb) {
					if (level == 2) throw "pom".equals(sb.toString()) ? yes : no;
				}
			};
			final XMLReader reader = SAXParserFactory.newInstance().newSAXParser()
				.getXMLReader();
			reader.setContentHandler(handler);
			reader.parse(new InputSource(in));
			in.close();
			return false;
		}
		catch (final Exception e) {
			try {
				in.close();
			}
			catch (final IOException e2) {
				e2.printStackTrace(err);
			}
			if (e == yes) return true;
			if (e == no) return false;
			e.printStackTrace(err);
			return false;
		}
	}

	protected static int hexNybble(final int b) {
		return (b < 'A' ? (b < 'a' ? b - '0' : b - 'a' + 10) : b - 'A' + 10) & 0xf;
	}

	protected static void rmRF(final File directory) {
		for (final File file : directory.listFiles())
			if (file.isDirectory()) rmRF(file);
			else file.delete();
		directory.delete();
	}

	protected File download(final URL url, final File directory,
		final String message) throws IOException
	{
		return download(url, directory, null, message);
	}

	protected File download(final URL url, final File directory,
		final String fileName, final String message) throws IOException
	{
		if (offlineMode) throw new RuntimeException("Offline!");
		if (verbose) err.println("Trying to download " + url);
		String name = fileName;
		if (name == null) {
			name = url.getPath();
			name = name.substring(name.lastIndexOf('/') + 1);
		}
		final URLConnection connection = url.openConnection();
		if (connection instanceof HttpURLConnection) {
			final HttpURLConnection http = (HttpURLConnection) connection;
			http.setRequestProperty("User-Agent", "MiniMaven/2.0.0-SNAPSHOT");
		}
		final InputStream in = connection.getInputStream();
		if (message != null) err.println(message);
		directory.mkdirs();
		final File result = new File(directory, name);
		if (verbose) {
			err.println("Downloading " + url + " to " + result.getAbsolutePath());
		}
		copy(in, result);
		return result;
	}

	public static void copyFile(final File source, final File target)
		throws IOException
	{
		copy(new FileInputStream(source), target);
	}

	public static void copy(final InputStream in, final File target)
		throws IOException
	{
		copy(in, new FileOutputStream(target), true);
	}

	public static void copy(final InputStream in, final OutputStream out,
		final boolean closeOutput) throws IOException
	{
		final byte[] buffer = new byte[131072];
		for (;;) {
			final int count = in.read(buffer);
			if (count < 0) break;
			out.write(buffer, 0, count);
		}
		in.close();
		if (closeOutput) out.close();
	}

	protected static boolean isSnapshotVersion(final String version) {
		return version != null && version.endsWith("-SNAPSHOT");
	}

	protected static boolean isTimestampVersion(final String version) {
		return version != null && version.matches("2\\d{7,13}");
	}

	protected static int compareVersion(final String version1,
		final String version2)
	{
		if (version1 == null) return version2 == null ? 0 : -1;
		if (version1.equals(version2)) return 0;

		// prefer snapshot over timestamp versions
		// (AKA the mpicbg problem)
		if (isTimestampVersion(version1) && isSnapshotVersion(version2)) return -1;
		if (isSnapshotVersion(version1) && isTimestampVersion(version2)) return +1;

		final String[] split1 = version1.split("\\.");
		final String[] split2 = version2.split("\\.");

		for (int i = 0;; i++) {
			if (i == split1.length) return i == split2.length ? 0 : -1;
			if (i == split2.length) return +1;
			final int end1 = firstNonDigit(split1[i]);
			final int end2 = firstNonDigit(split2[i]);
			if (end1 != end2) return end1 - end2;
			int result = end1 == 0 ? 0 : Integer.parseInt(split1[i].substring(0,
				end1)) - Integer.parseInt(split2[i].substring(0, end2));
			if (result != 0) return result;
			result = split1[i].substring(end1).compareTo(split2[i].substring(end2));
			if (result != 0) return result;
		}
	}

	protected static int firstNonDigit(final String string) {
		final int length = string.length();
		for (int i = 0; i < length; i++)
			if (!Character.isDigit(string.charAt(i))) return i;
		return length;
	}

	protected String getImplementationBuild(final File fileOrDirectory) {
		File file = fileOrDirectory;
		if (!file.isAbsolute()) {
			try {
				file = file.getCanonicalFile();
			}
			catch (final IOException e) {
				file = file.getAbsoluteFile();
			}
		}
		for (;;) {
			final File gitDir = new File(file, ".git");
			if (gitDir.exists()) {
				return exec(gitDir.getParentFile(), "git", "rev-parse", "HEAD");
			}
			file = file.getParentFile();
			if (file == null) return null;
		}
	}

	protected String exec(final File gitDir, final String... args) {
		try {
			final Process process = Runtime.getRuntime().exec(args, null, gitDir);
			process.getOutputStream().close();
			final ReadInto err = new ReadInto(process.getErrorStream(), this.err);
			final ReadInto out = new ReadInto(process.getInputStream(), null);
			try {
				process.waitFor();
				err.join();
				out.join();
			}
			catch (final InterruptedException e) {
				throw new RuntimeException(e);
			}
			if (process.exitValue() != 0) {
				throw new RuntimeException("Error executing " + Arrays.toString(args) +
					"\n" + err);
			}
			return out.toString();
		}
		catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}
}
