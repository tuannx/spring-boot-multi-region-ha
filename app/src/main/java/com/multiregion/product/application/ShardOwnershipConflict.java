package com.multiregion.product.application;

public final class ShardOwnershipConflict extends RuntimeException {

    private final String ownerRegion;
    private final String requestedRegion;

    public ShardOwnershipConflict(String ownerRegion, String requestedRegion) {
        super("product shard " + requestedRegion + " is owned by that region; this writer serves " + ownerRegion);
        this.ownerRegion = ownerRegion;
        this.requestedRegion = requestedRegion;
    }

    public String ownerRegion() {
        return ownerRegion;
    }

    public String requestedRegion() {
        return requestedRegion;
    }
}
