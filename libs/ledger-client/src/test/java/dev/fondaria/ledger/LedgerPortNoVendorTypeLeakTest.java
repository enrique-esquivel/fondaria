package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code LedgerPort} and its domain types must never leak a TigerBeetle vendor type into a public
 * signature, field or exception (docs/ENGINEERING-RULES.md §4, "Never use a TigerBeetle type in
 * service code"). This module also carries no compile dependency on the TigerBeetle client
 * (enforced by the {@code ban-tigerbeetle-client} rule in this module's {@code pom.xml}), so this
 * test exists to catch the case where a vendor type sneaks in transitively through some other
 * dependency.
 */
class LedgerPortNoVendorTypeLeakTest {

  private static final String FORBIDDEN_PACKAGE_PREFIX = "com.tigerbeetle";

  private static final List<Class<?>> PUBLIC_API =
      List.of(
          LedgerPort.class,
          AccountId.class,
          TransferId.class,
          CreateAccountCommand.class,
          CreateTransferCommand.class,
          CreateTransferCommand.Cash.class,
          CreateTransferCommand.Unit.class,
          LedgerAccount.class,
          LedgerAccount.Cash.class,
          LedgerAccount.Unit.class,
          LedgerTransfer.class,
          LedgerTransfer.Cash.class,
          LedgerTransfer.Unit.class,
          TransferResult.class,
          TransferResult.Created.class,
          TransferResult.AlreadyExists.class,
          TransferResult.Rejected.class,
          TransferKind.class,
          TransferQuery.class,
          LedgerRejectionReason.class);

  @Test
  void noPublicApiTypeLeaksTheVendorPackage() {
    List<String> offenders = new ArrayList<>();
    for (Class<?> clazz : PUBLIC_API) {
      checkType(clazz, clazz.getSimpleName() + " itself", offenders);
      for (Method method : clazz.getDeclaredMethods()) {
        if (Modifier.isPublic(method.getModifiers())) {
          String location = clazz.getSimpleName() + "#" + method.getName();
          checkType(method.getGenericReturnType(), location + " return type", offenders);
          for (Type paramType : method.getGenericParameterTypes()) {
            checkType(paramType, location + " parameter", offenders);
          }
        }
      }
      for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
        if (Modifier.isPublic(constructor.getModifiers())) {
          for (Type paramType : constructor.getGenericParameterTypes()) {
            checkType(paramType, clazz.getSimpleName() + " constructor parameter", offenders);
          }
        }
      }
    }
    assertThat(offenders).as("public API members referencing a vendor type").isEmpty();
  }

  private void checkType(Type type, String location, List<String> offenders) {
    if (type instanceof Class<?> clazz) {
      if (clazz.getPackageName().startsWith(FORBIDDEN_PACKAGE_PREFIX)) {
        offenders.add(location + " -> " + clazz.getName());
      }
    } else if (type instanceof ParameterizedType parameterized) {
      checkType(parameterized.getRawType(), location, offenders);
      for (Type argument : parameterized.getActualTypeArguments()) {
        checkType(argument, location, offenders);
      }
    }
  }
}
