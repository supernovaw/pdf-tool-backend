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

@RestController
public class ExtractController {
	private static final String WORKDIR = "./user-pdfs";

	static {
		File dir = new File(WORKDIR);
		if (!dir.exists()) dir.mkdir();
		else if (dir.isFile()) throw new Error("workdir path occupied by file");
	}

	private static void ensureKeySafety(String key) {
		if (!AuthController.authorisedKeys.contains(key)) throw new Error("unauthorised");
		if (key.startsWith("../") || key.contains("/../")) throw new Error("dangerous key");
	}

	private static String getFileOriginalPath(String key) {
		ensureKeySafety(key);
		return WORKDIR + "/" + key + ".orig.pdf";
	}

	private static String getExtractedDirPath(String key) {
		ensureKeySafety(key);
		return WORKDIR + "/extracted_" + key;
	}

	@PostMapping(path = "/upload")
	public ResponseEntity upload(@RequestParam("key") String key, @RequestParam("document") MultipartFile file) throws IOException {
		if (!AuthController.authorisedKeys.contains(key)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		String path = getFileOriginalPath(key);
		save(file.getInputStream(), path);
		int pages = countPages(path);
		if (pages == -1) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("failed to read document");
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
		File extrDir = new File(getExtractedDirPath(key));
		if (orig.isFile()) orig.delete();
		if (extrDir.isDirectory()) {
			for (File f : extrDir.listFiles())
				f.delete();
			extrDir.delete();
		}
		return "{\"success\":true}";
	}

	@PostMapping(path = "/extract")
	public ResponseEntity extract(@RequestParam String key, @RequestParam String selections) throws IOException, InterruptedException {
		if (!AuthController.authorisedKeys.contains(key)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		String origPath = getFileOriginalPath(key);
		String extrDir = getExtractedDirPath(key);
		int pageCount = countPages(origPath);
		String error = ExtractHandler.extract(origPath, extrDir, pageCount, selections);
		if (error != null) {
			return ResponseEntity
					.status(HttpStatus.BAD_REQUEST)
					.body("{\"message\":\"" + jsonEscape(error) + "\"}");
		}
		return ResponseEntity.status(HttpStatus.OK).body("{\"success\":true}");
	}

	private static String jsonEscape(String s) {
		return s.replaceAll("\\\\", "\\\\\\\\")
				.replaceAll("\"", "\\\\\"")
				.replaceAll("\n", "\\n")
				.replaceAll("\r", "\\r");
	}

	@GetMapping("/download")
	public ResponseEntity<FileSystemResource> download(@RequestParam("key") String key) {
		if (!AuthController.authorisedKeys.contains(key)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		File file = new File(getExtractedDirPath(key) + "/" + ExtractHandler.RESULT_FILENAME);
		if (!file.exists()) {
			return ResponseEntity.notFound().build();
		}
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE);
		return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file));
	}
}
