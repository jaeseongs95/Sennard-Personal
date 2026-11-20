// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.support;

import java.lang.reflect.Method;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Declarative error translation (a common treatment after the arguments are bound is advice): around every public method of a
 * {@code @Service} of the application layer, what Spring's data access and Jakarta Validation throw becomes an {@code SennardException} by
 * {@link PersistenceErrors}. Its order puts it outside the transaction, so a failure of the commit is translated too.
 */
@Component("errorTranslationAdvisor")
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public final class ErrorTranslationAdvisor extends AbstractPointcutAdvisor {
    private static final long serialVersionUID = 1L;
    /** Before the transaction advice (lowest precedence), so it is outside the transaction, the validation's advice inside. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1000;
    private static final String APPLICATION_PACKAGE = "io.github.jaeseongs95.sennard.app.";

    private final transient Pointcut pointcut = new StaticMethodMatcherPointcut() {
        {
            setClassFilter((ClassFilter) type -> type.getName().startsWith(APPLICATION_PACKAGE) && AnnotatedElementUtils.hasAnnotation(type, Service.class));
        }

        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            return java.lang.reflect.Modifier.isPublic(method.getModifiers());
        }
    };
    private final transient MethodInterceptor advice = invocation -> {
        try {
            return invocation.proceed();
        } catch (RuntimeException failure) {
            // Boundary (service layer): the one translation of persistence failures into Sennard's own; what it does not translate is rethrown as it is.
            RuntimeException translated = PersistenceErrors.translate(failure);
            if (translated == failure) {
                throw failure;
            }
            throw translated;
        }
    };

    public ErrorTranslationAdvisor() {
        setOrder(ORDER);
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return advice;
    }
}
