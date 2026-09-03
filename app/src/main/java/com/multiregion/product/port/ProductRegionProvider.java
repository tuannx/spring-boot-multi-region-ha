package com.multiregion.product.port;

/** Supplies the deployment region without coupling the product use case to a configuration adapter. */
@FunctionalInterface
public interface ProductRegionProvider {

    String region();
}
