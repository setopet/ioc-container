package org.ioc.container;

import org.junit.jupiter.api.Test;

import javax.inject.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.*;

public class IocContainerTest {

  public static class ServiceB {

    public ServiceB(final String someString) {}
  }

  public static class ServiceC {

    @Inject
    public ServiceC(final ServiceA serviceA) {}
  }

  public static class ServiceD {

    @Inject
    public ServiceD(final ServiceE serviceE) {}
  }

  public static class ServiceE {

    @Inject
    public ServiceE(final ServiceD serviceD) {}
  }

  public static class ServiceF {

    private final Provider<ServiceB> serviceBProvider;

    @Inject
    public ServiceF(final Provider<ServiceB> serviceB) {
      this.serviceBProvider = serviceB;
    }

    public Provider<ServiceB> getServiceBProvider() {
      return serviceBProvider;
    }
  }

  public static class ServiceG {

    private final Provider<ServiceH> provider;

    @Inject
    public ServiceG(final Provider<ServiceH> provider) {
      this.provider = provider;
    }
  }

  public static class ServiceH {

    @Inject
    public ServiceH(final ServiceG serviceG) {}
  }

  public static class ServiceI {

    private final String something;

    @Inject
    public ServiceI(final @Named("something") String something) {
      this.something = something;
    }
  }

  public static class ServiceJ implements Contract {}

  public static class ServiceK implements Contract {}

  public static class ServiceL {

    private Contract contract;

    @Inject
    public ServiceL(final @Named("requestServiceK") Contract contract) {
      this.contract = contract;
    }
  }

  @Singleton
  public static class ServiceM {}

  public static class ServiceN {

    private final Contract contract;

    @Inject
    public ServiceN(final @SpecialType Contract contract) {
      this.contract = contract;
    }
  }

  public static class ServiceO {

    private final Container container;

    @Inject
    public ServiceO(final Container container) {
      this.container = container;
    }
  }

  public interface Contract {}

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  public @interface SpecialType {}

  private final IocContainer container = new IocContainer();

  public static class ServiceA implements Contract {}

  @Test
  public void shouldResolveServiceWithoutDependencies() {
    assertNotNull(container.resolve(ServiceA.class));
  }

  @Test
  public void shouldThrowOnMissingSuitableConstructor() {
    assertThrows(IllegalStateException.class, () -> container.resolve(ServiceB.class));
  }

  @Test
  public void shouldResolveProvided() {
    final ServiceB serviceB = new ServiceB("something");
    container.registerSingleton(ServiceB.class, serviceB);
    assertSame(serviceB, container.resolve(ServiceB.class));
  }

  @Test
  public void shouldResolveByContract() {
    container.registerContract(Contract.class, ServiceA.class);
    assertInstanceOf(ServiceA.class, container.resolve(Contract.class));
  }

  @Test
  public void shouldResolveDependencies() {
    container.registerContract(ServiceC.class);
    assertNotNull(container.resolve(ServiceC.class));
  }

  @Test
  public void shouldRequireContractRegistration() {
    assertThrows(IllegalStateException.class, () -> container.resolve(ServiceC.class));
  }

  @Test
  public void shouldHandleCyclicDependencies() {
    container.registerContract(ServiceE.class);
    assertThrows(IllegalStateException.class, () -> container.resolve(ServiceD.class));
  }

  @Test
  public void shouldInjectProviders() {
    final ServiceB serviceB = new ServiceB("something");
    container.registerSingleton(serviceB);
    container.registerContract(ServiceF.class);
    assertNotNull(container.resolve(ServiceF.class));
    assertSame(serviceB, container.resolve(ServiceF.class).getServiceBProvider().get());
  }

  @Test
  public void shouldInjectLazyProviders() {
    container.registerContract(ServiceH.class);
    container.registerContract(ServiceG.class);
    final ServiceG serviceG = container.resolve(ServiceG.class);
    assertNotNull(serviceG);
    assertNotNull(container.resolve(ServiceH.class));
    assertNotNull(serviceG.provider.get());
  }

  @Test
  public void shouldProvideNamed() {
    container.registerNamed("something", "my provided thing");
    container.registerContract(ServiceI.class);
    assertEquals("my provided thing", container.resolve(ServiceI.class).something);
  }

  @Test
  public void shouldThrowOnWrongNamedType() {
    container.registerNamed("something", new Object());
    container.registerContract(ServiceI.class);
    assertThrows(IllegalStateException.class, () -> container.resolve(ServiceI.class));
  }

  @Test
  public void shouldThrowOnWrongQualifiedType() {
    container.registerQualified(SpecialType.class, new Object());
    container.registerContract(ServiceN.class);
    assertThrows(IllegalStateException.class, () -> container.resolve(ServiceN.class));
  }

  @Test
  public void shouldPrioritizeNamedRegistrationOverContractRegistration() {
    container.registerContract(Contract.class, ServiceJ.class);
    container.registerNamed("requestServiceK", ServiceK.class);
    container.registerContract(ServiceL.class);
    assertInstanceOf(ServiceK.class, container.resolve(ServiceL.class).contract);
  }

  @Test
  public void shouldPrioritizeQualifierRegistrationOverContractRegistration() {
    container.registerContract(Contract.class, ServiceJ.class);
    container.registerQualified(SpecialType.class, ServiceK.class);
    container.registerContract(ServiceN.class);
    assertInstanceOf(ServiceK.class, container.resolve(ServiceN.class).contract);
  }

  @Test
  public void shouldReuseSingletonInstances() {
    container.registerContract(ServiceM.class);
    assertSame(container.resolve(ServiceM.class), container.resolve(ServiceM.class));
  }

  @Test
  public void shouldProvideNewInstancesForNonSingleton() {
    container.registerContract(ServiceA.class);
    assertNotSame(container.resolve(ServiceA.class), container.resolve(ServiceA.class));
  }

  @Test
  public void shouldProvideContainer() {
    container.registerContract(ServiceO.class);
    assertSame(container, container.resolve(ServiceO.class).container);
  }
}
