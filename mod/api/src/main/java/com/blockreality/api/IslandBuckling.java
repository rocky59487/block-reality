package com.blockreality.api;

/** One engine-supplied island result. A refused world can still contain computed islands. */
public record IslandBuckling(int island, Kind kind, BucklingState state, double factor) {
    public enum Kind { NONE, EIGEN }
}
