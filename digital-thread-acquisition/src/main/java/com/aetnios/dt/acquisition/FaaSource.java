package com.aetnios.dt.acquisition;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three FAA feeds: the aircraft registry (bulk zip), Airworthiness Directives (Federal Register
 * API), and Service Difficulty Reports. SDRs have no bulk API, so this drives the ASPX query
 * form: get viewstate, post the query, select all rows, post the download.
 */
public class FaaSource implements Source {

    private static final String REGISTRY_URL = "https://registry.faa.gov/database/ReleasableAircraft.zip";
    private static final String AD_URL = "https://www.federalregister.gov/api/v1/documents.json"
            + "?per_page=100&page=%d"
            + "&conditions%%5Btype%%5D%%5B%%5D=RULE"
            + "&conditions%%5Bagencies%%5D%%5B%%5D=federal-aviation-administration"
            + "&conditions%%5Bterm%%5D=airworthiness%%20directive"
            + "&conditions%%5Bpublication_date%%5D%%5Byear%%5D=%d";
    private static final String SDR_URL = "https://sdrs.faa.gov/Query.aspx";

    private static final String CAT_REGISTRY = "faa/registry";
    private static final String CAT_AD = "faa/ad";
    private static final String CAT_SDR = "faa/sdr";

    private static final Pattern HIDDEN_FIELD =
            Pattern.compile("name=\"(__[A-Z]+[A-Za-z]*)\"[^>]*value=\"([^\"]*)\"");
    private static final Pattern CHECKBOX =
            Pattern.compile("name=\"(ctl00\\$pageContentPlaceHolder\\$dgQueryResults\\$[^\"]*cbSelected)\"");
    private static final Pattern RECORD_COUNT = Pattern.compile("returned (\\d+) records");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "faa";
    }

    @Override
    public void pull(Scope scope, HttpJsonClient client, RawStore store, boolean refresh) {
        pullRegistry(client, store, refresh);
        pullAds(scope, client, store, refresh);
        pullAdTexts(scope, client, store, refresh);
        pullSdrs(scope, client, store, refresh);
    }

    /**
     * Full rule text for ADs naming the scoped manufacturer. The affected models and serial
     * ranges live only in the rule body, not the API metadata.
     */
    private void pullAdTexts(Scope scope, HttpJsonClient client, RawStore store, boolean refresh) {
        int fetched = 0, matched = 0;
        for (int year = scope.yearFrom(); year <= scope.yearTo(); year++) {
            try {
                String first = fetchAdPage(year, 1, client, store, refresh);
                int totalPages = mapper.readTree(first).path("total_pages").asInt(1);
                for (int page = 1; page <= totalPages; page++) {
                    for (var d : mapper.readTree(fetchAdPage(year, page, client, store, refresh)).path("results")) {
                        String title = d.path("title").asText("");
                        if (!title.startsWith("Airworthiness Directives")) continue;
                        if (!title.contains("Cessna") && !title.contains("Textron Aviation")) continue;
                        matched++;
                        String doc = d.path("document_number").asText();
                        String date = d.path("publication_date").asText();  // yyyy-MM-dd
                        if (!refresh && store.exists("faa/ad_text", doc, "txt")) continue;
                        String url = "https://www.federalregister.gov/documents/full_text/text/"
                                + date.replace('-', '/') + "/" + doc + ".txt";
                        try {
                            HttpJsonClient.Response resp = client.get(url);
                            if (resp.status() != 200) throw new RuntimeException("HTTP " + resp.status());
                            store.write("faa/ad_text", doc, "txt", url, resp.status(), resp.body());
                            fetched++;
                        } catch (Exception e) {
                            System.err.printf("ad text %s failed: %s%n", doc, e.getMessage());
                            store.logFailure(url, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.printf("ad text scan %d failed: %s%n", year, e.getMessage());
            }
        }
        System.out.printf("ad texts: %d matched scope, %d newly fetched%n", matched, fetched);
    }

    private void pullRegistry(HttpJsonClient client, RawStore store, boolean refresh) {
        String name = "releasable_aircraft";
        if (!refresh && store.exists(CAT_REGISTRY, name, "zip")) return;
        try {
            System.out.println("registry: downloading " + REGISTRY_URL);
            HttpJsonClient.Response resp = client.get(REGISTRY_URL);
            if (resp.status() != 200) throw new RuntimeException("HTTP " + resp.status());
            byte[] b = resp.body();
            if (b.length < 4 || b[0] != 'P' || b[1] != 'K') throw new RuntimeException("not a zip, got " + b.length + " bytes");
            store.write(CAT_REGISTRY, name, "zip", REGISTRY_URL, resp.status(), b);
            System.out.printf("registry: %d bytes%n", b.length);
        } catch (Exception e) {
            System.err.println("registry failed: " + e.getMessage());
            store.logFailure(REGISTRY_URL, e.getMessage());
        }
    }

    private void pullAds(Scope scope, HttpJsonClient client, RawStore store, boolean refresh) {
        for (int year = scope.yearFrom(); year <= scope.yearTo(); year++) {
            try {
                String first = fetchAdPage(year, 1, client, store, refresh);
                int totalPages = mapper.readTree(first).path("total_pages").asInt(1);
                System.out.printf("ad %d: %d pages%n", year, totalPages);
                for (int page = 2; page <= totalPages; page++) {
                    fetchAdPage(year, page, client, store, refresh);
                }
            } catch (Exception e) {
                System.err.printf("ad %d failed: %s%n", year, e.getMessage());
                store.logFailure(String.format(AD_URL, 1, year), e.getMessage());
            }
        }
    }

    private String fetchAdPage(int year, int page, HttpJsonClient client, RawStore store, boolean refresh) throws Exception {
        String name = String.format("ad_%d_p%03d", year, page);
        String url = String.format(AD_URL, page, year);
        if (!refresh && store.exists(CAT_AD, name, "json")) return store.read(CAT_AD, name, "json");
        HttpJsonClient.Response resp = client.get(url);
        if (resp.status() != 200) throw new RuntimeException("HTTP " + resp.status());
        store.write(CAT_AD, name, "json", url, resp.status(), resp.body());
        return resp.text();
    }

    private void pullSdrs(Scope scope, HttpJsonClient client, RawStore store, boolean refresh) {
        for (String make : scope.makes()) {
            for (int year = scope.yearFrom(); year <= scope.yearTo(); year++) {
                for (int month = 1; month <= 12; month++) {
                    YearMonth ym = YearMonth.of(year, month);
                    String name = String.format("%s_%d_%02d", make, year, month);
                    pullSdrWindow(make, ym.atDay(1), ym.atEndOfMonth(), name, client, store, refresh);
                }
            }
        }
    }

    /**
     * One date window. If the result grid paginates (more records than visible checkboxes), the
     * window halves and recurses; fragments store as name_a / name_b and are idempotent like
     * whole months. ponytail: a formerly-split month re-runs its query on every crawl (no month
     * marker file, only fragments exist) — three extra requests per such month; add a marker if
     * split months ever get common.
     */
    private void pullSdrWindow(String make, LocalDate from, LocalDate to, String name,
                               HttpJsonClient client, RawStore store, boolean refresh) {
        if (!refresh && store.exists(CAT_SDR, name, "xls")) return;
        try {
            HttpJsonClient.Response blank = client.get(SDR_URL);
            if (blank.status() != 200) throw new RuntimeException("query page HTTP " + blank.status());

            List<String[]> query = hiddenFields(blank.text());
            query.add(new String[]{"ctl00$pageContentPlaceHolder$tbDifficultyDateFrom", usDate(from)});
            query.add(new String[]{"ctl00$pageContentPlaceHolder$tbDifficultyDateTo", usDate(to)});
            query.add(new String[]{"ctl00$pageContentPlaceHolder$tbAircraftManufacturer", make});
            for (String tb : new String[]{"tbAircraftModel", "tbEngineManufacturer", "tbEngineModel",
                    "tbPropellerManufacturer", "tbPropellerModel", "tbPartManufacturer", "tbPartName",
                    "tbPartNumber", "tbJASCCode", "tbOperatorDesignator", "tbOperatorControlNumber",
                    "tbRegistrationNumber", "tbProblemDescription"}) {
                query.add(new String[]{"ctl00$pageContentPlaceHolder$" + tb, ""});
            }
            query.add(new String[]{"ctl00$pageContentPlaceHolder$btnQuery", "Search"});

            HttpJsonClient.Response results = client.postForm(SDR_URL, encode(query));
            if (results.status() != 200) throw new RuntimeException("query post HTTP " + results.status());
            String html = results.text();

            Matcher count = RECORD_COUNT.matcher(html);
            int records = count.find() ? Integer.parseInt(count.group(1)) : 0;

            List<String[]> download = hiddenFields(html);
            List<String> boxes = new ArrayList<>();
            Matcher cb = CHECKBOX.matcher(html);
            while (cb.find()) boxes.add(cb.group(1));
            // Split when rows are missing from the grid (boxes < records) — and also when rows
            // are present but the "returned N records" message is absent (records == 0): the
            // server drops the message and silently caps the grid at 3000 rows on large result
            // sets, so an unverifiable count means the download can't be trusted.
            if (!boxes.isEmpty() && (records == 0 || boxes.size() < records)) {
                if (from.equals(to)) throw new RuntimeException(
                        "grid incomplete within a single day: " + boxes.size() + " of " + records + " rows visible");
                LocalDate mid = from.plusDays(ChronoUnit.DAYS.between(from, to) / 2);
                System.out.printf("sdr %s: %d of %d rows visible, splitting %s..%s%n",
                        name, boxes.size(), records, from, to);
                pullSdrWindow(make, from, mid, name + "_a", client, store, refresh);
                pullSdrWindow(make, mid.plusDays(1), to, name + "_b", client, store, refresh);
                return;
            }
            for (String b : boxes) download.add(new String[]{b, "on"});
            download.add(new String[]{"ctl00$pageContentPlaceHolder$btnDownload", "Download"});

            HttpJsonClient.Response file = client.postForm(SDR_URL, encode(download));
            if (file.status() != 200) throw new RuntimeException("download post HTTP " + file.status());
            store.write(CAT_SDR, name, "xls", SDR_URL + "#" + name, file.status(), file.body());
            System.out.printf("sdr %s: %d records%n", name, records);
        } catch (Exception e) {
            System.err.printf("sdr %s failed: %s%n", name, e.getMessage());
            store.logFailure(SDR_URL + "#" + name, e.getMessage());
        }
    }

    private static String usDate(LocalDate d) {
        return String.format("%02d/%02d/%d", d.getMonthValue(), d.getDayOfMonth(), d.getYear());
    }

    private static List<String[]> hiddenFields(String html) {
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"__EVENTTARGET", ""});
        fields.add(new String[]{"__EVENTARGUMENT", ""});
        Matcher m = HIDDEN_FIELD.matcher(html);
        while (m.find()) fields.add(new String[]{m.group(1), m.group(2)});
        return fields;
    }

    private static String encode(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(p[0], StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(p[1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
