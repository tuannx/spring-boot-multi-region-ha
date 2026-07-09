package com.multiregion.product.port;

import java.util.function.Supplier;

public interface ProductDataRoute {

    <T> T read(Supplier<T> operation);

    <T> T write(Supplier<T> operation);

    void write(Runnable operation);
}
