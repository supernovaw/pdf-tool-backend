package website.supernovaw.pdftoolbackend;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
	@Override
	public void addCorsMappings(CorsRegistry registry) {
		String origin = System.getenv("ORIGIN");
		if (origin != null)
			registry.addMapping("/**")
					.allowedOrigins(System.getenv(origin))
					.allowedMethods("*");
	}
}