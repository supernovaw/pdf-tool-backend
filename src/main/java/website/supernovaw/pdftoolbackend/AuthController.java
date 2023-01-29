package website.supernovaw.pdftoolbackend;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

@RestController
public class AuthController {
	public static ArrayList<String> authorisedKeys;

	static {
		try {
			authorisedKeys = new ArrayList<>(Files.readAllLines(Paths.get("./authorised-keys")));
			authorisedKeys.removeIf(key -> key.length() < 4);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@PostMapping(
			path = "/authenticate",
			consumes = {MediaType.APPLICATION_JSON_VALUE},
			produces = {MediaType.APPLICATION_JSON_VALUE}
	)
	public ResponseEntity<Object> authenticate(@RequestBody AuthRequest req) {
		boolean isValid = authorisedKeys.contains(req.key);
		if ("verify".equals(req.action))
			return ResponseEntity.status(HttpStatus.OK).body(new AuthResponse(isValid));
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("unknown action " + req.action);
	}

	public static class AuthRequest {
		public String action;
		public String key;

		public AuthRequest(String action, String key) {
			this.action = action;
			this.key = key;
		}
	}

	public static class AuthResponse {
		public boolean valid;

		public AuthResponse(boolean valid) {
			this.valid = valid;
		}
	}

}
