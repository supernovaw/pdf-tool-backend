package website.supernovaw.pdftoolbackend;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

public class ExtractHandler {
	public static final String RESULT_FILENAME = "result.zip";

	// Returns error message or null if successful
	public static String extract(String sourceFile, String workdir, int totalPages, String selectionsStr) throws IOException, InterruptedException {
		Selection[] selections;
		try {
			selections = parseSelections(selectionsStr, totalPages);
		} catch (Exception e) {
			return e.getMessage();
		}

		int totalOutputPages = 0;
		for (Selection s : selections) totalOutputPages += s.pages.length;
		if (totalOutputPages > 5000)
			return "5000 output page limit exceeded: " + totalOutputPages;

		File dir = new File(workdir);
		if (!dir.exists()) dir.mkdir();
		String scriptPath = workdir + "/extract.bash";
		File scriptFile = new File(scriptPath);
		if (scriptFile.exists()) scriptFile.delete();

		String script = buildScript(new File(sourceFile).getAbsolutePath(), dir.getAbsolutePath(), selections);
		Files.writeString(Path.of(scriptFile.toURI()), script);

		// 2>&1 redirects stderr to stdout
		Process p = new ProcessBuilder("bash", "-c", "bash 2>&1 " + escapeArg(scriptPath)).start();
		InputStream stdoutStream = p.getInputStream();
		String stdout = new String(stdoutStream.readAllBytes());
		p.waitFor();
		if (checkGeneratedFilesNum(stdout) != selections.length) {
			long time = System.currentTimeMillis() / 1000;
			Files.writeString(Paths.get(workdir + "/" + time + ".log"), stdout);
			return "something went wrong, error log saved";
		}
		return null;
	}

	private static int checkGeneratedFilesNum(String scriptOutput) {
		int filesNumIndex = scriptOutput.lastIndexOf("GeneratedFiles=");
		if (filesNumIndex == -1) return -1;
		filesNumIndex += 15; // Length of "GeneratedFiles="
		int filesNumIndexEnd = scriptOutput.indexOf(';', filesNumIndex);
		if (filesNumIndexEnd == -1) return -1;
		String number = scriptOutput.substring(filesNumIndex, filesNumIndexEnd);
		try {
			return Integer.parseInt(number);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String buildScript(String sourceFile, String workdir, Selection[] selections) {
		// If multiple files result in the same sanitised filename, make sure they don't collide
		ArrayList<String> sanitisedFilenames = new ArrayList<>();

		StringBuilder s = new StringBuilder();
		s.append("#!/bin/bash\n");
		s.append("cd ").append(escapeArg(workdir)).append('\n');

		s.append("rm -rf Extracted\n");
		s.append("mkdir Extracted\n");
		for (Selection sel : selections) {
			// Sanitise filename and ensure no collisions
			String sanitised = sanitiseFilename(sel.name, ".pdf");
			if (sanitisedFilenames.contains(sanitised)) {
				int attempt = 2;
				String suffixedFilename;
				do {
					suffixedFilename = addSuffix(sanitised, ".pdf", attempt++);
				} while (sanitisedFilenames.contains(suffixedFilename));
				sanitised = suffixedFilename;
			}
			sanitisedFilenames.add(sanitised);

			// E.g.: pdftk 'orig.pdf' cat 4 5 6 10 output test.pdf
			s.append("pdftk ");
			s.append(escapeArg(sourceFile));
			s.append(" cat ");
			for (int p : sel.pages) s.append(p).append(' ');
			s.append("output ");
			s.append("Extracted/").append(escapeArg(sanitised));
			s.append('\n');
		}
		String zipFile = escapeArg(RESULT_FILENAME);
		s.append("rm -rf ").append(zipFile).append('\n');
		s.append("7z a ").append(zipFile).append(" Extracted\n");
		s.append("echo GeneratedFiles=`ls -A Extracted | wc -l`\\;\n");
		s.append("rm -rf Extracted\n");
		return s.toString();
	}

	// addSuffix("file.pdf", ".pdf", 42) => "file 42.pdf"
	private static String addSuffix(String filename, String extension, int suffix) {
		if (!filename.endsWith(extension)) return filename + suffix;
		String withoutExt = filename.substring(0, filename.length() - extension.length());
		return withoutExt + " " + suffix + extension;
	}

	private static String sanitiseFilename(String filename, String extension) {
		if (filename.length() > 100) filename = filename.substring(0, 100);
		filename = filename.replaceAll("[/\\\\|?%*:;`${}\"<>\n\r]", "_");
		if (!filename.toLowerCase().endsWith(extension))
			filename += extension;
		return filename;
	}

	private static String escapeArg(String arg) {
		return "'" + arg.replaceAll("'", "'\\\\''") + "'";
	}

	private static Selection[] parseSelections(String selections, int totalPages) throws Exception {
		selections = selections.replaceAll("\r\n", "\n");
		String[] split = selections.split("\n");
		if (split.length % 2 != 0) throw new Exception("invalid selections:\n" + selections);
		Selection[] result = new Selection[split.length / 2];
		for (int i = 0; i < result.length; i++) {
			result[i] = new Selection(split[i * 2], split[i * 2 + 1], totalPages);
		}
		return result;
	}

	private static class Selection {
		public String name;
		public int[] pages;

		public Selection(String name, String pagesStr, int pagesCount) throws Exception {
			this.name = name;
			String[] pagesSplit = pagesStr.split(",");
			pages = new int[pagesSplit.length];
			for (int i = 0; i < pages.length; i++) {
				try {
					pages[i] = Integer.parseInt(pagesSplit[i]);
				} catch (NumberFormatException e) {
					throw new Exception("invalid number in " + pagesStr);
				}
				if (pages[i] < 1 || pages[i] > pagesCount)
					throw new Exception("out of bounds (" + pagesStr + ") page in " + pagesStr);
			}
			Arrays.sort(pages);
			for (int i = 1; i < pages.length; i++) {
				if (pages[i - 1] == pages[i])
					throw new Exception("duplicate (" + pages[i] + ") in " + pagesStr);
			}
		}
	}
}
