package armada.example;

import armada.example.api.GunData;
import armada.example.api.RegistrationData;
import armada.example.api.TargetData;
import armada.example.api.TheatreData;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class NaiveTargetingClient {

    static final ObjectMapper mapper = new ObjectMapper();
    static final OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();

    public static void main(String... args) throws Exception {

        ping();

        TheatreData theatre = register("Ian's Naive Example");

        for (int x = 0; x < theatre.gridWidth(); x++) {
            for (int y = 0; y < theatre.gridHeight(); y++) {
                target(x, y);
                load();
                fire();
            }
        }
        Thread.sleep(1000);
        finish();
    }

    private static void finish() throws IOException {
        final Request target = new Request.Builder()
                .url("http://localhost:7000/finish")
                .build();

        try (Response response = client.newCall(target).execute()) {
            System.out.println(response.body().string());
        }
    }

    private static void target(int x, int y) throws IOException {
        final TargetData data = new TargetData(x, y, 0);
        final Request target = new Request.Builder()
                .method("POST", RequestBody.create(mapper.writeValueAsBytes(data), MediaType.parse("application/json")))
                .url("http://localhost:7000/target")
                .build();

        try (Response response = client.newCall(target).execute()) {
            System.out.println(response.body().string());
        }
    }

    private static void load() throws IOException {
        final GunData data = new GunData(0, 0);
        final Request request = new Request.Builder()
                .method("POST", RequestBody.create(mapper.writeValueAsBytes(data), MediaType.parse("application/json")))
                .url("http://localhost:7000/load")
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println(response.body().string());
        }
    }

    private static void fire() throws IOException {
        final GunData data = new GunData(0, 0);
        final Request request = new Request.Builder()
                .method("POST", RequestBody.create(mapper.writeValueAsBytes(data), MediaType.parse("application/json")))
                .url("http://localhost:7000/fire")
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println(response.body().string());
        }
    }

    private static TheatreData register(String name) throws IOException {
        final RegistrationData registrationData = new RegistrationData(name);
        final Request register = new Request.Builder()
                .method("POST", RequestBody.create(mapper.writeValueAsBytes(registrationData), MediaType.parse("application/json")))
                .url("http://localhost:7000/register")
                .build();

        try (Response response = client.newCall(register).execute()) {
            return mapper.readValue(response.body().bytes(), TheatreData.class);
        }

    }

    private static void ping() throws IOException {
        final Request ping = new Request.Builder()
                .url("http://localhost:7000/ping")
                .build();

        try (Response response = client.newCall(ping).execute()) {
            System.out.println(response.body().string());
        }
    }

}

