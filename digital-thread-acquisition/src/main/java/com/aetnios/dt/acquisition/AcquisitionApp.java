package com.aetnios.dt.acquisition;

import java.nio.file.Path;
import java.util.List;

public class AcquisitionApp {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(System.getProperty("out", "data/raw"));
        boolean refresh = Boolean.parseBoolean(System.getProperty("refresh", "false"));
        long rateMillis = Long.parseLong(System.getProperty("rateMillis", "300"));

        Scope scope = Scope.load();
        HttpJsonClient client = new HttpJsonClient(rateMillis);
        RawStore store = new RawStore(out);

        List<Source> sources = List.of(new FaaSource());
        for (Source source : sources) {
            System.out.println("pulling source: " + source.name());
            source.pull(scope, client, store, refresh);
        }
        System.out.println("done");
    }
}
