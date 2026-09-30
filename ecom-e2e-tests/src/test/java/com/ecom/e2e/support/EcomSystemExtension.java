package com.ecom.e2e.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Starts the {@link EcomSystem} once per JVM and stops it after the last test class (via a
 * root-store CloseableResource, JUnit's "after all classes" hook). A JVM shutdown hook is the backstop.
 */
public final class EcomSystemExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrComputeIfAbsent(EcomSystemExtension.class, k -> {
                    EcomSystem sys = EcomSystem.get();
                    return (ExtensionContext.Store.CloseableResource) sys::stop;
                });
    }
}
