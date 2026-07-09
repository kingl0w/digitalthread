package com.aetnios.dt.core;

import java.nio.file.Path;

public class CoreApp {

    public static void main(String[] args) throws Exception {
        Path raw = Path.of(System.getProperty("raw", "../digital-thread-acquisition/data/raw"));
        Path canonical = Path.of(System.getProperty("canonical", "data/canonical"));
        Path seed = Path.of(System.getProperty("seed", "data/seed"));
        String make = System.getProperty("make", "CESSNA");
        long rngSeed = Long.parseLong(System.getProperty("rngSeed", "42"));
        int fleetSize = Integer.parseInt(System.getProperty("fleetSize", "500"));

        Transform.Result result = new Transform(raw, canonical, make).run();
        new Seed(canonical, seed, rngSeed, fleetSize, result.airframes()).run();
        System.out.println("done");
    }
}
