package website.supernovaw.pdftoolbackend;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.ArrayList;

@RestController
public class ExtractController {
	private static final String WORKDIR = "./user-pdfs";

	static {
		File dir = new File(WORKDIR);
		if (!dir.exists()) dir.mkdir();
		else if (dir.isFile()) throw new Error("workdir path occupied by file");
	}

	private static void ensureKeySafety(String key) {
		if (!AuthController.authorisedKeys.contains(key))
			throw new Error("unauthorised");
		if (key.startsWith("../") || key.contains("/../"))
			throw new Error("dangerous key");
	}

	private static String getFileOriginalPath(String key) {
		ensureKeySafety(key);
		return WORKDIR + "/" + key + ".orig.pdf";
	}

	private static String getFileExtractedPath(String key) {
		ensureKeySafety(key);
		return WORKDIR + "/" + key + ".extr.pdf";
	}

	@PostMapping(path = "/upload")
	public ResponseEntity upload(@RequestParam("key") String key, @RequestParam("document") MultipartFile file) throws IOException {
		if (!AuthController.authorisedKeys.contains(key)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		String path = getFileOriginalPath(key);
		save(file.getInputStream(), path);
		int pages = countPages(path);
		if (pages == -1)
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("failed to read document");
		return ResponseEntity.status(HttpStatus.OK).body("{\"pages\":" + pages + "}");
	}

	private static void save(InputStream stream, String path) throws IOException {
		byte[] buffer = new byte[256 * 1024];
		int read;
		OutputStream out = new FileOutputStream(path);
		while ((read = stream.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
		out.close();
		stream.close();
	}

	private static int countPages(String path) throws IOException {
		Process p = new ProcessBuilder("pdftk", path, "dump_data").start();
		String[] dataDump = new String(p.getInputStream().readAllBytes()).split("\n");
		for (String s : dataDump) {
			if (s.matches("^NumberOfPages: \\d+$")) {
				return Integer.parseInt(s.substring(15));
			}
		}
		return -1;
	}

	@PostMapping(path = "/remove")
	public String remove(@RequestParam("key") String key) {
		File orig = new File(getFileOriginalPath(key));
		File extr = new File(getFileExtractedPath(key));
		if (orig.isFile()) orig.delete();
		if (extr.isFile()) extr.delete();
		return "{\"success\":true}";
	}

	@PostMapping(path = "/extract")
	public ResponseEntity extract(@RequestParam("key") String key, @RequestParam String pages) throws IOException, InterruptedException {
		if (!AuthController.authorisedKeys.contains(key)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		String[] pagesSplit = pages.split(",");
		int[] pagesInts = new int[pagesSplit.length];
		for (int i = 0; i < pagesInts.length; i++) {
			try {
				pagesInts[i] = Integer.parseInt(pagesSplit[i]);
			} catch (NumberFormatException e) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid number: " + pagesSplit[i]);
			}
		}
		String origPath = getFileOriginalPath(key);
		String extractedPath = getFileExtractedPath(key);

		int pageCount = countPages(origPath);
		String selectionValidationError = validateSelection(pagesInts, pageCount);
		if (selectionValidationError != null)
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(selectionValidationError);

		if (extract(origPath, extractedPath, pagesInts))
			return ResponseEntity.status(HttpStatus.OK).body("{\"success\":true}");
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("failed to execute command");
	}

	private static String validateSelection(int[] selection, int total) {
		if (selection.length == 0) return "no selection";
		if (selection.length == total) return "all selected";
		if (selection.length > total) return "invalid selection";
		for (int i = 1; i < selection.length; i++) {
			// verify ascending order
			if (selection[i - 1] >= selection[i]) return "invalid selection";
		}
		for (int p : selection) {
			if (p < 1 || p > total)
				return "out of range: " + p;
		}
		return null;
	}

	private static boolean extract(String source, String target, int[] pages) throws IOException, InterruptedException {
		ArrayList<String> command = new ArrayList<>();
		command.add("pdftk");
		command.add(source);
		command.add("cat");
		for (int page : pages) command.add("" + page);
		command.add("output");
		command.add(target);

		Process p = new ProcessBuilder(command).start();
		String output = new String(p.getInputStream().readAllBytes());
		p.waitFor();
		if (p.exitValue() != 0) System.out.println(output);
		return p.exitValue() == 0;
	}


	@GetMapping("/download")
	public ResponseEntity<FileSystemResource> download(@RequestParam("key") String key) {
		if (!AuthController.authorisedKeys.contains(key)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		File file = new File(getFileExtractedPath(key));
		if (!file.exists()) {
			return ResponseEntity.notFound().build();
		}
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE);
		return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file));
	}
}
