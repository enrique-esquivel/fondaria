package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReflectionTypesTest {

  private static final List<Class<?>> FORBIDDEN_TYPES =
      List.of(double.class, Double.class, float.class, Float.class, BigDecimal.class);

  @Test
  void assertNoForbiddenTypesInPublicApi() {
    List<Class<?>> targetClasses =
        List.of(
            Money.class,
            Units.class,
            Nav.class,
            CurrencyCode.class,
            CurrencyMismatchException.class);

    for (Class<?> clazz : targetClasses) {
      for (Method method : clazz.getDeclaredMethods()) {
        if (Modifier.isPublic(method.getModifiers())) {
          Class<?> returnType = method.getReturnType();
          for (Class<?> forbidden : FORBIDDEN_TYPES) {
            assertThat(returnType)
                .withFailMessage(
                    "Method %s in %s has forbidden return type %s",
                    method.getName(), clazz.getSimpleName(), forbidden.getSimpleName())
                .isNotEqualTo(forbidden);
          }
          for (Class<?> paramType : method.getParameterTypes()) {
            for (Class<?> forbidden : FORBIDDEN_TYPES) {
              assertThat(paramType)
                  .withFailMessage(
                      "Method %s in %s has forbidden parameter type %s",
                      method.getName(), clazz.getSimpleName(), forbidden.getSimpleName())
                  .isNotEqualTo(forbidden);
            }
          }
        }
      }

      for (Constructor<?> constructor : clazz.getConstructors()) {
        if (Modifier.isPublic(constructor.getModifiers())) {
          for (Class<?> paramType : constructor.getParameterTypes()) {
            for (Class<?> forbidden : FORBIDDEN_TYPES) {
              assertThat(paramType)
                  .withFailMessage(
                      "Constructor in %s has forbidden parameter type %s",
                      clazz.getSimpleName(), forbidden.getSimpleName())
                  .isNotEqualTo(forbidden);
            }
          }
        }
      }
    }
  }
}
