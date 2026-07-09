package com.aetnios.dt.acquisition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class NhtsaSource implements Source {

    private static final String VPIC = "https://vpic.nhtsa.dot.gov/api/vehicles/GetModelsForMakeYear/make/%s/modelyear/%d?format=json";
    private static final String RECALLS = "https://api.nhtsa.gov/recalls/recallsByVehicle?make=%s&model=%s&modelYear=%d";
    private static final String COMPLAINTS = "https://api.nhtsa.gov/complaints/complaintsByVehicle?make=%s&model=%s&modelYear=%d";

    private static final String CAT_MODELS = "nhtsa/vpic/models";
    private static final String CAT_RECALLS = "nhtsa/recalls";
    private static final String CAT_COMPLAINTS = "nhtsa/complaints";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "nhtsa";
    }

    @Override
    public void pull(Scope scope, HttpJsonClient client, RawStore store, boolean refresh) {
        for (String make : scope.makes()) {
            for (int year = scope.yearFrom(); year <= scope.yearTo(); year++) {
                List<String> models;
                try {
                    models = fetchModels(make, year, client, store, refresh);
                } catch (Exception e) {
                    System.err.printf("model list failed for %s %d: %s%n", make, year, e.getMessage());
                    store.logFailure(String.format(VPIC, enc(make), year), e.getMessage());
                    continue;
                }
                System.out.printf("%s %d: %d models%n", make, year, models.size());
                for (String model : models) {
                    fetchPerModel(CAT_RECALLS, String.format(RECALLS, enc(make), enc(model), year),
                            make, model, year, client, store, refresh);
                    fetchPerModel(CAT_COMPLAINTS, String.format(COMPLAINTS, enc(make), enc(model), year),
                            make, model, year, client, store, refresh);
                }
            }
        }
    }

    /** Fetches (or reuses) the vPIC model list, then extracts Results[].Model_Name to drive the crawl. */
    private List<String> fetchModels(String make, int year, HttpJsonClient client, RawStore store, boolean refresh) throws Exception {
        String name = make + "_" + year;
        String url = String.format(VPIC, enc(make), year);
        String body;
        if (!refresh && store.exists(CAT_MODELS, name, "json")) {
            body = store.read(CAT_MODELS, name, "json");
        } else {
            HttpJsonClient.Response resp = client.get(url);
            if (resp.status() != 200) throw new RuntimeException("HTTP " + resp.status());
            store.write(CAT_MODELS, name, "json", url, resp.status(), resp.body());
            body = resp.text();
        }
        // vPIC substring-matches the make, so filter to the exact make. Dedupe: vPIC lists each model twice.
        Set<String> models = new LinkedHashSet<>();
        JsonNode results = mapper.readTree(body).path("Results");
        results.forEach(r -> {
            String m = r.path("Model_Name").asText("");
            if (!m.isEmpty() && r.path("Make_Name").asText("").equalsIgnoreCase(make)) models.add(m);
        });
        return new ArrayList<>(models);
    }

    private void fetchPerModel(String category, String url, String make, String model, int year,
                               HttpJsonClient client, RawStore store, boolean refresh) {
        String name = make + "_" + model + "_" + year;
        if (!refresh && store.exists(category, name, "json")) return;
        try {
            HttpJsonClient.Response resp = client.get(url);
            if (resp.status() != 200) throw new RuntimeException("HTTP " + resp.status());
            store.write(category, name, "json", url, resp.status(), resp.body());
        } catch (Exception e) {
            System.err.printf("%s failed for %s %s %d: %s%n", category, make, model, year, e.getMessage());
            store.logFailure(url, e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
