package org.ioc.container;

import javax.inject.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public class IocContainer implements Container {

  private final ConcurrentMap<Class<?>, Supplier<?>> singletons = new ConcurrentHashMap<>();
  private final ConcurrentMap<Class<?>, Class<?>> contracts = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Object> nameBindings = new ConcurrentHashMap<>();
  private final ConcurrentMap<Class<? extends Annotation>, Object> qualifierBindings =
      new ConcurrentHashMap<>();

  public IocContainer() {
    singletons.put(Container.class, () -> this);
  }

  public <T> T resolve(Class<T> type) {
    return resolveInternal(type, new HashSet<>());
  }

  public <T> void registerSingleton(T instance) {
    singletons.put(instance.getClass(), () -> instance);
  }

  public <T, V extends T> void registerSingleton(Class<T> contract, V instance) {
    singletons.put(contract, () -> instance);
  }

  public <T> void registerContract(Class<T> contract) {
    contracts.put(contract, contract);
  }

  public <T, V extends T> void registerContract(Class<T> contract, Class<V> binding) {
    contracts.put(contract, binding);
  }

  public void registerNamed(String name, Object value) {
    nameBindings.put(name, value);
  }

  public void registerQualified(Class<? extends Annotation> qualifier, final Object binding) {
    qualifierBindings.put(qualifier, binding);
  }

  @SuppressWarnings("unchecked")
  private <T> T resolveInternal(final Class<T> type, final Set<Class<?>> openTypeRequests) {
    if (singletons.containsKey(type)) {
      return type.cast(singletons.get(type).get());
    }
    if (openTypeRequests.contains(type)) {
      throw new IllegalStateException("Cyclic dependency detected!");
    }
    openTypeRequests.add(type);
    Class<T> requestedType;
    if (contracts.containsKey(type)) {
      requestedType = (Class<T>) contracts.get(type);
    } else {
      final Optional<Constructor<?>> defaultConstructor =
          getDefaultConstructor(type.getConstructors());
      if (!defaultConstructor.isPresent()) {
        throw new IllegalStateException("Unknown type: " + type);
      } else {
        return callConstructor(defaultConstructor.get(), type);
      }
    }
    final Constructor<?> injectionPoint = getInjectionPoint(requestedType.getConstructors());
    final T resolvedValue =
        callConstructor(
            injectionPoint,
            requestedType,
            resolveDependencies(injectionPoint.getParameters(), openTypeRequests));
    if (type.isAnnotationPresent(Singleton.class)) {
      singletons.put(type, () -> resolvedValue);
    }
    return resolvedValue;
  }

  private Object[] resolveDependencies(
      final Parameter[] parameters, Set<Class<?>> openTypeRequests) {
    return Arrays.stream(parameters)
        .map(
            parameter -> {
              final Class<?> type = parameter.getType();
              if (parameter.isAnnotationPresent(Named.class)) {
                return resolveNamed(parameter, openTypeRequests);
              }
              final Optional<Annotation> qualifier =
                  Arrays.stream(parameter.getDeclaredAnnotations())
                      .filter(
                          annotation ->
                              Arrays.stream(annotation.annotationType().getDeclaredAnnotations())
                                  .anyMatch(
                                      parentAnnotation ->
                                          Qualifier.class.isAssignableFrom(
                                              parentAnnotation.getClass())))
                      .findFirst();
              if (qualifier.isPresent()) {
                return resolveQualifier(parameter, qualifier.get(), openTypeRequests);
              }
              if (type.equals(Provider.class)) {
                return resolveProvider(parameter, openTypeRequests);
              }
              return resolveInternal(type, openTypeRequests);
            })
        .toArray();
  }

  @SuppressWarnings("unchecked")
  private Object resolveQualifier(
      final Parameter parameter, final Annotation qualifier, final Set<Class<?>> openTypeRequests) {
    final Class<?> qualifierType = qualifier.annotationType();
    final Object binding = qualifierBindings.get(qualifierType);
    if (binding == null) {
      throw new IllegalStateException("No type available for qualifier " + qualifierType);
    }
    final Object resolvedValue;
    if (binding.getClass() != Class.class) {
      resolvedValue = binding;
    } else {
      resolvedValue = resolveInternal((Class) binding, openTypeRequests);
    }
    final Class<?> requestedType = parameter.getType();
    final Class<?> providedType = resolvedValue.getClass();
    if (!requestedType.isAssignableFrom(resolvedValue.getClass())) {
      throw new IllegalStateException(
          "Incompatible type was provided! Requested type: "
              + requestedType
              + ", qualified by: "
              + qualifierType
              + ", provided type: "
              + providedType);
    }
    return resolvedValue;
  }

  @SuppressWarnings("rawtypes")
  private Provider<?> resolveProvider(
      final Parameter parameter, final Set<Class<?>> openTypeRequests) {
    if (parameter.getParameterizedType() instanceof ParameterizedType) {
      final Class<?> requestedType =
          (Class<?>)
              ((ParameterizedType) parameter.getParameterizedType()).getActualTypeArguments()[0];
      return (Provider) () -> resolve(requestedType);
    }
    return resolveInternal(Provider.class, openTypeRequests);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Object resolveNamed(final Parameter parameter, final Set<Class<?>> openTypeRequests) {
    final String name = parameter.getAnnotation(Named.class).value();
    if (nameBindings.get(name) == null) {
      throw new IllegalStateException(
          "Parameter @Named(\"" + name + "\") was requested but not provided!");
    }
    final Object value = nameBindings.get(name);
    Object resolvedValue;
    if (value instanceof Class) {
      resolvedValue = resolveInternal((Class) value, openTypeRequests);
    } else {
      resolvedValue = value;
    }
    final Class<?> requestedType = parameter.getType();
    final Class<?> providedType = resolvedValue.getClass();
    if (!requestedType.isAssignableFrom(providedType)) {
      throw new IllegalStateException(
          "Incompatible type was provided! Requested type: "
              + requestedType
              + ", named: "
              + name
              + ", provided type: "
              + providedType);
    }
    return resolvedValue;
  }

  private Constructor<?> getInjectionPoint(final Constructor<?>[] constructors) {
    Constructor<?> defaultConstructor = null;
    for (final Constructor<?> constructor : constructors) {
      if (constructor.getAnnotation(Inject.class) != null) {
        return constructor;
      } else if (isDefaultConstructor(constructor)) {
        defaultConstructor = constructor;
      }
    }
    if (defaultConstructor != null) {
      return defaultConstructor;
    }
    throw new IllegalStateException("No suitable constructor found!");
  }

  private boolean isDefaultConstructor(final Constructor<?> constructor) {
    return constructor.getParameterTypes().length == 0
        && Modifier.isPublic(constructor.getModifiers());
  }

  private Optional<Constructor<?>> getDefaultConstructor(final Constructor<?>[] constructors) {
    return Arrays.stream(constructors).filter(this::isDefaultConstructor).findFirst();
  }

  private <T> T callConstructor(
      final Constructor<?> constructor, final Class<T> type, final Object... params) {
    try {
      return type.cast(constructor.newInstance(params));
    } catch (InstantiationException | InvocationTargetException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
