package com.coachplatform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs against a REAL embedded server. MockMvc never performs the servlet container's internal error dispatch
 * to /error, which is where Spring Security used to turn every 4xx raised through sendError into a 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealServerStatusCodesTest {

    private static final String PASSWORD = "Prueba-1234-x";

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String registerCoach() throws Exception {
        String email = "coach-" + UUID.randomUUID() + "@test.co";
        var r = send("POST", "/api/auth/register-coach",
                "{\"name\":\"C\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null);
        assertThat(r.statusCode()).isEqualTo(201);
        return JsonPath.read(r.body(), "$.token");
    }

    private String studentToken(String coachToken) throws Exception {
        String email = "alumno-" + UUID.randomUUID() + "@test.co";
        var created = send("POST", "/api/coach/students",
                "{\"fullName\":\"Ana\",\"email\":\"" + email + "\"}", coachToken);
        assertThat(created.statusCode()).isEqualTo(201);
        String inviteUrl = JsonPath.read(created.body(), "$.inviteUrl");
        String token = inviteUrl.substring(inviteUrl.lastIndexOf('/') + 1);
        assertThat(send("POST", "/api/invitations/accept",
                "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}", null).statusCode()).isEqualTo(200);
        var login = send("POST", "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null);
        return JsonPath.read(login.body(), "$.token");
    }

    @Test
    void aStudentCallingACoachEndpointGets403NotAMisleading401() throws Exception {
        String student = studentToken(registerCoach());
        assertThat(send("GET", "/api/coach/plans", null, student).statusCode()).isEqualTo(403);
    }

    @Test
    void noTokenIs401AndABadTokenIs401() throws Exception {
        assertThat(send("GET", "/api/me", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/coach/plans", null, "not-a-jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void malformedJsonIs400AndAnUnknownPathIs404ForALoggedInUser() throws Exception {
        String coach = registerCoach();
        assertThat(send("POST", "/api/coach/plans", "{not json", coach).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/api/coach/does-not-exist", null, coach).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/coach/plans", null, coach).statusCode()).isEqualTo(405);
    }

    @Test
    void theErrorEndpointIsNotReachableDirectlyWithoutAToken() throws Exception {
        assertThat(send("GET", "/error", null, null).statusCode()).isEqualTo(401);
    }
}
