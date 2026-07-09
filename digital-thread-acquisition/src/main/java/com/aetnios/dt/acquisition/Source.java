package com.aetnios.dt.acquisition;

/** A pluggable data source. FAA and openFDA drop in later as new implementations. */
public interface Source {
    String name();
    void pull(Scope scope, HttpJsonClient client, RawStore store, boolean refresh);
}
