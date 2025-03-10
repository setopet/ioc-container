package org.ioc.container;

public interface Container {

  <T> T resolve(final Class<T> type);
}
